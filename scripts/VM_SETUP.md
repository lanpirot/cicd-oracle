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

```bash
source ~/.bashrc
cd ~/projects/merge++/maven-hook && mvn install
cd ~/projects/merge++/cicd-oracle/cicd-oracle && mvn clean package
```

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

For unattended runs, redirect output so detaching doesn't lose history:

```bash
tmux new -d -s rq2 \
  "cd ~/projects/merge++/cicd-oracle/cicd-oracle && \
   mvn clean package && \
   java -DoverlayTmpDir=/dev/shm -DexperimentTag=master \
        -cp 'target/*:target/lib/*' \
        ch.unibe.cs.mergeci.experiment.RQ2PipelineRunner \
   2>&1 | tee ~/rq2-master.log"
```

Optional system properties (override defaults from `AppConfig`):

| Flag | Effect |
|---|---|
| `-DfreshRun=true` | Wipe all data/output dirs at startup (full reset). |
| `-DreanalyzeSuccess=true` | Re-analyze repos already marked SUCCESS without re-cloning. |
| `-DmaxConflictMerges=N` | Cap merges per project (default 5). |
| `-Drq3BestMode=<name>` | Override which mode RQ3 reads from (e.g. `cache_parallel`). |
