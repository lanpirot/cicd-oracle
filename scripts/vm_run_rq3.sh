#!/usr/bin/env bash
# vm_run_rq3.sh — Deploy and launch the RQ3 pipeline on the calculon VM.
#
# Single-file playbook covering: local commit, push, remote cleanup, git pull,
# build, and launch in tmux. Modeled after VM_SETUP.md.
#
# Steps:
#   1. Locally — stage tracked + untracked changes, commit (auto message),
#      push current branch to origin.
#   2. Remotely — kill any rq3 tmux session, mvnd daemons, and fuse-overlayfs
#      mounts; sweep stale overlay temp dirs.
#   3. Remotely — fetch and hard-reset to origin/<branch> (deploy semantics).
#   4. Remotely — build maven-hook (mvn install, skip tests) and cicd-oracle
#      (mvn clean package, skip tests).
#   5. Remotely — launch RQ3PipelineRunner in detached tmux with
#        -DfreshRun=true   -DexperimentTag=<tag>
#        -Drq3BestMode=<mode>   -DoverlayTmpDir=/dev/shm
#      and auto-confirm the freshRun y/N prompt.
#
# Usage (from cicd-oracle workspace root):
#   ./scripts/vm_run_rq3.sh                                # all defaults
#   ./scripts/vm_run_rq3.sh --tag rq3_VM_S_2026-05-06
#   ./scripts/vm_run_rq3.sh --best-mode no_optimization
#   ./scripts/vm_run_rq3.sh --sample-target 1000           # stop after N successes
#   ./scripts/vm_run_rq3.sh --no-fresh                     # skip freshRun (resume)
#   ./scripts/vm_run_rq3.sh --commit-msg "rq3: ..."
#   ./scripts/vm_run_rq3.sh --dry-run                      # print, don't execute
#
# After launch:
#   ssh -p 2033 seg@calculon.inf.unibe.ch -t tmux attach -t rq3
#   ssh -p 2033 seg@calculon.inf.unibe.ch 'tail -f ~/<tag>.log'

set -euo pipefail

# ── Defaults ────────────────────────────────────────────────────────────────
VM_HOST="seg@calculon.inf.unibe.ch"
VM_PORT="2033"
TMUX_SESSION="rq3"
BEST_MODE="no_optimization"          # S — promoted by RQ3 head-to-head analysis
TAG="rq3_VM_$(date +%Y-%m-%d)"
SAMPLE_TARGET="1000"                  # RQ3-at-scale target; code default is only 500
FRESH=true
DRY_RUN=false
COMMIT_MSG=""

# ── Parse args ──────────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        --tag)           TAG="$2";           shift 2 ;;
        --best-mode)     BEST_MODE="$2";     shift 2 ;;
        --sample-target) SAMPLE_TARGET="$2"; shift 2 ;;
        --no-fresh)   FRESH=false;      shift ;;
        --dry-run)    DRY_RUN=true;     shift ;;
        --commit-msg) COMMIT_MSG="$2";  shift 2 ;;
        -h|--help)    sed -n '2,30p' "$0"; exit 0 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

run_local()  { echo "+ (local) $*";  if ! $DRY_RUN; then "$@"; fi; }
run_remote() { local cmd="$1"; echo "+ (remote)"; printf '    %s\n' "$cmd";
               if ! $DRY_RUN; then ssh -p "$VM_PORT" "$VM_HOST" "$cmd"; fi; }

# ── Locate repo root ────────────────────────────────────────────────────────
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
echo "Repo root: $REPO_ROOT"

# ── Step 1: local commit (if dirty) and push ────────────────────────────────
echo
echo "=== Step 1/5: local commit + push ==="
if [[ -n "$(git status --porcelain)" ]]; then
    if [[ -z "$COMMIT_MSG" ]]; then
        COMMIT_MSG="rq3-deploy: $TAG (auto)"
    fi
    echo "Working tree dirty — committing all changes."
    run_local git add -A
    run_local git commit -m "$COMMIT_MSG"
else
    echo "Working tree clean — nothing to commit."
fi

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
echo "Pushing $BRANCH to origin..."
run_local git push origin "$BRANCH"

# ── Step 2: remote cleanup ──────────────────────────────────────────────────
# Split into separate SSH calls because `pkill -f mvnd-agent` matches its own
# parent bash (the `mvnd-agent` substring is in the bash -c argv) and would
# self-terminate the ssh session, skipping the rest of a single heredoc.
echo
echo "=== Step 2/5: remote cleanup (tmux + mvnd + overlays) ==="

# 2a. Kill tmux session.
run_remote "tmux kill-session -t $TMUX_SESSION 2>/dev/null || true; sleep 3"

# 2b. Kill mvnd daemons. This ssh shell self-matches and may exit non-zero
# (per VM_SETUP.md); the kill still completes against the real daemons first.
echo "+ (remote, self-terminate-tolerant)"
echo "    pkill -TERM -f mvnd-agent ..."
if ! $DRY_RUN; then
    ssh -p "$VM_PORT" "$VM_HOST" \
        "pkill -TERM -f mvnd-agent 2>/dev/null; sleep 3; pkill -9 -f mvnd-agent 2>/dev/null" \
        || echo "    (mvnd kill ssh exited non-zero — expected, see VM_SETUP.md)"
fi

# 2c. Unmount any orphaned fuse-overlayfs and sweep stale overlay dirs.
run_remote "$(cat <<'EOF'
  set +e
  mount | grep fuse-overlayfs | awk '{print $3}' | xargs -r -I {} fusermount -uz {} 2>/dev/null
  rm -rf /dev/shm/projects /tmp/bruteforce_tmp/m2_overlays
  exit 0
EOF
)"

# 2d. Verify clean state.
run_remote "$(cat <<'EOF'
  echo tmux:     $(tmux ls 2>&1 | head -1)
  echo mvnd:     $(pgrep -af mvnd-agent | grep -v pgrep | wc -l)
  echo overlays: $(mount | grep -c fuse-overlayfs)
EOF
)"

# ── Step 3: remote fetch + hard reset to origin/<branch> ────────────────────
echo
echo "=== Step 3/5: remote git fetch + reset --hard origin/$BRANCH ==="
run_remote "$(cat <<EOF
  set -e
  cd ~/projects/merge++/cicd-oracle
  git fetch origin
  # -B creates or resets the local branch to point at origin/<branch> and switches
  # to it. The post-checkout LFS hook may exit non-zero on hosts without
  # git-lfs installed (the repo tracks one CSV via LFS); the hook is advisory,
  # so we tolerate it and verify HEAD explicitly below.
  git checkout -B $BRANCH origin/$BRANCH || true
  HEAD_SHA=\$(git rev-parse --short HEAD)
  ORIGIN_SHA=\$(git rev-parse --short origin/$BRANCH)
  if [ "\$HEAD_SHA" != "\$ORIGIN_SHA" ]; then
    echo "FAIL: HEAD=\$HEAD_SHA != origin/$BRANCH=\$ORIGIN_SHA" >&2
    exit 1
  fi
  echo "HEAD: \$HEAD_SHA on \$(git rev-parse --abbrev-ref HEAD)"
EOF
)"

# ── Step 4: build maven-hook + cicd-oracle ──────────────────────────────────
echo
echo "=== Step 4/5: build maven-hook + cicd-oracle on VM ==="
run_remote "$(cat <<'EOF'
  set -e
  export PATH=/opt/maven/bin:/opt/maven-mvnd-1.0.5-linux-amd64/bin:$PATH
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

  cd ~/projects/merge++/cicd-oracle/maven-hook
  mvn install -DskipTests -q

  cd ~/projects/merge++/cicd-oracle/cicd-oracle
  mvn clean package -DskipTests -q
  echo "Build OK."
EOF
)"

# ── Step 5: launch RQ3 in tmux ──────────────────────────────────────────────
FRESH_FLAG=""
if $FRESH; then FRESH_FLAG="-DfreshRun=true"; fi

echo
echo "=== Step 5/5: launch RQ3PipelineRunner in tmux ==="
echo "  best-mode = $BEST_MODE"
echo "  tag       = $TAG"
echo "  target    = $SAMPLE_TARGET successful merges"
echo "  fresh     = $FRESH"
echo "  log       = ~/$TAG.log"

run_remote "$(cat <<EOF
  set -e
  export PATH=/opt/maven/bin:/opt/maven-mvnd-1.0.5-linux-amd64/bin:\$PATH
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

  tmux new -d -s $TMUX_SESSION "cd ~/projects/merge++/cicd-oracle/cicd-oracle && \
     java -DoverlayTmpDir=/dev/shm \
          -DexperimentTag=$TAG \
          -Drq3BestMode=$BEST_MODE \
          -Drq3SampleTarget=$SAMPLE_TARGET \
          -DmavenBuildTimeout=3600 \
          -DmaxVariantBudget=36000 \
          $FRESH_FLAG \
          -cp 'target/*:target/lib/*' \
          ch.unibe.cs.mergeci.experiment.RQ3PipelineRunner \
       2>&1 | tee ~/$TAG.log"
  sleep 1
  tmux ls
EOF
)"

# Auto-confirm the freshRun y/N prompt. The pipeline pauses early; sleep
# generously and send y twice (idempotent if the prompt is already past).
if $FRESH; then
    echo "Auto-confirming freshRun prompt in 15 s..."
    if ! $DRY_RUN; then
        sleep 15
        ssh -p "$VM_PORT" "$VM_HOST" \
            "tmux send-keys -t $TMUX_SESSION 'y' Enter 2>/dev/null || true"
        sleep 5
        ssh -p "$VM_PORT" "$VM_HOST" \
            "tmux send-keys -t $TMUX_SESSION 'y' Enter 2>/dev/null || true"
    fi
fi

echo
echo "=== Launch complete ==="
echo "Attach:   ssh -p $VM_PORT $VM_HOST -t tmux attach -t $TMUX_SESSION"
echo "Tail log: ssh -p $VM_PORT $VM_HOST 'tail -f ~/$TAG.log'"
echo "Kill:     ssh -p $VM_PORT $VM_HOST 'tmux kill-session -t $TMUX_SESSION'"
echo "Output:   ~/data/bruteforcemerge/$TAG/rq3/   (on the VM)"
