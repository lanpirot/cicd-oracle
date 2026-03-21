#!/usr/bin/env python3
"""
Check each project's GitHub repo for a root-level pom.xml (no cloning).
Adds an `is_maven` column (True/False) to Java_chunks_bruteforce.csv.

Usage:
    python3 add_maven_column.py [--token YOUR_GITHUB_TOKEN]

A GitHub token is optional but strongly recommended to avoid hitting the
60 req/h unauthenticated rate limit (with a token: 5000 req/h).
Results are cached in maven_check_cache.json so interrupted runs resume.
"""

import argparse
import csv
import json
import os
import sys
import time
import urllib.request
import urllib.error
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
BRUTEFORCE_CSV = SCRIPT_DIR / "Java_chunks_bruteforce.csv"
CACHE_FILE = SCRIPT_DIR / "maven_check_cache.json"

PROBE_TIMEOUT = 10  # seconds per HTTP request
RETRY_DELAY = 5     # seconds to wait after a rate-limit response
MAX_RETRIES = 3


def parse_args():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--token", default=os.environ.get("GITHUB_TOKEN"),
                   help="GitHub personal access token (or set GITHUB_TOKEN env var)")
    return p.parse_args()


def load_cache(path: Path) -> dict:
    if path.exists():
        with open(path) as f:
            return json.load(f)
    return {}


def save_cache(path: Path, cache: dict):
    with open(path, "w") as f:
        json.dump(cache, f, indent=2)


def extract_owner_repo(remote_url: str) -> tuple[str, str] | None:
    """Return (owner, repo) from a GitHub URL, or None if unparseable."""
    url = remote_url.rstrip("/").removesuffix(".git")
    # Expect: https://github.com/owner/repo
    parts = url.split("github.com/", 1)
    if len(parts) != 2:
        return None
    segments = parts[1].split("/")
    if len(segments) < 2:
        return None
    return segments[0], segments[1]


def has_pom_xml(owner: str, repo: str, token: str | None) -> bool | None:
    """
    Return True if pom.xml exists at the repo root, False if not, None on error.
    Uses the GitHub Contents API so we get a definitive 200/404 without downloading.
    """
    url = f"https://api.github.com/repos/{owner}/{repo}/contents/pom.xml"
    req = urllib.request.Request(url)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if token:
        req.add_header("Authorization", f"Bearer {token}")

    for attempt in range(1, MAX_RETRIES + 1):
        try:
            with urllib.request.urlopen(req, timeout=PROBE_TIMEOUT) as resp:
                return resp.status == 200
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return False
            if e.code in (403, 429):
                reset = e.headers.get("X-RateLimit-Reset")
                wait = max(RETRY_DELAY, int(reset) - int(time.time()) + 2) if reset else RETRY_DELAY * attempt
                print(f"    Rate limited (HTTP {e.code}), waiting {wait}s …", flush=True)
                time.sleep(wait)
            else:
                print(f"    HTTP {e.code} for {owner}/{repo} (attempt {attempt})", flush=True)
                if attempt < MAX_RETRIES:
                    time.sleep(RETRY_DELAY)
        except Exception as exc:
            print(f"    Error for {owner}/{repo}: {exc} (attempt {attempt})", flush=True)
            if attempt < MAX_RETRIES:
                time.sleep(RETRY_DELAY)

    return None  # could not determine


def collect_projects(csv_path: Path) -> dict[str, str]:
    """Return {project_id: remote_url} for all unique projects."""
    projects = {}
    with open(csv_path, newline="") as f:
        for row in csv.DictReader(f):
            pid = row["project_id"]
            if pid not in projects:
                projects[pid] = row["remote_url"]
    return projects


def check_all_projects(projects: dict[str, str], token: str | None, cache: dict) -> dict:
    """Probe each project not already in cache; update cache in-place."""
    total = len(projects)
    to_probe = [(pid, url) for pid, url in projects.items() if pid not in cache]
    print(f"{total} projects total, {len(cache)} already cached, {len(to_probe)} to probe.")

    for i, (pid, url) in enumerate(to_probe, 1):
        parsed = extract_owner_repo(url)
        if parsed is None:
            print(f"[{i}/{len(to_probe)}] SKIP (unparseable URL): {url}")
            cache[pid] = None
            continue

        owner, repo = parsed
        result = has_pom_xml(owner, repo, token)
        label = "MAVEN" if result is True else ("NON-MAVEN" if result is False else "UNKNOWN")
        print(f"[{i}/{len(to_probe)}] {owner}/{repo} → {label}", flush=True)
        cache[pid] = result

        save_cache(CACHE_FILE, cache)
        # Small polite delay to avoid hammering the API
        time.sleep(0.3)

    return cache


def write_output(bruteforce_csv: Path, cache: dict):
    """Rewrite bruteforce CSV with new `is_maven` column appended."""
    rows = []
    fieldnames = None
    with open(bruteforce_csv, newline="") as f:
        reader = csv.DictReader(f)
        fieldnames = reader.fieldnames + ["is_maven"]
        for row in reader:
            result = cache.get(row["project_id"])
            row["is_maven"] = (
                "True" if result is True
                else "False" if result is False
                else ""  # unknown / error
            )
            rows.append(row)

    with open(bruteforce_csv, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    maven = sum(1 for r in rows if r["is_maven"] == "True")
    non_maven = sum(1 for r in rows if r["is_maven"] == "False")
    unknown = sum(1 for r in rows if r["is_maven"] == "")
    print(f"\nDone. Rows written: {len(rows)}")
    print(f"  Maven rows    : {maven}")
    print(f"  Non-Maven rows: {non_maven}")
    print(f"  Unknown rows  : {unknown}")


def main():
    args = parse_args()
    if not args.token:
        print("WARNING: No GitHub token provided. Rate limit is 60 req/h. "
              "Set --token or GITHUB_TOKEN for 5000 req/h.", file=sys.stderr)

    cache = load_cache(CACHE_FILE)
    projects = collect_projects(BRUTEFORCE_CSV)

    # Check if column already exists and bail out gracefully
    with open(BRUTEFORCE_CSV, newline="") as f:
        existing_headers = csv.DictReader(f).fieldnames or []
    if "is_maven" in existing_headers:
        print("Column `is_maven` already exists in the CSV.")
        yn = input("Re-run probes and overwrite? [y/N]: ").strip().lower()
        if yn != "y":
            print("Aborted.")
            return

    check_all_projects(projects, args.token, cache)

    unknown_count = sum(1 for v in cache.values() if v is None)
    if unknown_count:
        print(f"\nWARNING: {unknown_count} projects could not be determined (marked as empty).")

    write_output(BRUTEFORCE_CSV, cache)


if __name__ == "__main__":
    main()
