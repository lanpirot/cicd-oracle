#!/usr/bin/env python3
"""
view_noteworthy.py — Generate a self-contained HTML viewer from conflict file triplets.

Usage:
    python scripts/view_noteworthy.py \
        --triplets-dir /home/lanpirot/data/bruteforcemerge/conflict_files \
        --output       /home/lanpirot/data/bruteforcemerge/viewer.html

Then open viewer.html in a browser.
"""

import argparse
import html
import json
import os
import sys


# ── colour scheme ─────────────────────────────────────────────────────────────

COLORS = {
    "OURS":   "#cce5ff",
    "THEIRS": "#d4edda",
    "BASE":   "#fff3cd",
    "EMPTY":  "#e2e3e5",
    "marker": "#ffcccc",
    "human":  "#fffde7",
}

# Diagonal gradient CSS for compound patterns (e.g. OURSTHEIRS)
_ATOMICS = ["OURS", "THEIRS", "BASE", "EMPTY"]


def pattern_style(pattern_name):
    """Return a CSS background-color or background (gradient) for a pattern."""
    parts = []
    remaining = pattern_name
    while remaining:
        matched = False
        for a in sorted(_ATOMICS, key=len, reverse=True):
            if remaining.startswith(a):
                parts.append(a)
                remaining = remaining[len(a):]
                matched = True
                break
        if not matched:
            break

    if not parts:
        return f"background-color: {COLORS['OURS']};"
    if len(parts) == 1:
        color = COLORS.get(parts[0], COLORS["OURS"])
        return f"background-color: {color};"
    # Compound: diagonal stripe
    colors = [COLORS.get(p, COLORS["OURS"]) for p in parts]
    stop = 100 // len(colors)
    stops = []
    for i, c in enumerate(colors):
        stops.append(f"{c} {i * stop}%")
        stops.append(f"{c} {(i + 1) * stop}%")
    gradient = ", ".join(stops)
    return f"background: repeating-linear-gradient(45deg, {gradient});"


# ── data loading ──────────────────────────────────────────────────────────────

def load_triplets(triplets_dir):
    """Load all case directories from triplets_dir, return sorted list of case dicts."""
    cases = []
    if not os.path.isdir(triplets_dir):
        print(f"[ERROR] triplets directory not found: {triplets_dir}", file=sys.stderr)
        return cases

    for entry in sorted(os.listdir(triplets_dir)):
        case_dir = os.path.join(triplets_dir, entry)
        if not os.path.isdir(case_dir):
            continue
        meta_path = os.path.join(case_dir, "meta.json")
        if not os.path.exists(meta_path):
            continue
        try:
            with open(meta_path, encoding="utf-8") as f:
                meta = json.load(f)
        except Exception as e:
            print(f"[WARN] skipping {entry}: {e}", file=sys.stderr)
            continue

        file_entries = []
        for file_path in meta.get("files", []):
            sanitized = file_path.replace("/", "__").replace("\\", "__").replace(":", "_")
            file_dir = os.path.join(case_dir, sanitized)
            if not os.path.isdir(file_dir):
                continue

            ext = os.path.splitext(file_path)[1] or ""
            human_path    = os.path.join(file_dir, "human"     + ext)
            tentative_path = os.path.join(file_dir, "tentative" + ext)
            variant_path  = os.path.join(file_dir, "variant"   + ext)
            chunks_path   = os.path.join(file_dir, "chunks.json")

            if not all(os.path.exists(p) for p in
                       [human_path, tentative_path, variant_path, chunks_path]):
                continue

            human    = read_file(human_path)
            tentative = read_file(tentative_path)
            variant  = read_file(variant_path)
            with open(chunks_path, encoding="utf-8") as f:
                chunks = json.load(f)

            file_entries.append({
                "path":      file_path,
                "human":     human,
                "tentative": tentative,
                "variant":   variant,
                "chunks":    chunks,
            })

        if file_entries:
            cases.append({"meta": meta, "files": file_entries})

    return cases


def read_file(path):
    try:
        with open(path, encoding="utf-8") as f:
            return f.read()
    except Exception:
        return ""


# ── line rendering ────────────────────────────────────────────────────────────

def render_lines(content, chunks, role):
    """
    Return list of (line_text, css_class, style_override) tuples.
    role: "human" | "tentative" | "variant"
    """
    lines = content.split("\n")
    # Remove trailing empty element produced by a final newline
    if lines and lines[-1] == "":
        lines = lines[:-1]

    # Build a map: 1-indexed line number → (css_class, style)
    line_styles = {}

    for chunk in chunks:
        pattern = chunk.get("pattern", "OURS")

        if role == "tentative":
            t = chunk.get("tentative", {})
            # Marker lines
            for key in ("markerOurs", "markerBase", "markerSep", "markerEnd"):
                ln = t.get(key)
                if ln:
                    line_styles[ln] = ("marker", "background-color: " + COLORS["marker"] + ";")
            # OURS section
            for ln in range(t.get("oursStart", 0), t.get("oursEnd", -1) + 1):
                if ln > 0:
                    line_styles[ln] = ("ours", "background-color: " + COLORS["OURS"] + ";")
            # BASE section
            for ln in range(t.get("baseStart", 0), t.get("baseEnd", -1) + 1):
                if ln > 0:
                    line_styles[ln] = ("base", "background-color: " + COLORS["BASE"] + ";")
            # THEIRS section
            for ln in range(t.get("theirsStart", 0), t.get("theirsEnd", -1) + 1):
                if ln > 0:
                    line_styles[ln] = ("theirs", "background-color: " + COLORS["THEIRS"] + ";")

        elif role == "human":
            h = chunk.get("human", {})
            for ln in range(h.get("start", 0), h.get("end", -1) + 1):
                if ln > 0:
                    line_styles[ln] = ("human-conflict",
                                       "background-color: " + COLORS["human"] + ";")

        elif role == "variant":
            v = chunk.get("variant", {})
            style = pattern_style(pattern)
            for ln in range(v.get("start", 0), v.get("end", -1) + 1):
                if ln > 0:
                    line_styles[ln] = ("variant-" + pattern.lower(), style)

    result = []
    for i, line in enumerate(lines):
        ln = i + 1
        css_class, style = line_styles.get(ln, ("", ""))
        result.append((line, css_class, style))
    return result


def render_panel(content, chunks, role, panel_id):
    """Return HTML string for one code panel."""
    rendered = render_lines(content, chunks, role)
    lines_html = []
    for i, (text, css_class, style) in enumerate(rendered):
        ln = i + 1
        cls = f' class="{css_class}"' if css_class else ""
        sty = f' style="{style}"' if style else ""
        escaped = html.escape(text)
        lines_html.append(
            f'<div id="{panel_id}-L{ln}" data-line="{ln}"{cls}{sty}>'
            f'<span class="ln">{ln}</span>'
            f'<span class="lc">{escaped}</span>'
            f'</div>'
        )
    return "\n".join(lines_html)


# ── HTML generation ───────────────────────────────────────────────────────────

CSS = """
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: monospace; font-size: 12px; background: #f8f9fa; }

#top-bar {
    position: fixed; top: 0; left: 0; right: 0; z-index: 100;
    background: #343a40; color: #fff; padding: 6px 10px;
    display: flex; flex-direction: column; gap: 4px;
}
.nav-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.nav-row button {
    background: #495057; color: #fff; border: none; padding: 3px 10px;
    cursor: pointer; border-radius: 3px; font-size: 12px;
}
.nav-row button:hover { background: #6c757d; }
.nav-label { font-size: 12px; min-width: 200px; }
.file-tab {
    background: #495057; color: #ccc; border: none; padding: 3px 8px;
    cursor: pointer; border-radius: 3px; font-size: 11px;
}
.file-tab.active { background: #0d6efd; color: #fff; }
a.gh-link {
    color: #6ea8fe; font-size: 11px; text-decoration: none;
    background: #495057; padding: 2px 6px; border-radius: 3px;
}
a.gh-link:hover { background: #6c757d; }

#panels {
    display: flex; position: fixed; top: 72px; left: 0; right: 0; bottom: 0;
}
.panel {
    flex: 1; display: flex; flex-direction: column;
    border-right: 1px solid #dee2e6; overflow: hidden;
}
.panel:last-child { border-right: none; }
.panel-header {
    background: #e9ecef; padding: 4px 8px; font-weight: bold;
    font-size: 11px; border-bottom: 1px solid #dee2e6; white-space: nowrap;
    overflow: hidden; text-overflow: ellipsis;
}
.panel-content {
    flex: 1; overflow-y: scroll; overflow-x: auto;
    background: #fff;
}
.panel-content pre {
    padding: 0; margin: 0; line-height: 1.4;
    white-space: pre;
}
.panel-content div { display: flex; }
.ln {
    display: inline-block; min-width: 40px; padding: 0 6px;
    color: #6c757d; text-align: right; user-select: none;
    border-right: 1px solid #dee2e6; background: #f8f9fa;
    flex-shrink: 0;
}
.lc { padding: 0 6px; white-space: pre; }
.marker { color: #c0392b; font-weight: bold; }
"""

JS = r"""
const CASES = __CASES__;

let caseIdx = 0;
let fileIdx = 0;
let chunkIdx = 0;

function numChunks() {
    if (!CASES.length) return 0;
    const f = CASES[caseIdx].files[fileIdx];
    return f ? f.chunks.length : 0;
}

function render() {
    if (!CASES.length) { document.getElementById('case-label').textContent = 'No cases'; return; }
    const c = CASES[caseIdx];
    const f = c.files[fileIdx];
    const m = c.meta;

    const dateStr = m.commitDate ? m.commitDate.slice(0, 10) : '';
    const flags = [
        m.hasTestConflict  ? 'testConflict' : '',
        m.baselineBroken   ? 'baselineBroken' : '',
    ].filter(Boolean).join(', ');
    document.getElementById('case-label').textContent =
        `Case ${caseIdx+1}/${CASES.length}: ${m.projectName}  ` +
        `${dateStr}  |  ${m.numConflictFiles}f/${m.numConflictChunks}c  |  ` +
        `human=${m.humanPassedTests}t  variant${m.variantIndex}=${m.variantPassedTests}t  ` +
        `compiles: ${m.variantCompiles}` +
        (flags ? `  [${flags}]` : '');

    // GitHub commit link
    const linkEl = document.getElementById('github-link');
    if (m.repoUrl && m.repoUrl.includes('github.com')) {
        const base = m.repoUrl.replace(/\.git$/, '');
        linkEl.href = `${base}/commit/${m.mergeCommit}`;
        linkEl.textContent = `${m.mergeCommit.slice(0, 8)} ↗`;
        linkEl.style.display = 'inline';
    } else {
        linkEl.style.display = 'none';
    }

    document.getElementById('chunk-label').textContent =
        `Conflict ${chunkIdx+1}/${numChunks()}`;

    // File tabs
    const tabBar = document.getElementById('file-tabs');
    tabBar.innerHTML = '';
    c.files.forEach((ff, i) => {
        const btn = document.createElement('button');
        btn.className = 'file-tab' + (i === fileIdx ? ' active' : '');
        const short = ff.path.split('/').pop();
        btn.textContent = short;
        btn.title = ff.path;
        btn.onclick = () => { fileIdx = i; chunkIdx = 0; render(); };
        tabBar.appendChild(btn);
    });

    // Panel headers
    const shortPath = f.path.split('/').pop();
    document.getElementById('hdr-human').textContent =
        `Human  —  ${shortPath}  (${m.mergeCommit.slice(0,8)})  parent1: ${m.parent1 ? m.parent1.slice(0,8) : '?'}  parent2: ${m.parent2 ? m.parent2.slice(0,8) : '?'}`;
    document.getElementById('hdr-tentative').textContent =
        `Tentative  —  ${shortPath}`;
    document.getElementById('hdr-variant').textContent =
        `Variant ${m.variantIndex}  —  ${shortPath}  ` +
        `(patterns: ${[...new Set(f.chunks.map(ch => ch.pattern))].join(', ')})`;

    // Render panels
    document.getElementById('code-human').innerHTML     = f.humanHtml;
    document.getElementById('code-tentative').innerHTML = f.tentativeHtml;
    document.getElementById('code-variant').innerHTML   = f.variantHtml;

    jumpToChunk();
}

function jumpToChunk() {
    if (!CASES.length) return;
    const f = CASES[caseIdx].files[fileIdx];
    if (!f || !f.chunks.length) return;
    const chunk = f.chunks[chunkIdx];

    scrollPanelToLine('panel-human',     'human',     chunk.human     && chunk.human.start);
    scrollPanelToLine('panel-tentative', 'tentative', chunk.tentative && chunk.tentative.markerOurs);
    scrollPanelToLine('panel-variant',   'variant',   chunk.variant   && chunk.variant.start);
}

function scrollPanelToLine(panelId, prefix, lineNum) {
    if (!lineNum) return;
    const panel = document.getElementById(panelId).querySelector('.panel-content');
    const el = document.getElementById(prefix + '-L' + lineNum);
    if (!el || !panel) return;
    const offset = el.offsetTop - panel.clientHeight / 3;
    panel.scrollTop = Math.max(0, offset);
}

document.addEventListener('keydown', e => {
    if (e.key === 'ArrowRight' || e.key === 'n') nextCase();
    else if (e.key === 'ArrowLeft'  || e.key === 'p') prevCase();
    else if (e.key === 'ArrowDown'  || e.key === 'j') nextChunk();
    else if (e.key === 'ArrowUp'    || e.key === 'k') prevChunk();
});

function prevCase()  { if (caseIdx > 0)                  { caseIdx--; fileIdx=0; chunkIdx=0; render(); } }
function nextCase()  { if (caseIdx < CASES.length - 1)   { caseIdx++; fileIdx=0; chunkIdx=0; render(); } }
function prevChunk() { if (chunkIdx > 0)                  { chunkIdx--; jumpToChunk(); updateChunkLabel(); } }
function nextChunk() { if (chunkIdx < numChunks()-1)      { chunkIdx++; jumpToChunk(); updateChunkLabel(); } }
function updateChunkLabel() {
    document.getElementById('chunk-label').textContent =
        `Conflict ${chunkIdx+1}/${numChunks()}`;
}

window.onload = () => render();
"""

HTML_TEMPLATE = """\
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>Noteworthy Merge Viewer</title>
<style>
{css}
</style>
</head>
<body>

<div id="top-bar">
  <div class="nav-row">
    <button onclick="prevCase()">&#9664; Prev Case</button>
    <span class="nav-label" id="case-label">Loading…</span>
    <button onclick="nextCase()">Next Case &#9654;</button>
    &nbsp;
    <a id="github-link" class="gh-link" href="#" target="_blank" style="display:none;"></a>
  </div>
  <div class="nav-row">
    <button onclick="prevChunk()">&#9650; Prev Conflict</button>
    <span id="chunk-label" style="min-width:120px;text-align:center;"></span>
    <button onclick="nextChunk()">Next Conflict &#9660;</button>
    &nbsp;
    <span id="file-tabs"></span>
    &nbsp;
    <span style="color:#adb5bd;font-size:11px;">
      keys: ←/→ case &nbsp; ↑/↓ conflict &nbsp; p/n/j/k also work
    </span>
  </div>
</div>

<div id="panels">
  <div class="panel" id="panel-human">
    <div class="panel-header" id="hdr-human">Human</div>
    <div class="panel-content"><pre id="code-human"></pre></div>
  </div>
  <div class="panel" id="panel-tentative">
    <div class="panel-header" id="hdr-tentative">Tentative</div>
    <div class="panel-content"><pre id="code-tentative"></pre></div>
  </div>
  <div class="panel" id="panel-variant">
    <div class="panel-header" id="hdr-variant">Variant</div>
    <div class="panel-content"><pre id="code-variant"></pre></div>
  </div>
</div>

<script>
{js}
</script>
</body>
</html>
"""


# ── main ──────────────────────────────────────────────────────────────────────

def build_case_data(cases):
    """Pre-render HTML for each panel and embed as JS data."""
    js_cases = []
    for case in cases:
        js_files = []
        for f in case["files"]:
            chunks = f["chunks"]
            js_files.append({
                "path":        f["path"],
                "chunks":      chunks,
                "humanHtml":   render_panel(f["human"],    chunks, "human",     "human"),
                "tentativeHtml": render_panel(f["tentative"], chunks, "tentative", "tentative"),
                "variantHtml": render_panel(f["variant"],  chunks, "variant",   "variant"),
            })
        js_cases.append({"meta": case["meta"], "files": js_files})
    return js_cases


def generate_html(triplets_dir, output_path):
    print(f"Loading triplets from {triplets_dir} …")
    cases = load_triplets(triplets_dir)
    if not cases:
        print("[WARN] No cases found. The viewer will be empty.", file=sys.stderr)

    print(f"  {len(cases)} case(s) loaded.")
    print("Rendering HTML …")

    js_cases = build_case_data(cases)
    cases_json = json.dumps(js_cases, ensure_ascii=False, separators=(",", ":"))

    js = JS.replace("__CASES__", cases_json)
    html_content = HTML_TEMPLATE.format(css=CSS, js=js)

    with open(output_path, "w", encoding="utf-8") as f:
        f.write(html_content)

    size_kb = os.path.getsize(output_path) / 1024
    print(f"Written {output_path}  ({size_kb:.0f} kB)")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--triplets-dir",
                        default="/home/lanpirot/data/bruteforcemerge/conflict_files",
                        help="Directory containing triplet case subdirectories")
    parser.add_argument("--output",
                        default="/home/lanpirot/data/bruteforcemerge/viewer.html",
                        help="Output HTML file path")
    args = parser.parse_args()

    generate_html(args.triplets_dir, args.output)


if __name__ == "__main__":
    main()
