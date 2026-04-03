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

### 3a. Update JAVA_HOMES

Check installed JDK paths:

```bash
ls /usr/lib/jvm/
```

Edit `cicd-oracle/src/main/java/ch/unibe/cs/mergeci/config/AppConfig.java` — update the `JAVA_HOMES` map to match:

```java
public static final Map<Integer, Path> JAVA_HOMES = Map.of(
    8,  Paths.get("/usr/lib/jvm/temurin-8-jdk-amd64"),
    11, Paths.get("/usr/lib/jvm/temurin-11-jdk-amd64"),
    17, Paths.get("/usr/lib/jvm/java-17-openjdk-amd64"),
    21, Paths.get("/usr/lib/jvm/java-21-openjdk-amd64")
);
```

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

Use `-DexperimentTag=<tag>` to namespace results (e.g. by git tag):

```bash
# Pre-overlayfs baseline
git checkout rq2-before-overlayfs
mvn clean package
java -DexperimentTag=rq2-before-overlayfs -cp "target/*:target/lib/*" \
    ch.unibe.cs.mergeci.experiment.RQ2PipelineRunner

# Current master
git checkout master
mvn clean package
java -DexperimentTag=master -cp "target/*:target/lib/*" \
    ch.unibe.cs.mergeci.experiment.RQ2PipelineRunner
```

Results go to `~/data/bruteforcemerge/<tag>/rq2/`.
