#!/usr/bin/env bash
# Build the plugin, kill any running IntelliJ, redeploy, and relaunch with the mock repo.
set -euo pipefail

PLUGIN_DIR="$(cd "$(dirname "$0")" && pwd)"
IDEA_PLUGINS="$HOME/.local/share/JetBrains/IntelliJIdea2025.3"
IDEA_BIN="/opt/intellij-idea/bin/idea.sh"
MOCK_REPO="${1:-/tmp/mockRepo}"

cd "$PLUGIN_DIR"

echo "==> Building plugin..."
./gradlew buildPlugin

echo "==> Killing IntelliJ IDEA (if running)..."
pkill -f "com.intellij.idea.Main" 2>/dev/null || true
sleep 2

echo "==> Stopping mvnd daemons (flush in-memory build cache)..."
mvnd --stop 2>/dev/null || true

echo "==> Cleaning up stale overlay mounts and temp dirs..."
{ grep -s 'fuse-overlayfs' /proc/mounts || true; } | awk '$2 ~ /cicd-oracle-plugin/ {print $2}' \
  | while read -r mp; do fusermount3 -u "$mp" 2>/dev/null || true; done
rm -rf /dev/shm/cicd-oracle-plugin /tmp/cicd-oracle-plugin*

echo "==> Deploying plugin..."
rm -rf "$IDEA_PLUGINS/cicd-merge-oracle"
unzip -o build/distributions/cicd-merge-oracle-1.0-SNAPSHOT.zip -d "$IDEA_PLUGINS/" > /dev/null

if [ ! -d "$MOCK_REPO/.git" ]; then
  echo "==> Mock repo not found — creating from scratch..."
  "$PLUGIN_DIR/src/test/resources/create-mock-repo.sh" "$MOCK_REPO"
fi

echo "==> Resetting mock repo to pristine merge-conflict state..."
(
  cd "$MOCK_REPO"
  git merge --abort 2>/dev/null || true
  git checkout feature-subtract --force
  git reset --hard feature-subtract
  git clean -fd
  git config merge.conflictstyle diff3
  git merge feature-multiply --no-commit || true
)

echo "==> Launching IntelliJ IDEA with $MOCK_REPO..."
nohup "$IDEA_BIN" "$MOCK_REPO" > /dev/null 2>&1 &
disown

echo "Done."
