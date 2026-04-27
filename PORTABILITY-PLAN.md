# Plugin Portability — Linux / macOS / Windows

## Context

The IntelliJ plugin (`plugin/`) currently builds and runs cleanly only on the
developer's Linux box. The goal is one `cicd-merge-oracle-1.0-SNAPSHOT.zip`
that a colleague can drag into IntelliJ on any of {another Linux box, macOS,
Windows} and have **interactive merge resolution + variant scoring** working
out of the box, with documented manual steps for ML-AR.

Close inspection found fewer hard blockers than the first audit suggested:

| Concern | Reality |
|---|---|
| `fuse-overlayfs` and `/proc` reads | **Already gated** by `OverlayMount.isAvailable()` (`OverlayMount.java:78`). On non-Linux the call returns false and `PluginOrchestrator.java:95` skips the overlay path. No correct alternative on macOS/Windows — plain copy is the only option. |
| `/proc/meminfo` | Only consulted by `AppConfig.readMemAvailable()` (`AppConfig.java:296`). Cross-platform JMX equivalent exists — see code change 2 below. |
| `JAVA_HOMES` hardcoded to `/usr/lib/jvm/...` | `JavaVersionResolver.resolveJavaHome()` returns `Optional.empty()` if the path doesn't exist, but that means a Mac with Java 8/11/21 installed in `/Library/Java/JavaVirtualMachines/` cannot be used at all. Needs a real cross-platform resolver. |
| `mvnd` discovery | `MavenCommandResolver.findMvnd()` tries PATH first (`MavenCommandResolver.java:42`); the `/opt` scan is Linux-only. Should also probe Homebrew (`/opt/homebrew/bin`, `/usr/local/bin`) and `%ProgramFiles%\maven-mvnd\bin`. |
| Maven wrapper / `mvn.cmd` | `MavenCommandResolver` already branches on `os.name`. ✓ |
| `pgrep` / `pkill` for daemon cleanup | Available on Linux **and macOS** (BSD pgrep ships with macOS). Missing on Windows — needs `taskkill` branch. |
| `/tmp/variant-benchmark.json` | Hardcoded (`MergeResolutionPanel.java:898`). Trivial fix to use `java.io.tmpdir`. |
| ML-AR `.pt` checkpoints | Not bundled; live under `~/data/bruteforcemerge/rq1/checkpoints/`. The heuristic generator (`learnt_historical_pattern_distribution.csv`) **is** bundled — heuristic-only path works without external data on every OS. |

The principle the user wants: **don't fall back to a degraded result if the
correct cross-platform answer is cheaply available.** The 4-thread fallback,
the empty-Optional JDK fallback, and the bare-name `mvnd` fallback all violate
that — they're being replaced below with real cross-platform implementations.

---

## Code changes (apply once, benefit all OSes)

### 1. Replace `JAVA_HOMES` with a cross-platform resolver

**File:** `cicd-oracle/src/main/java/ch/unibe/cs/mergeci/config/AppConfig.java:113-119`

Replace the static `Map.of(...)` with a resolver that, in order:

1. Reads `~/.cicd-oracle/java-homes.properties` if present (lines like `21=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`). Explicit user override wins.
2. Reads env var `CICD_JAVA_HOMES` (semicolon-separated `version=path` pairs).
3. **Auto-probes per OS** (the actual best-effort step, not a fallback):
   - Linux: scan `/usr/lib/jvm/*` and `/opt/jdk*`, plus `JAVA_HOME` env.
   - macOS: scan `/Library/Java/JavaVirtualMachines/*/Contents/Home`, `/opt/homebrew/opt/openjdk@*`, `/usr/local/opt/openjdk@*`, plus `/usr/libexec/java_home -V` parsing.
   - Windows: scan `%ProgramFiles%\Java\*`, `%ProgramFiles%\Eclipse Adoptium\*`, `%ProgramFiles%\Microsoft\jdk-*`, plus `JAVA_HOME`.
   - For each candidate, read its `release` file for `JAVA_VERSION=` and map version → path.
4. Only if zero JDKs are found anywhere — return an empty map. `JavaVersionResolver` already handles "version not installed" via `Optional.empty()`, so this is the single legitimate fallback.

`JavaVersionResolver.AVAILABLE_VERSIONS` (line 19) is computed from the map at
class-load and would need to become a method call so it reflects the
resolved set.

### 2. Replace the hardcoded 4-thread fallback with a real per-OS RAM probe

**File:** `cicd-oracle/src/main/java/ch/unibe/cs/mergeci/config/AppConfig.java:295-306`

`readMemAvailable()` currently only knows `/proc/meminfo`. The per-OS correct
answer is available via the standard JDK without shelling out:

```java
static long readMemAvailable() {
    // Linux: prefer /proc/meminfo's MemAvailable — it accounts for reclaimable cache.
    try (var br = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
        String line;
        while ((line = br.readLine()) != null) {
            if (line.startsWith("MemAvailable:")) {
                return Long.parseLong(line.split("\\s+")[1]) * 1024;
            }
        }
    } catch (IOException ignored) { /* not Linux, or /proc/meminfo unreadable */ }

    // macOS / Windows / fallback: use the JMX OperatingSystemMXBean.
    var osBean = (com.sun.management.OperatingSystemMXBean)
            java.lang.management.ManagementFactory.getOperatingSystemMXBean();
    return osBean.getFreeMemorySize();
}
```

Then `computeMaxThreads()` no longer needs its `catch → THREAD_FALLBACK` arm
(remove `THREAD_FALLBACK` constant entirely if it's only used there). The
existing `Runtime.getRuntime().availableProcessors()` already gives correct
core counts on every OS, so cores were never the problem — only memory was.

`com.sun.management.OperatingSystemMXBean` ships with HotSpot/OpenJDK, which
is what every supported IntelliJ JBR uses. Not a portability risk in practice.

### 3. Real cross-platform `mvnd` discovery

**File:** `cicd-oracle/src/main/java/ch/unibe/cs/mergeci/runner/maven/MavenCommandResolver.java:40-55`

Augment `findMvnd()` to scan, in order:

1. Bare `mvnd` on PATH (existing behavior).
2. Linux: `/opt/maven-mvnd-*/bin/mvnd` (existing).
3. macOS: `/opt/homebrew/bin/mvnd`, `/usr/local/bin/mvnd`, `/opt/homebrew/Cellar/mvnd/*/bin/mvnd`.
4. Windows: `%ProgramFiles%\maven-mvnd-*\bin\mvnd.cmd`, `%LOCALAPPDATA%\Programs\maven-mvnd-*\bin\mvnd.cmd`.

If none match, fall through to `mvn`/`mvn.cmd` (the resolver already does this
correctly). Don't return the bare-name `"mvnd"` ghost — if it's not on PATH,
falling straight to `mvn` is more honest than handing the build a name that
will fail at exec time.

### 4. Cross-platform daemon cleanup

**File:** `cicd-oracle/src/main/java/ch/unibe/cs/mergeci/runner/maven/MavenCommandResolver.java:218-247`

`waitOrKill` and `countProcessesMatching` use `pgrep`/`pkill`. These work on
Linux and macOS unchanged. For Windows, add a branch:

```java
if (System.getProperty("os.name").toLowerCase().contains("windows")) {
    // Use tasklist/taskkill — both ship with Windows.
    // Match on image name "mvnd.exe" (cmdline-substring match isn't supported by tasklist).
    new ProcessBuilder("taskkill", "/F", "/IM", "mvnd.exe").start().waitFor(5, TimeUnit.SECONDS);
    return;
}
```

The existing comment about JVM cmdline truncation is Linux-specific — on
Windows the image-name match is sufficient because mvnd ships as `mvnd.exe`
distinct from the JVM `java.exe`. Keep pgrep/pkill on Linux and macOS for the
exact reason the comment states.

### 5. Use `java.io.tmpdir` instead of `/tmp`

**File:** `plugin/src/main/java/org/example/cicdmergeoracle/cicdMergeTool/ui/MergeResolutionPanel.java:898`

```java
Path out = Path.of(System.getProperty("java.io.tmpdir"), "variant-benchmark.json");
```

One-liner. No risk.

### 6. Bundle `autoregressive_fold_assignment.json` in the plugin JAR

**Files:**
- New: `plugin/src/main/resources/pattern-heuristics/autoregressive_fold_assignment.json` (copied from `~/data/bruteforcemerge/rq1/checkpoints/`)
- Modify: `cicd-oracle/src/main/java/ch/unibe/cs/mergeci/runner/MLARGenerator.java` to fall back to the classpath copy when `AppConfig.RQ1_FOLD_ASSIGNMENT_FILE` doesn't exist.

The `.pt` checkpoints (~hundreds of MB) stay out of the zip — they're the only
thing a fresh user must copy manually to enable ML-AR.

### 7. User-facing setup doc

New file: `plugin/INSTALL.md`. One page mirroring the table below.

---

## Per-OS user setup (after the code changes ship)

The setup is nearly identical across the three OSes:

| Step | Linux (other PC) | macOS | Windows |
|---|---|---|---|
| Install plugin zip | Settings → Plugins → Install from Disk | same | same |
| Install Maven | `apt install maven` | `brew install maven` | scoop/choco install, add to PATH |
| Install mvnd (optional, faster) | `apt install maven-mvnd` or unpack into `/opt` | `brew install mvndaemon/homebrew-mvnd/mvnd` | unpack mvnd zip, add `bin/` to PATH |
| Register JDKs (auto-probe handles most cases) | only edit `~/.cicd-oracle/java-homes.properties` if a JDK is in a non-standard path | same | same, at `%USERPROFILE%\.cicd-oracle\java-homes.properties` |
| OverlayFS speedup | `apt install fuse-overlayfs` | not available — plain copy used | not available — plain copy used |
| ML-AR (optional) | drop `.pt` files into `~/data/bruteforcemerge/rq1/checkpoints/`, ensure `python3` is on PATH | same | same path under `%USERPROFILE%`, `py` instead of `python3` |

The plugin's interactive merge UI, heuristic variant generation, conflict
chunk extraction, and per-variant build scoring **work identically on all
three OSes** once Maven is on PATH. Differences are performance- or
feature-gated, never correctness-gated.

---

## What might break (short)

- **JDK auto-probe picks the wrong JDK** when multiple vendors are installed (Temurin vs Oracle vs Microsoft) and their `release` files report the same `JAVA_VERSION`. Mitigation: `~/.cicd-oracle/java-homes.properties` always wins. Symptom: a build uses an unexpected JDK; usually still works.
- **`com.sun.management.OperatingSystemMXBean.getFreeMemorySize()`** reports OS-free memory rather than Linux's `MemAvailable` (which counts reclaimable cache). On a system with cold cache this can under-estimate available RAM, picking fewer threads than optimal. Throughput regression, not a correctness bug. Linux still uses `/proc/meminfo` first.
- **macOS Gatekeeper** may quarantine `fuse-overlayfs` if a user installs it manually — the `isAvailable()` probe will still return false (it just exec-fails), so the worst case is the existing plain-copy path. No new failure mode.
- **Windows daemon cleanup** matches `mvnd.exe` by image name only. If the user runs multiple unrelated `mvnd` instances, all of them get killed when the plugin tears down. Acceptable since the plugin assumes it owns the build environment.
- **Bundled `autoregressive_fold_assignment.json` going stale** vs the live one in `~/data/...` — the classpath copy is only used when the live file is absent, so an out-of-date training run is the only risk. Easy to refresh on plugin rebuild.
- Nothing in this plan changes Linux behavior on the dev's machine (every code path is gated on `/proc/meminfo` existing, `JAVA_HOMES` props file existing, etc.).

---

## Critical files to modify

| File | Change |
|---|---|
| `cicd-oracle/src/main/java/ch/unibe/cs/mergeci/config/AppConfig.java:113-119` | Replace static `JAVA_HOMES` with cross-platform resolver. |
| `cicd-oracle/src/main/java/ch/unibe/cs/mergeci/config/AppConfig.java:295-306` | Add JMX-based `getFreeMemorySize()` after the `/proc/meminfo` attempt; drop the 4-thread fallback. |
| `cicd-oracle/src/main/java/ch/unibe/cs/mergeci/util/JavaVersionResolver.java:19` | Make `AVAILABLE_VERSIONS` a method (so it reflects the resolved JDK set). |
| `cicd-oracle/src/main/java/ch/unibe/cs/mergeci/runner/maven/MavenCommandResolver.java:40-55` | Add macOS and Windows search paths to `findMvnd()`. |
| `cicd-oracle/src/main/java/ch/unibe/cs/mergeci/runner/maven/MavenCommandResolver.java:218-247` | Add Windows `taskkill` branch in the daemon-cleanup helpers. |
| `plugin/src/main/java/org/example/cicdmergeoracle/cicdMergeTool/ui/MergeResolutionPanel.java:898` | Use `java.io.tmpdir`. |
| `plugin/src/main/resources/pattern-heuristics/autoregressive_fold_assignment.json` | New: bundle the fold-assignment manifest. |
| `cicd-oracle/src/main/java/ch/unibe/cs/mergeci/runner/MLARGenerator.java` | Classpath fallback for the manifest. |
| `plugin/INSTALL.md` | New: per-OS setup doc. |

## Files that already handle cross-platform correctly (no change)

- `OverlayMount.java` — `isAvailable()` already gates everything; plain copy is the only correct alternative on Mac/Windows.
- `MavenCommandResolver.resolveMavenCommand()` — already branches on `os.name` for `mvn.cmd` / `mvnw.cmd`.
- `AppConfig.PYTHON_EXECUTABLE` — already walks up looking for `.venv`, falls back to system `python3`.
- `AppConfig.BASE_DIR` — derived from `user.home`.

## Verification

1. **Build:** `cd plugin && ./gradlew buildPlugin` — confirm the zip in `build/distributions/` builds without errors.
2. **Linux smoke test:** Open a merge conflict repo, generate heuristic variants, build at least one, confirm `Export benchmark` lands in `/tmp/variant-benchmark.json` (still works because `java.io.tmpdir` is `/tmp` on Linux).
3. **JDK resolver:** Rename `/usr/lib/jvm/jdk-21.0.2`, confirm the auto-probe picks up another Java 21 installation; then drop it into `~/.cicd-oracle/java-homes.properties` and confirm the override path is preferred. Restore the rename.
4. **Memory probe:** On Linux, `unshare -m` with `/proc` masked, confirm `computeMaxThreads()` falls through to JMX without throwing.
5. **macOS/Windows:** Install the rebuilt zip on a colleague's machine following the per-OS table. Open a small merge conflict project, generate variants, build at least one, confirm the conflict resolution UI shows pattern columns and the benchmark export lands in the user's temp dir.
6. **Heuristic-only fallback:** With ML-AR checkpoints absent, confirm `MLARGenerator` logs a warning and the heuristic generator continues to produce variants on every OS.

## Out of scope

- A full IntelliJ `Configurable` settings panel for JDK/Maven paths. The properties file + auto-probe is the minimum viable mechanism; a UI can come later.
- Bundling JDKs or Maven inside the plugin zip — too large.
- Re-implementing copy-on-write on macOS/Windows. Plain copy already works; speedup is a separate project.
