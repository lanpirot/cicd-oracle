#!/usr/bin/env bash
# setup_vm.sh — Provision a fresh Ubuntu 24.04 VM for cicd-oracle RQ2/RQ3 experiments.
# Run as: sudo -E bash setup_vm.sh   (preserves $HOME for the calling user)
#
# After running this script:
#   1. source ~/.bashrc
#   2. Update JAVA_HOMES in AppConfig.java to match installed paths
#   3. cd ~/projects/merge++/cicd-oracle/maven-hook && mvn install
#   4. cd ~/projects/merge++/cicd-oracle/cicd-oracle && mvn clean package
set -euo pipefail

# ---------- resolve target user (the human, not root) ----------
TARGET_USER="${SUDO_USER:-$USER}"
TARGET_HOME=$(eval echo "~$TARGET_USER")
echo "==> Setting up for user: $TARGET_USER (home: $TARGET_HOME)"

# ---------- system packages ----------
echo "==> Installing system packages..."
apt-get update -qq
apt-get install -y -qq git curl wget unzip fuse-overlayfs python3 python3-venv python3-pip \
    build-essential ca-certificates gnupg \
    texlive-latex-base texlive-fonts-recommended texlive-latex-extra cm-super dvipng

# ---------- JDKs ----------

# OpenJDK 17 + 21 from Ubuntu repos
echo "==> Installing OpenJDK 17, 21..."
apt-get install -y -qq openjdk-17-jdk openjdk-21-jdk

# Adoptium (Temurin) for JDK 8, 11, and 25
echo "==> Adding Adoptium apt repo for JDK 8, 11, 25..."
install -m 0755 -d /etc/apt/keyrings
wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public \
    | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg 2>/dev/null || true
echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb noble main" \
    > /etc/apt/sources.list.d/adoptium.list
apt-get update -qq
apt-get install -y -qq temurin-8-jdk temurin-11-jdk temurin-25-jdk

echo "==> Installed JDKs:"
ls /usr/lib/jvm/

# ---------- Maven 3.9.x ----------
MAVEN_VERSION="3.9.14"
if [ ! -d /opt/maven ]; then
    echo "==> Installing Maven ${MAVEN_VERSION}..."
    wget -q "https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
        -O /tmp/maven.tar.gz
    tar xzf /tmp/maven.tar.gz -C /opt
    ln -sfn "/opt/apache-maven-${MAVEN_VERSION}" /opt/maven
    rm /tmp/maven.tar.gz
else
    echo "==> Maven already installed at /opt/maven"
fi

# ---------- Maven Daemon (mvnd) ----------
MVND_VERSION="1.0.5"
MVND_DIR="/opt/maven-mvnd-${MVND_VERSION}-linux-amd64"
if [ ! -d "$MVND_DIR" ]; then
    echo "==> Installing mvnd ${MVND_VERSION}..."
    wget -q "https://github.com/apache/maven-mvnd/releases/download/${MVND_VERSION}/maven-mvnd-${MVND_VERSION}-linux-amd64.tar.gz" \
        -O /tmp/mvnd.tar.gz
    tar xzf /tmp/mvnd.tar.gz -C /opt
    rm /tmp/mvnd.tar.gz
else
    echo "==> mvnd already installed at ${MVND_DIR}"
fi

# ---------- directory structure ----------
echo "==> Creating directory structure..."
sudo -u "$TARGET_USER" mkdir -p \
    "$TARGET_HOME/data/bruteforcemerge/common" \
    "$TARGET_HOME/data/bruteforcemerge/rq1" \
    "$TARGET_HOME/data/bruteforcemerge/rq2" \
    "$TARGET_HOME/data/bruteforcemerge/rq3" \
    "$TARGET_HOME/data/bruteforcemerge/variant_experiments" \
    "$TARGET_HOME/data/bruteforcemerge/test" \
    "$TARGET_HOME/data/bruteforcemerge/conflict_files" \
    "$TARGET_HOME/tmp/bruteforce_repos" \
    "$TARGET_HOME/tmp/bruteforce_tmp" \
    "$TARGET_HOME/projects/merge++"

# ---------- Python venv (CPU-only PyTorch) ----------
VENV_DIR="$TARGET_HOME/projects/merge++/.venv"
if [ ! -d "$VENV_DIR" ]; then
    echo "==> Creating Python venv with CPU-only PyTorch..."
    sudo -u "$TARGET_USER" python3 -m venv "$VENV_DIR"
    sudo -u "$TARGET_USER" "$VENV_DIR/bin/pip" install -q --upgrade pip
    sudo -u "$TARGET_USER" "$VENV_DIR/bin/pip" install -q \
        torch --index-url https://download.pytorch.org/whl/cpu
    sudo -u "$TARGET_USER" "$VENV_DIR/bin/pip" install -q \
        numpy pandas scipy scikit-learn matplotlib
else
    echo "==> Python venv already exists at ${VENV_DIR}"
fi

# ---------- bashrc additions ----------
BASHRC="$TARGET_HOME/.bashrc"
MARKER="# --- cicd-oracle setup ---"
if ! grep -qF "$MARKER" "$BASHRC" 2>/dev/null; then
    echo "==> Appending environment setup to $BASHRC..."
    sudo -u "$TARGET_USER" tee -a "$BASHRC" > /dev/null <<'BASHEOF'

# --- cicd-oracle setup ---
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$JAVA_HOME/bin:/opt/maven/bin:/opt/maven-mvnd-1.0.5-linux-amd64/bin:$HOME/.local/bin:$PATH

# JDK switching aliases (adjust paths after 'ls /usr/lib/jvm/')
set_java_home() { export JAVA_HOME="$1"; export PATH="$JAVA_HOME/bin:$PATH"; }
alias java21='set_java_home /usr/lib/jvm/java-21-openjdk-amd64'
alias java17='set_java_home /usr/lib/jvm/java-17-openjdk-amd64'
alias java11='set_java_home /usr/lib/jvm/temurin-11-jdk-amd64'
alias java8='set_java_home /usr/lib/jvm/temurin-8-jdk-amd64'
alias java25='set_java_home /usr/lib/jvm/temurin-25-jdk-amd64'
# --- end cicd-oracle setup ---
BASHEOF
else
    echo "==> bashrc already configured"
fi

# ---------- summary ----------
echo ""
echo "=========================================="
echo "  Setup complete!"
echo "=========================================="
echo ""
echo "Installed JDKs:        $(ls /usr/lib/jvm/ | tr '\n' ' ')"
echo "Maven:                 $(/opt/maven/bin/mvn --version 2>/dev/null | head -1)"
echo "mvnd:                  $(${MVND_DIR}/bin/mvnd --version 2>/dev/null | head -1)"
echo "Python venv:           ${VENV_DIR}"
echo "fuse-overlayfs:        $(fuse-overlayfs --version 2>/dev/null | head -1)"
echo ""
echo "Expected JAVA_HOMES for AppConfig.java on this VM:"
echo "   8  -> /usr/lib/jvm/temurin-8-jdk-amd64"
echo "  11  -> /usr/lib/jvm/temurin-11-jdk-amd64"
echo "  17  -> /usr/lib/jvm/java-17-openjdk-amd64"
echo "  21  -> /usr/lib/jvm/java-21-openjdk-amd64"
echo "  25  -> /usr/lib/jvm/temurin-25-jdk-amd64"
echo ""
echo "Next steps:"
echo "  1. source ~/.bashrc"
echo "  2. Update JAVA_HOMES in AppConfig.java to the paths above"
echo "  3. rsync code + data from laptop (see below)"
echo "  4. cd ~/projects/merge++/cicd-oracle/maven-hook && mvn install"
echo "  5. cd ~/projects/merge++/cicd-oracle/cicd-oracle && mvn clean package"
echo "  6. mvn test"
echo ""
echo "rsync commands (run from laptop):"
echo "  rsync -avz --exclude=target/ --exclude=.venv/ ~/projects/merge++/ seg@VM:~/projects/merge++/"
echo "  rsync -avz ~/data/bruteforcemerge/common/ seg@VM:~/data/bruteforcemerge/common/"
echo "  rsync -avz ~/data/bruteforcemerge/rq1/   seg@VM:~/data/bruteforcemerge/rq1/"
