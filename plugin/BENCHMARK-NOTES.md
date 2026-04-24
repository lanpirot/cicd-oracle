# Plugin Build Optimization Benchmark Notes

## OverlayFS vs mvnd Daemon Race

### What happens

The plugin uses fuse-overlayfs to create copy-on-write variant directories: a shared "base" contains all non-conflict files, and each variant gets a lightweight overlay mount where only changed files are written. This avoids copying the entire project tree per variant.

When combined with **mvnd** (Maven Daemon), a race condition occurs:

1. The plugin mounts a fuse-overlayfs filesystem for a variant (e.g., `/dev/shm/cicd-oracle-plugin/projects/mockRepo_21/`)
2. mvnd's client connects to a daemon JVM and tells it to build in that mountpoint
3. The daemon starts reading files from the mountpoint
4. If the daemon's socket connection is slow or the daemon is busy with another build, the client times out with `StaleAddressException: Could not receive a message from the daemon`
5. The client dumps a dispatch-format log (`Dispatch message: ProjectLogMessage{...}`) instead of Maven's normal `[INFO]` output
6. The build may run to completion inside the daemon, but the client never receives the Reactor Summary — so `CompilationResult` falls back to `totalModules=1`

This affected ~8% of variants (22/270 in our test), causing them to be reported as 0/1 or 1/1 when the actual reactor had 3 modules.

### Why it only happens with overlayFS

Plain file copies (`cp -r`) create normal directories on the local filesystem. mvnd daemons access them without issue. fuse-overlayfs introduces a FUSE userspace filesystem layer that adds latency to every syscall — when many variants mount/unmount concurrently, the FUSE daemon itself becomes a bottleneck, causing mvnd's keepalive timeout (100ms default) to expire.

### How we fixed it (current state)

- **Disabled overlayFS in the plugin** — use plain file copy instead. The copy overhead on small-to-medium projects is negligible compared to build time.
- **Fixed CompilationResult log parsing** — unwrap mvnd's dispatch format (`ProjectLogMessage{message='[INFO] ...'}`) and strip timestamp prefixes, so module results can be parsed even from stale daemon logs. Also added fallback inference from `Building <name> [N/M]` lines when the Reactor Summary is entirely missing.

### Possible future fixes to re-enable overlayFS

1. **Serialize daemon access**: Ensure only one mvnd build runs per daemon at a time (defeats the purpose of parallelism unless you run multiple daemon instances)
2. **Increase mvnd keepalive**: Set `-Dmvnd.keepAlive=5s` (default 100ms) to tolerate FUSE latency — but this slows down daemon reuse detection
3. **Use plain `mvn` with overlayFS**: Skip mvnd entirely when overlayFS is active — the overlay savings may offset the loss of the warm JVM, especially on large projects
4. **Pre-mount all overlays before building**: Mount all N overlays upfront, then start builds — avoids concurrent mount/build races

## Maven Build Cache Extension vs Donor Target Warming

These are two different caching strategies that serve similar but distinct purposes:

### Donor target/ warming (what we use)

The best-performing variant so far ("donor") keeps its `target/` directories alive. Before building the next variant, we copy the donor's `target/` dirs into the new variant's project tree. This gives Maven's **incremental compiler** pre-compiled `.class` files:

- Maven sees `.class` files with timestamp T0
- Conflict-resolved `.java` files are written with timestamp T1 > T0
- The incremental compiler compares timestamps: only recompiles `.java` files newer than their `.class` files
- Modules whose source didn't change between donor and current variant skip compilation entirely
- **Operates at the file level** — no metadata, no hashing, just timestamp comparison

This is simple, fast, and works with any Maven version. The overhead is one `cp -r` of `target/` dirs per variant.

### Maven Build Cache Extension (what we removed from the plugin)

The [Maven Build Cache Extension](https://maven.apache.org/extensions/maven-build-cache-extension/) (1.2.0) is an official Maven extension that operates at the **module level**:

- Before building a module, it computes a SHA-256 hash of all input files (`src/**/*.java`, config files, etc.)
- It checks a local cache (`.cache/` directory) for a previous build with the same hash
- **Cache hit**: skips the entire module lifecycle (compile + test), restores the cached output artifacts (classes, jars, test results) directly
- **Cache miss**: builds normally, then stores the output in the cache for future use
- Can also use a remote cache (shared across CI nodes), though we only use local

**Key difference from donor warming:**
- Donor warming provides *warm* `.class` files that Maven's compiler can incrementally update
- Cache extension provides *exact* cached outputs that skip the entire module — no compiler runs at all
- Cache extension needs hash computation per module (~10-30ms each), extension startup overhead, and can produce `RestoredArtifact` objects that break reactor resolution (the bug we encountered)

### Why the cache extension hurts on small projects

1. **Hash overhead**: Computing SHA-256 over all source files takes longer than just compiling them on a 3-module project
2. **Extension startup**: Loading the extension, reading config, initializing the cache store adds ~200ms per Maven invocation
3. **Daemon instability**: The extension adds complexity to the daemon's class loading, increasing `StaleAddressException` frequency (13 crashes vs 3 without it)
4. **Reactor artifact bug**: When the extension restores a module from cache, it replaces the project's artifact with a `RestoredArtifact`. For compile-only cache entries (no jar), downstream modules can't resolve the dependency. Our maven-hook fix (proactive sibling scan on `MojoStarted`) mitigates this but doesn't eliminate it

### When the cache extension would help

- **Large projects** (50+ modules, 5+ minute builds): hash computation is amortized across many modules, and skipping entire modules saves minutes
- **High variant similarity**: When most variants share the same resolution for most modules, cache hit rates are high
- **Remote cache**: In CI pipelines where multiple machines build variants of the same project
