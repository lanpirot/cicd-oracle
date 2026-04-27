# VM Setup Guide

Target: fresh Ubuntu 24.04 VM, username `seg`, no GPU.

## 1. Provision the VM

Copy and run the setup script (requires sudo):

```bash
scp -P 2033 scripts/setup_vm.sh seg@calculon.inf.unibe.ch:~/
ssh -p 2033 seg@calculon.inf.unibe.ch 'sudo -E bash ~/setup_vm.sh'
```

This installs: JDK 8/11/17/21, Maven 3.9.12, mvnd 1.0.3, fuse-overlayfs, Python venv (CPU-only PyTorch), and creates all data directories.

## 2. Transfer code and data

From the laptop:

```bash
# Code (~small, excludes build artifacts and venv)
rsync -avz -e 'ssh -p 2033' --exclude=target/ --exclude=.venv/ ~/projects/merge++/ seg@calculon.inf.unibe.ch:~/projects/merge++/

# Shared input data (~678 MB)
rsync -avz -e 'ssh -p 2033' ~/data/bruteforcemerge/common/ seg@calculon.inf.unibe.ch:~/data/bruteforcemerge/common/

# RQ1 artefacts needed by RQ2/RQ3: fold CSVs, model checkpoints, predictions (~5.3 GB)
rsync -avz -e 'ssh -p 2033' ~/data/bruteforcemerge/rq1/ seg@calculon.inf.unibe.ch:~/data/bruteforcemerge/rq1/
```

Repos (55 GB) are cloned on-demand — no need to transfer unless you want to skip re-cloning.

## 3. Configure on the VM

### 3a. JDK discovery

`AppConfig.JAVA_HOMES` auto-scans `/usr/lib/jvm/*` at startup, parsing each
install's `release` file to map major version → path. After step 1 the VM has
JDK 8/11/17/21/25 installed, so no manual config is needed.

To pin a specific install (multiple JDKs of the same major version, or a JDK
outside `/usr/lib/jvm/`), drop a `~/.cicd-oracle/java-homes.properties`:

```properties
21=/opt/mycustom/jdk-21
17=/usr/lib/jvm/java-17-openjdk-amd64
```

The override file replaces auto-discovery entirely when present.

### 3b. Build

The `maven-hook` module is at `~/projects/merge++/cicd-oracle/maven-hook` (under
the inner repo, not at the workspace root).

**Interactive shell (after `ssh` login):** `~/.bashrc` exports
`PATH=$JAVA_HOME/bin:/opt/maven/bin:/opt/maven-mvnd-1.0.5-linux-amd64/bin:...`,
so `mvn` and `mvnd` are on PATH automatically.

```bash
ssh -p 2033 seg@calculon.inf.unibe.ch
cd ~/projects/merge++/cicd-oracle/maven-hook && mvn install -DskipTests
cd ~/projects/merge++/cicd-oracle/cicd-oracle && mvn clean package
```

**Non-interactive shell (e.g. `ssh host '<cmd>'`):** Ubuntu's `~/.bashrc`
short-circuits for non-interactive shells (returns at the top before exporting
`PATH`), so `mvn` is **not** on PATH. Either log in first or set the env
explicitly:

```bash
ssh -p 2033 seg@calculon.inf.unibe.ch '
  export PATH=/opt/maven/bin:/opt/maven-mvnd-1.0.5-linux-amd64/bin:$PATH
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  cd ~/projects/merge++/cicd-oracle/maven-hook && mvn install -DskipTests
  cd ~/projects/merge++/cicd-oracle/cicd-oracle && mvn clean package
'
```

VM tooling locations (for reference):
- `mvn`  → `/opt/maven/bin/mvn` (symlink to `/opt/apache-maven-3.9.14`)
- `mvnd` → `/opt/maven-mvnd-1.0.5-linux-amd64/bin/mvnd`
- JDKs   → `/usr/lib/jvm/java-{8,11,17,21}-openjdk-amd64` and `/usr/lib/jvm/temurin-11-jdk-amd64`

### 3c. Verify

```bash
mvn test
```

## 4. Run experiments

Always run inside a `tmux` session — the pipeline takes hours to days and SSH
disconnects would otherwise kill it.

Required system properties on every run:

| Flag | Why |
|---|---|
| `-DoverlayTmpDir=/dev/shm` | Both the human-baseline build and variants write under this path. Putting it on tmpfs makes their I/O share the same backing, so the per-daemon RAM measurement reflects what the variant phase actually does. The VM has `/dev/shm` mounted at ~46 GiB. |
| `-DexperimentTag=<tag>` | Namespaces results to `~/data/bruteforcemerge/<tag>/rq2/`. Use the git tag or branch name. |

```bash
# Start a named session, build, and launch the pipeline
ssh -p 2033 seg@calculon.inf.unibe.ch
tmux new -s rq2

cd ~/projects/merge++/cicd-oracle/cicd-oracle
mvn clean package
java -DoverlayTmpDir=/dev/shm -DexperimentTag=master \
     -cp "target/*:target/lib/*" \
     ch.unibe.cs.mergeci.experiment.RQ2PipelineRunner

# Detach with Ctrl-b d (the session keeps running). To attach later:
ssh -p 2033 seg@calculon.inf.unibe.ch -t tmux attach -t rq2
```

To compare against the pre-overlayfs baseline, repeat the run with
`git checkout rq2-before-overlayfs` and `-DexperimentTag=rq2-before-overlayfs`
in a separate tmux session.

For unattended launches from the laptop, the inner shell is non-interactive,
so set `PATH` and `JAVA_HOME` explicitly (see §3b for why):

```bash
ssh -p 2033 seg@calculon.inf.unibe.ch '
  export PATH=/opt/maven/bin:/opt/maven-mvnd-1.0.5-linux-amd64/bin:$PATH
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  tmux new -d -s rq2 "cd ~/projects/merge++/cicd-oracle/cicd-oracle && \
     java -DoverlayTmpDir=/dev/shm -DexperimentTag=master \
          -cp \"target/*:target/lib/*\" \
          ch.unibe.cs.mergeci.experiment.RQ2PipelineRunner \
     2>&1 | tee ~/rq2-master.log"
'
```

Optional system properties (override defaults from `AppConfig`):

| Flag | Effect |
|---|---|
| `-DfreshRun=true` | Wipe all data/output dirs at startup (full reset). Pipeline pauses for a `y/N` confirmation — in detached tmux send it with `tmux send-keys -t rq2 "y" Enter`. |
| `-DreanalyzeSuccess=true` | Re-analyze repos already marked SUCCESS without re-cloning. |
| `-DmaxConflictMerges=N` | Cap merges per project (default 5). |
| `-Drq3BestMode=<name>` | Override which mode RQ3 reads from (e.g. `cache_parallel`). |

## 5. Clean up before re-running

A killed pipeline leaves three kinds of stale state behind that the next run
won't fix on its own: the tmux session (you can't `tmux new -s rq2` while one
already exists), idle `mvnd` daemons (3 h `idleTimeout`), and live
`fuse-overlayfs` mounts on `/dev/shm/projects/*` and
`/tmp/bruteforce_tmp/m2_overlays/*` whose underlying directories the next run
will try to recreate.

```bash
ssh -p 2033 seg@calculon.inf.unibe.ch '
  # 1. Kill the tmux session (sends SIGHUP to its java process)
  tmux kill-session -t rq2 2>/dev/null
  sleep 5

  # 2. Kill mvnd daemons (the SIGTERM here can drop your own ssh shell —
  #    that is a non-issue, the kill still ran; reconnect to verify).
  pkill -TERM -f mvnd-agent
  sleep 3
  pkill -9    -f mvnd-agent 2>/dev/null

  # 3. Force-unmount any orphaned fuse-overlayfs mounts.
  mount | grep fuse-overlayfs | awk "{print \$3}" | xargs -r -I {} fusermount -uz {}

  # 4. Sweep the now-empty overlay temp dirs.
  rm -rf /dev/shm/projects /tmp/bruteforce_tmp/m2_overlays
'

# Verify clean (reconnect if step 2 dropped the previous shell):
ssh -p 2033 seg@calculon.inf.unibe.ch '
  echo tmux: $(tmux ls 2>&1)
  echo mvnd: $(pgrep -af mvnd-agent | grep -v pgrep | wc -l)
  echo overlays: $(mount | grep -c fuse-overlayfs)
'
```

Expected output: `tmux: no server running…`, `mvnd: 0`, `overlays: 0`.

Notes:
- **The `pkill -f mvnd-agent` line can drop your own ssh shell** — the
  `bash -c '…'` that `ssh` spawns has the literal string `mvnd-agent` in its
  argv, so it self-matches. The kill still completes against the real daemons
  before your shell dies; just reconnect and verify.
- `fusermount -uz` (lazy unmount) succeeds even when files inside the mount
  are still open, which `fusermount -u` will refuse.
- `OverlayMount.cleanupStaleMounts(...)` runs at the start of every pipeline
  invocation and will sweep leftover **directories**, but it cannot unmount
  active overlays — do that manually before relaunching.
