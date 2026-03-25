#!/usr/bin/env python3
"""
Extract merge_commits.csv and all_conflicts.csv from the SQL dump.

Reads Java_1767374472.sql (exported from calculon.inf.unibe.ch:5013) and
produces two artefacts:

  merge_commits.csv   — one row per merge, Maven projects only, with exact
                        commit SHAs from the DB.
  all_conflicts.csv   — one row per conflict chunk with all ML-relevant
                        features derived directly from the SQL columns.
                        Absorbs the logic formerly in add_file_lex_rank.py — no
                        post-processing needed.

Usage:
    python3 extract_from_sql_dump.py [--sql PATH]

GITHUB_TOKEN env var is used automatically when probing projects not yet in
maven_check_cache.json.  Set it once:
    echo 'export GITHUB_TOKEN=ghp_yourToken' >> ~/.bashrc && source ~/.bashrc
"""

import argparse
import csv
import json
import os
import re
import sys
import time
import urllib.request
import urllib.error
from collections import defaultdict
from datetime import date
from pathlib import Path

# ── Paths ──────────────────────────────────────────────────────────────────────
DATA_BASE_DIR     = Path.home() / 'data/bruteforcemerge'
COMMON_DIR        = DATA_BASE_DIR / 'common'          # shared: SQL, CSVs, cache
RQ1_DIR           = DATA_BASE_DIR / 'rq1'
DEFAULT_SQL_DUMP  = COMMON_DIR / 'Java_1767374472.sql'
MAVEN_CACHE_FILE  = COMMON_DIR / 'maven_check_cache.json'
MERGE_COMMITS_CSV = COMMON_DIR / 'merge_commits.csv'  # shared by RQ2 + RQ3
ALL_CONFLICTS_CSV = COMMON_DIR / 'all_conflicts.csv'

# ── HTTP config ────────────────────────────────────────────────────────────────
PROBE_TIMEOUT = 10
RETRY_DELAY   = 5
MAX_RETRIES   = 3

# ── Keyword stems in mergeRangekeywordFrequency JSON ──────────────────────────
# These are the 12 stems used in the original Java_chunks.csv feature set.
KEYWORD_STEMS = [
    'fix', 'bug', 'feature', 'improv', 'document', 'refactor',
    'updat', 'add', 'remov', 'us', 'delet', 'chang',
]

EPOCH_ORIGIN = date(1970, 1, 1)


# ── CLI ────────────────────────────────────────────────────────────────────────

def parse_args():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--sql', default=str(DEFAULT_SQL_DUMP),
                   help='Path to SQL dump (default: %(default)s)')
    return p.parse_args()


# ── JSON cache helpers ─────────────────────────────────────────────────────────

def load_json(path: Path) -> dict:
    if path.exists():
        with open(path) as f:
            return json.load(f)
    return {}


def save_json(path: Path, data: dict):
    tmp = path.with_suffix('.tmp')
    with open(tmp, 'w') as f:
        json.dump(data, f, indent=2)
    tmp.replace(path)


# ── SQL parsing ────────────────────────────────────────────────────────────────

def parse_col_names(insert_line: str) -> list[str]:
    """Extract column names from an INSERT INTO ... (...) VALUES line."""
    m = re.search(r'INSERT INTO `\w+` \(([^)]+)\)', insert_line)
    if not m:
        return []
    return [c.strip().strip('`') for c in m.group(1).split(',')]


def parse_sql_row(line: str) -> list | None:
    """
    Parse one SQL data row such as:
        (1, 'OK', '', 12, 0, 'text', NULL),
    Returns a list of string values (NULL becomes None).
    """
    line = line.strip()
    if line.endswith(';'):
        line = line[:-1]
    if line.endswith(','):
        line = line[:-1]

    if not (line.startswith('(') and line.endswith(')')):
        return None
    line = line[1:-1]

    values = []
    i = 0
    n = len(line)

    while i < n:
        while i < n and line[i] == ' ':
            i += 1
        if i >= n:
            break

        if line[i] == "'":
            i += 1
            s: list[str] = []
            while i < n:
                c = line[i]
                if c == '\\' and i + 1 < n:
                    nc = line[i + 1]
                    s.append('\n' if nc == 'n' else
                             '\r' if nc == 'r' else
                             '\t' if nc == 't' else nc)
                    i += 2
                elif c == "'" and i + 1 < n and line[i + 1] == "'":
                    s.append("'")
                    i += 2
                elif c == "'":
                    i += 1
                    break
                else:
                    s.append(c)
                    i += 1
            values.append(''.join(s))

        elif line[i:i + 4] == 'NULL':
            values.append(None)
            i += 4

        elif line[i] == '_':
            # _binary'...' or similar prefix
            while i < n and line[i] != "'":
                i += 1
            if i < n:
                i += 1
                s = []
                while i < n:
                    c = line[i]
                    if c == '\\' and i + 1 < n:
                        s.append(line[i + 1])
                        i += 2
                    elif c == "'":
                        i += 1
                        break
                    else:
                        s.append(c)
                        i += 1
                values.append(''.join(s))

        else:
            j = i
            while j < n and line[j] != ',':
                j += 1
            values.append(line[i:j].strip())
            i = j

        while i < n and line[i] == ' ':
            i += 1
        if i < n and line[i] == ',':
            i += 1

    return values


# ── Maven check ────────────────────────────────────────────────────────────────

def extract_owner_repo(remote_url: str) -> tuple[str, str] | None:
    url = remote_url.rstrip('/').removesuffix('.git')
    parts = url.split('github.com/', 1)
    if len(parts) != 2:
        return None
    segments = parts[1].split('/')
    if len(segments) < 2:
        return None
    return segments[0], segments[1]


def has_pom_xml(owner: str, repo: str, token: str | None) -> bool | None:
    url = f'https://api.github.com/repos/{owner}/{repo}/contents/pom.xml'
    req = urllib.request.Request(url)
    req.add_header('Accept', 'application/vnd.github+json')
    req.add_header('X-GitHub-Api-Version', '2022-11-28')
    if token:
        req.add_header('Authorization', f'Bearer {token}')

    for attempt in range(1, MAX_RETRIES + 1):
        try:
            with urllib.request.urlopen(req, timeout=PROBE_TIMEOUT) as resp:
                return resp.status == 200
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return False
            if e.code in (403, 429):
                reset = e.headers.get('X-RateLimit-Reset')
                wait = (max(RETRY_DELAY, int(reset) - int(time.time()) + 2)
                        if reset else RETRY_DELAY * attempt)
                print(f'    Rate limited (HTTP {e.code}), waiting {wait}s …', flush=True)
                time.sleep(wait)
            else:
                print(f'    HTTP {e.code} for {owner}/{repo} (attempt {attempt})', flush=True)
                if attempt < MAX_RETRIES:
                    time.sleep(RETRY_DELAY)
        except Exception as exc:
            print(f'    Error for {owner}/{repo}: {exc} (attempt {attempt})', flush=True)
            if attempt < MAX_RETRIES:
                time.sleep(RETRY_DELAY)
    return None


def check_maven_for_missing(projects: dict, maven_cache: dict,
                             cache_path: Path, token: str | None):
    """Probe GitHub for projects not yet in maven_cache; update cache in place."""
    missing = [(pid, url) for pid, (name, url) in projects.items()
               if str(pid) not in maven_cache]
    if not missing:
        print(f'All {len(projects)} projects already have maven status cached.')
        return

    print(f'Maven check: {len(missing)} projects not in cache, '
          f'{len(projects) - len(missing)} cached.')
    for i, (pid, url) in enumerate(missing, 1):
        parsed = extract_owner_repo(url)
        if parsed is None:
            print(f'[{i}/{len(missing)}] SKIP (unparseable URL): {url}')
            maven_cache[str(pid)] = None
        else:
            owner, repo = parsed
            result = has_pom_xml(owner, repo, token)
            label = ('MAVEN' if result is True else
                     'NON-MAVEN' if result is False else 'UNKNOWN')
            print(f'[{i}/{len(missing)}] {owner}/{repo} → {label}', flush=True)
            maven_cache[str(pid)] = result
        save_json(cache_path, maven_cache)
        time.sleep(0.3)


# ── Date helpers ───────────────────────────────────────────────────────────────

def parse_date(s: str | None) -> date | None:
    """Parse 'YYYY-MM-DD' or 'YYYY-MM-DD HH:MM:SS...' into a date."""
    if not s:
        return None
    try:
        return date.fromisoformat(s.strip().split(' ')[0].split('T')[0])
    except Exception:
        return None


def days_between(d1: date | None, d2: date | None) -> str:
    """Return abs(d1 - d2).days as a string, or '' if either is None."""
    if d1 is None or d2 is None:
        return ''
    return str(abs((d1 - d2).days))


def date_minus(d1: date | None, d2: date | None) -> str:
    """Return (d1 - d2).days as a string (can be negative), or '' if None."""
    if d1 is None or d2 is None:
        return ''
    return str((d1 - d2).days)


# ── Keyword JSON parsing ───────────────────────────────────────────────────────

def parse_keywords(kw_json: str | None) -> dict[str, int]:
    """Parse mergeRangekeywordFrequency JSON → {stem: count}."""
    if not kw_json:
        return {}
    try:
        return json.loads(kw_json)
    except Exception:
        return {}


# ── LOC and changed-file derived columns ──────────────────────────────────────

def _i(v: str | None) -> int:
    try:
        return int(v) if v else 0
    except (ValueError, TypeError):
        return 0


def compute_merge_derived(m: dict) -> None:
    """Compute derived columns and store them back in the merge dict."""
    # locAdded/locRemoved = conflicting + clean (insert/delete + replace insert/delete)
    m['locAddedOURS'] = str(
        _i(m.get('filesConflictingMergedOurs_LocInsert')) +
        _i(m.get('filesConflictingMergedOurs_LocReplaceInsert')) +
        _i(m.get('filesCleanMergedOurs_LocInsert')) +
        _i(m.get('filesCleanMergedOurs_LocReplaceInsert'))
    )
    m['locRemovedOURS'] = str(
        _i(m.get('filesConflictingMergedOurs_LocDelete')) +
        _i(m.get('filesConflictingMergedOurs_LocReplaceDelete')) +
        _i(m.get('filesCleanMergedOurs_LocDelete')) +
        _i(m.get('filesCleanMergedOurs_LocReplaceDelete'))
    )
    m['locAddedTHEIRS'] = str(
        _i(m.get('filesConflictingMergedTheirs_LocInsert')) +
        _i(m.get('filesConflictingMergedTheirs_LocReplaceInsert')) +
        _i(m.get('filesCleanMergedTheirs_LocInsert')) +
        _i(m.get('filesCleanMergedTheirs_LocReplaceInsert'))
    )
    m['locRemovedTHEIRS'] = str(
        _i(m.get('filesConflictingMergedTheirs_LocDelete')) +
        _i(m.get('filesConflictingMergedTheirs_LocReplaceDelete')) +
        _i(m.get('filesCleanMergedTheirs_LocDelete')) +
        _i(m.get('filesCleanMergedTheirs_LocReplaceDelete'))
    )
    # changedFilesOurs/Theirs = files only on that branch + conflicting files
    conflicting = _i(m.get('filesConflictingMerged'))
    m['changedFilesOURS'] = str(
        _i(m.get('filesModifiedOnlyOnOurs_Commit')) +
        _i(m.get('filesAddedOnlyOnOurs_Commit')) +
        _i(m.get('filesDeletedOnlyOnOurs_Commit')) +
        conflicting
    )
    m['changedFilesTHEIRS'] = str(
        _i(m.get('filesModifiedOnlyOnTheirs_Commit')) +
        _i(m.get('filesAddedOnlyOnTheirs_Commit')) +
        _i(m.get('filesDeletedOnlyOnTheirs_Commit')) +
        conflicting
    )
    # Duration features
    commit_dt   = parse_date(m.get('commitTime'))
    ours_dt     = parse_date(m.get('oursCommitTime'))
    theirs_dt   = parse_date(m.get('theirsCommitTime'))
    oldest_dt   = parse_date(m.get('mergeRangeOldestCommitTime'))
    m['mergeRangeDurationDays'] = date_minus(commit_dt, oldest_dt)
    m['branchDivergenceDays']   = days_between(ours_dt, theirs_dt)

    # selfConflict = 1 if all contributors are on both branches
    m['selfConflict'] = '1' if m.get('authorsIntersectionMergeRange') == 'ALL' else '0'

    # Keywords
    kw = parse_keywords(m.get('_kw_json'))
    for stem in KEYWORD_STEMS:
        m[f'keyword_{stem}'] = str(kw.get(stem, 0))


# ── Pass 1: load lookup tables ─────────────────────────────────────────────────

MERGE_SQL_COLS = [
    'projectReportID',
    'commitId', 'commitTime', 'oursCommitTime', 'theirsCommitTime',
    'baseCommitTime', 'mergeRangeOldestCommitTime',
    'mergeAuthorContribution',
    'mergeRangeCommitCountOurs', 'mergeRangeCommitCountTheirs',
    'mergeRangeOursAuthorsCount', 'mergeRangeTheirsAuthorsCount',
    'authorsMultipleContributorsMergeRange', 'authorsIntersectionMergeRange',
    'authorsOnlyOneBranchMergeRange', 'authorsBothBranchesMergeRange',
    'conclusionDelay',
    'filesConflictingMerged', 'filesCleanMerged', 'fileCount',
    # LOC columns needed for derivations (not kept in CSV directly)
    'filesConflictingMergedOurs_LocInsert', 'filesConflictingMergedOurs_LocDelete',
    'filesConflictingMergedOurs_LocReplaceInsert', 'filesConflictingMergedOurs_LocReplaceDelete',
    'filesConflictingMergedTheirs_LocInsert', 'filesConflictingMergedTheirs_LocDelete',
    'filesConflictingMergedTheirs_LocReplaceInsert', 'filesConflictingMergedTheirs_LocReplaceDelete',
    'filesCleanMergedOurs_LocInsert', 'filesCleanMergedOurs_LocDelete',
    'filesCleanMergedOurs_LocReplaceInsert', 'filesCleanMergedOurs_LocReplaceDelete',
    'filesCleanMergedTheirs_LocInsert', 'filesCleanMergedTheirs_LocDelete',
    'filesCleanMergedTheirs_LocReplaceInsert', 'filesCleanMergedTheirs_LocReplaceDelete',
    # Changed-file counts for derivations
    'filesModifiedOnlyOnOurs_Commit', 'filesAddedOnlyOnOurs_Commit', 'filesDeletedOnlyOnOurs_Commit',
    'filesModifiedOnlyOnTheirs_Commit', 'filesAddedOnlyOnTheirs_Commit', 'filesDeletedOnlyOnTheirs_Commit',
    # Keyword JSON
    'mergeRangekeywordFrequency',
]

FILE_SQL_COLS = [
    'conflictingMergeReportID', 'filename',
    'chunkCount', 'lineCountUnmergedFile', 'cyclomaticComplexity',
]

LOOKUP_TABLES = {'ProjectReportSEART', 'ConflictingMergeReport', 'ConflictingFileReport'}


def load_lookup_tables(sql_path: Path) -> tuple[dict, dict, dict]:
    """
    Stream the SQL dump and collect:
      projects: {id_str: (projectName, remoteURL)}
      merges:   {id_str: dict_of_merge_features}
      files:    {id_str: dict_of_file_features}
    """
    projects: dict[str, tuple] = {}
    merges:   dict[str, dict]  = {}
    files:    dict[str, dict]  = {}

    current_table: str | None = None
    col_idx: dict[str, int] = {}

    print('Pass 1: loading ProjectReportSEART, ConflictingMergeReport, '
          'ConflictingFileReport …')
    with open(sql_path, 'r', errors='replace') as f:
        for lineno, line in enumerate(f, 1):
            if lineno % 200_000 == 0:
                print(f'  line {lineno:,}  projects={len(projects)} '
                      f'merges={len(merges)} files={len(files)}', flush=True)

            if line.startswith('INSERT INTO `'):
                m = re.match(r'INSERT INTO `(\w+)`', line)
                if m and m.group(1) in LOOKUP_TABLES:
                    current_table = m.group(1)
                    cols = parse_col_names(line)
                    col_idx = {name: i for i, name in enumerate(cols)}
                else:
                    current_table = None
                continue

            if not current_table:
                continue
            if not line.strip().startswith('('):
                continue

            vals = parse_sql_row(line)
            if not vals:
                continue

            try:
                row_id = vals[col_idx['id']]

                if current_table == 'ProjectReportSEART':
                    projects[row_id] = (
                        vals[col_idx['projectName']] or '',
                        vals[col_idx['remoteURL']] or '',
                    )

                elif current_table == 'ConflictingMergeReport':
                    md: dict[str, str | None] = {}
                    for col in MERGE_SQL_COLS:
                        if col == 'mergeRangekeywordFrequency':
                            md['_kw_json'] = vals[col_idx[col]] if col in col_idx else None
                        elif col in col_idx:
                            md[col] = vals[col_idx[col]]
                    compute_merge_derived(md)
                    merges[row_id] = md

                elif current_table == 'ConflictingFileReport':
                    fd: dict[str, str | None] = {}
                    for col in FILE_SQL_COLS:
                        if col in col_idx:
                            fd[col] = vals[col_idx[col]]
                    # rename SQL column for clarity in CSV
                    fd['cyclomaticComplexityFile'] = fd.pop('cyclomaticComplexity', None)
                    files[row_id] = fd

            except (IndexError, KeyError):
                pass

    print(f'Pass 1 done: {len(projects)} projects, {len(merges)} merges, '
          f'{len(files)} files')
    return projects, merges, files


# ── Rank computation ───────────────────────────────────────────────────────────

def build_file_lex_ranks(files: dict) -> dict[str, str]:
    """Compute 1-based lexicographic rank of each file within its merge."""
    merge_files: dict[str, list[tuple[str, str]]] = defaultdict(list)
    for fid, fd in files.items():
        mid = fd.get('conflictingMergeReportID', '')
        fname = fd.get('filename', '')
        merge_files[mid].append((fname, fid))

    file_lex_rank: dict[str, str] = {}
    for items in merge_files.values():
        for rank, (_, fid) in enumerate(sorted(items), 1):
            file_lex_rank[fid] = str(rank)
    return file_lex_rank


def build_chunk_ranks(sql_path: Path) -> dict[str, str]:
    """
    Mini-pass over ConflictingChunkReport: collect (chunk_id, file_report_id)
    and return chunkRankInFile: chunk_id → 1-based rank within its file.
    """
    chunks_per_file: dict[str, list[int]] = defaultdict(list)
    current_table: str | None = None
    col_idx: dict[str, int] = {}

    print('Rank pass: collecting chunk IDs per file …')
    with open(sql_path, 'r', errors='replace') as f:
        for lineno, line in enumerate(f, 1):
            if line.startswith('INSERT INTO `'):
                m = re.match(r'INSERT INTO `(\w+)`', line)
                current_table = m.group(1) if m else None
                if current_table == 'ConflictingChunkReport':
                    cols = parse_col_names(line)
                    col_idx = {name: i for i, name in enumerate(cols)}
                continue
            if current_table != 'ConflictingChunkReport':
                continue
            if not line.strip().startswith('('):
                continue
            vals = parse_sql_row(line)
            if not vals:
                continue
            try:
                cid = vals[col_idx['id']]
                fid = vals[col_idx['conflictingFileReportID']]
                chunks_per_file[fid].append(int(cid))
            except (IndexError, KeyError, ValueError):
                pass

    chunk_rank: dict[str, str] = {}
    for fid, cids in chunks_per_file.items():
        for rank, cid in enumerate(sorted(cids), 1):
            chunk_rank[str(cid)] = str(rank)

    total = sum(len(v) for v in chunks_per_file.values())
    print(f'Rank pass done: {total:,} chunks across {len(chunks_per_file):,} files')
    return chunk_rank


# ── Write merge_commits.csv ────────────────────────────────────────────────────

def write_merge_commits(projects: dict, merges: dict, maven_cache: dict,
                        out_path: Path):
    """Write one row per Maven merge to merge_commits.csv."""
    written = skipped = 0
    with open(out_path, 'w', newline='') as f:
        w = csv.writer(f)
        w.writerow(['merge_id', 'commit_id', 'project_id', 'project_name',
                    'remote_url', 'commit_time', 'is_maven'])
        for mid, md in merges.items():
            pid = md.get('projectReportID', '')
            if maven_cache.get(str(pid)) is not True:
                skipped += 1
                continue
            name, url = projects.get(pid, ('', ''))
            w.writerow([mid, md.get('commitId', ''), pid, name, url,
                        md.get('commitTime', ''), 'True'])
            written += 1
    print(f'merge_commits.csv: {written:,} Maven merges written, '
          f'{skipped:,} non-Maven skipped')


# ── all_conflicts.csv column schema ───────────────────────────────────────────

ALL_CONFLICTS_HEADER = [
    # ── Identifiers ───────────────────────────────────────────────────────────
    'project_id', 'projectName', 'remoteURL', 'is_maven',
    'merge_id', 'commitId', 'commitTime',
    'oursCommitTime', 'theirsCommitTime', 'baseCommitTime',
    'mergeRangeOldestCommitTime',
    'file_report_id', 'filename', 'chunk_id', 'chunkIndex',
    'y_conflictResolutionResult',

    # ── Merge-level features (SQL-native) ─────────────────────────────────────
    'mergeAuthorContribution',
    'mergeRangeCommitCountOurs', 'mergeRangeCommitCountTheirs',
    'mergeRangeOursAuthorsCount', 'mergeRangeTheirsAuthorsCount',
    'authorsMultipleContributorsMergeRange', 'authorsIntersectionMergeRange',
    'authorsOnlyOneBranchMergeRange', 'authorsBothBranchesMergeRange',
    'conclusionDelay',
    'filesConflictingMerged', 'filesCleanMerged', 'fileCount',

    # ── Merge-level features (computed) ───────────────────────────────────────
    'locAddedOURS', 'locRemovedOURS', 'locAddedTHEIRS', 'locRemovedTHEIRS',
    'changedFilesOURS', 'changedFilesTHEIRS',
    'mergeRangeDurationDays', 'branchDivergenceDays',
    'selfConflict',

    # ── Keyword counts (parsed from mergeRangekeywordFrequency JSON) ──────────
    'keyword_fix', 'keyword_bug', 'keyword_feature', 'keyword_improv',
    'keyword_document', 'keyword_refactor', 'keyword_updat', 'keyword_add',
    'keyword_remov', 'keyword_us', 'keyword_delet', 'keyword_chang',

    # ── File-level features ───────────────────────────────────────────────────
    'chunkCount', 'lineCountUnmergedFile', 'cyclomaticComplexityFile',
    'file_lex_rank',

    # ── Chunk-level features (SQL-native) ─────────────────────────────────────
    'lengthOURS', 'lengthBASE', 'lengthTHEIRS',
    'lengthChunk',                          # = lengthOURS + lengthBASE + lengthTHEIRS
    'lengthRelativeOURS', 'lengthRelativeTHEIRS', 'lengthRelativeOURSTHEIRS',
    'lengthContextBefore', 'lengthContextAfter',
    'chunkPositionQuarter',
    'cyclomaticComplexityOURS', 'cyclomaticComplexityTHEIRS',

    # ── Chunk positional rank (computed) ──────────────────────────────────────
    'chunkRankInFile',
]

# Chunk columns to extract directly from SQL
CHUNK_SQL_COLS = [
    'id', 'conflictingFileReportID', 'chunkIndex',
    'lengthOURS', 'lengthBASE', 'lengthTHEIRS',
    'lengthRelativeOURS', 'lengthRelativeTHEIRS', 'lengthRelativeOURSTHEIRS',
    'lengthContextBefore', 'lengthContextAfter',
    'conflictResolutionResult',
    'chunkPositionQuarter',
    'cyclomaticComplexityOURS', 'cyclomaticComplexityTHEIRS',
]


# ── Pass 2: stream chunks → all_conflicts.csv ──────────────────────────────────

def write_all_conflicts(sql_path: Path, projects: dict, merges: dict,
                        files: dict, maven_cache: dict,
                        file_lex_ranks: dict, chunk_ranks: dict,
                        out_path: Path):
    """Stream ConflictingChunkReport, join with lookup dicts, write CSV."""
    current_table: str | None = None
    col_idx: dict[str, int] = {}
    written = skipped = 0

    print('Pass 2: streaming ConflictingChunkReport → all_conflicts.csv …')
    with open(sql_path, 'r', errors='replace') as f_in, \
         open(out_path, 'w', newline='') as f_out:

        w = csv.writer(f_out)
        w.writerow(ALL_CONFLICTS_HEADER)

        for lineno, line in enumerate(f_in, 1):
            if lineno % 200_000 == 0:
                print(f'  line {lineno:,}  chunks written={written:,}', flush=True)

            if line.startswith('INSERT INTO `'):
                m = re.match(r'INSERT INTO `(\w+)`', line)
                if m:
                    current_table = m.group(1)
                    if current_table == 'ConflictingChunkReport':
                        cols = parse_col_names(line)
                        col_idx = {name: i for i, name in enumerate(cols)}
                continue

            if current_table != 'ConflictingChunkReport':
                continue
            if not line.strip().startswith('('):
                continue

            vals = parse_sql_row(line)
            if not vals:
                continue

            try:
                cid = vals[col_idx['id']]
                fid = vals[col_idx['conflictingFileReportID']]

                file_info = files.get(fid)
                if file_info is None:
                    skipped += 1
                    continue
                mid = file_info['conflictingMergeReportID']

                merge_info = merges.get(mid)
                if merge_info is None:
                    skipped += 1
                    continue
                pid = merge_info.get('projectReportID', '')

                proj_info = projects.get(pid)
                if proj_info is None:
                    skipped += 1
                    continue
                proj_name, remote_url = proj_info

                is_maven = maven_cache.get(str(pid))
                is_maven_str = ('True'  if is_maven is True  else
                                'False' if is_maven is False else '')

                lo  = vals[col_idx.get('lengthOURS', -1)] or '0'
                lb  = vals[col_idx.get('lengthBASE', -1)] or '0'
                lt  = vals[col_idx.get('lengthTHEIRS', -1)] or '0'
                try:
                    length_chunk = str(_i(lo) + _i(lb) + _i(lt))
                except Exception:
                    length_chunk = ''

                def cv(col_name):
                    idx = col_idx.get(col_name)
                    return vals[idx] if idx is not None and idx < len(vals) else ''

                mi = merge_info  # alias

                w.writerow([
                    # Identifiers
                    pid, proj_name, remote_url, is_maven_str,
                    mid, mi.get('commitId', ''), mi.get('commitTime', ''),
                    mi.get('oursCommitTime', ''), mi.get('theirsCommitTime', ''),
                    mi.get('baseCommitTime', ''), mi.get('mergeRangeOldestCommitTime', ''),
                    fid, file_info.get('filename', ''),
                    cid, cv('chunkIndex'),
                    cv('conflictResolutionResult'),

                    # Merge-level SQL features
                    mi.get('mergeAuthorContribution', ''),
                    mi.get('mergeRangeCommitCountOurs', ''),
                    mi.get('mergeRangeCommitCountTheirs', ''),
                    mi.get('mergeRangeOursAuthorsCount', ''),
                    mi.get('mergeRangeTheirsAuthorsCount', ''),
                    mi.get('authorsMultipleContributorsMergeRange', ''),
                    mi.get('authorsIntersectionMergeRange', ''),
                    mi.get('authorsOnlyOneBranchMergeRange', ''),
                    mi.get('authorsBothBranchesMergeRange', ''),
                    mi.get('conclusionDelay', ''),
                    mi.get('filesConflictingMerged', ''),
                    mi.get('filesCleanMerged', ''),
                    mi.get('fileCount', ''),

                    # Merge-level computed features
                    mi.get('locAddedOURS', ''),
                    mi.get('locRemovedOURS', ''),
                    mi.get('locAddedTHEIRS', ''),
                    mi.get('locRemovedTHEIRS', ''),
                    mi.get('changedFilesOURS', ''),
                    mi.get('changedFilesTHEIRS', ''),
                    mi.get('mergeRangeDurationDays', ''),
                    mi.get('branchDivergenceDays', ''),
                    mi.get('selfConflict', ''),

                    # Keywords
                    mi.get('keyword_fix', '0'),
                    mi.get('keyword_bug', '0'),
                    mi.get('keyword_feature', '0'),
                    mi.get('keyword_improv', '0'),
                    mi.get('keyword_document', '0'),
                    mi.get('keyword_refactor', '0'),
                    mi.get('keyword_updat', '0'),
                    mi.get('keyword_add', '0'),
                    mi.get('keyword_remov', '0'),
                    mi.get('keyword_us', '0'),
                    mi.get('keyword_delet', '0'),
                    mi.get('keyword_chang', '0'),

                    # File-level features
                    file_info.get('chunkCount', ''),
                    file_info.get('lineCountUnmergedFile', ''),
                    file_info.get('cyclomaticComplexityFile', ''),
                    file_lex_ranks.get(fid, ''),

                    # Chunk-level SQL features
                    lo, lb, lt, length_chunk,
                    cv('lengthRelativeOURS'), cv('lengthRelativeTHEIRS'),
                    cv('lengthRelativeOURSTHEIRS'),
                    cv('lengthContextBefore'), cv('lengthContextAfter'),
                    cv('chunkPositionQuarter'),
                    cv('cyclomaticComplexityOURS'), cv('cyclomaticComplexityTHEIRS'),

                    # Chunk positional rank
                    chunk_ranks.get(cid, ''),
                ])
                written += 1

            except (IndexError, KeyError):
                skipped += 1

    print(f'all_conflicts.csv: {written:,} chunks written, '
          f'{skipped:,} skipped (join miss/parse error)')


# ── Entry point ────────────────────────────────────────────────────────────────

def main():
    args = parse_args()
    sql_path = Path(args.sql)

    if not sql_path.exists():
        sys.exit(f'ERROR: SQL dump not found: {sql_path}')

    token = os.environ.get('GITHUB_TOKEN')
    if not token:
        print('WARNING: GITHUB_TOKEN not set — rate limit is 60 req/h. '
              'Set it for 5000 req/h.', file=sys.stderr)

    # Pass 1: load small lookup tables
    projects, merges, files = load_lookup_tables(sql_path)

    # Maven check for projects not yet in cache
    maven_cache = load_json(MAVEN_CACHE_FILE)
    check_maven_for_missing(projects, maven_cache, MAVEN_CACHE_FILE, token)

    # Compute file_lex_rank from loaded files dict
    print('Computing file_lex_rank …')
    file_lex_ranks = build_file_lex_ranks(files)

    # Rank pass: collect chunk IDs per file for chunkRankInFile
    chunk_ranks = build_chunk_ranks(sql_path)

    # Write merge_commits.csv
    write_merge_commits(projects, merges, maven_cache, MERGE_COMMITS_CSV)

    # Pass 2: write all_conflicts.csv
    write_all_conflicts(sql_path, projects, merges, files, maven_cache,
                        file_lex_ranks, chunk_ranks, ALL_CONFLICTS_CSV)

    print(f'\nDone.')
    print(f'  {MERGE_COMMITS_CSV}')
    print(f'  {ALL_CONFLICTS_CSV}')
    print(f'  Columns: {len(ALL_CONFLICTS_HEADER)}')


if __name__ == '__main__':
    main()
