# CI/CD as Oracle: Pattern-Based Merge Resolution with Learned Variant Generation

A research toolchain that automatically resolves merge conflicts by brute-forcing resolution pattern combinations and using Maven builds and test suites as an oracle to rank them. It also trains an autoregressive ML model to bias the search towards patterns that historically worked.

**Research questions:**
- **RQ1:** Are pattern distributions learnable to bias search space exploration?
- **RQ2:** Which variant generation mode is the fastest?
- **RQ3:** Can we find variants as good or better than the human baseline?

---

## Quick Start

Requirements: **Java 21**, **Git**, **Apache Maven 3.9+**

```bash
cd cicd-oracle
mvn clean package

# Fresh run (clears all prior output)
java -DfreshRun=true -cp “target/*:target/lib/*” ch.unibe.cs.mergeci.CiCdMergeResolverApplication

# Resume (skips already-processed repos and merges)
java -cp “target/*:target/lib/*” ch.unibe.cs.mergeci.CiCdMergeResolverApplication
```

The pipeline runs three phases automatically:
1. **Collect** — clone repos, find merge commits with Java conflicts, run baseline builds
2. **Generate variants** — for each merge, try all resolution pattern combinations within a time budget
3. **Analyse** — rank variants, compare to human baseline, produce statistics

See [cicd-oracle/README.md](cicd-oracle/README.md) for full configuration, RQ1/RQ2/RQ3 pipeline details, and data directory layout.

---

## Repository Structure

| Component | Description | Docs |
|-----------|-------------|------|
| `cicd-oracle/` | Core research pipeline (Java/Spring Boot) | [README](cicd-oracle/README.md) |
| `maven-hook/` | Maven plugin that extends build/test lifecycles for cache support | [README](maven-hook/README.md) |
| `demo/` | IntelliJ IDEA plugin — GUI for running conflict resolution interactively | [README](demo/README.md) |

---

## Configuration

All paths, timeouts, and feature flags are in `cicd-oracle/src/main/java/ch/unibe/cs/mergeci/config/AppConfig.java`. Key runtime overrides:

| Property | Default | Effect |
|----------|---------|--------|
| `freshRun` | `false` | Delete all output and start from scratch |
| `coverageActivated` | `true` | Collect JaCoCo coverage |
| `maxConflictMerges` | `10` | Max qualifying merges per project |
