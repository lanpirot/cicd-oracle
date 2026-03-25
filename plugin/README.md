# Merge Resolution Tool Plugin

An IntelliJ IDEA plugin to assist developers in **resolving merge conflicts**, analyzing build and test results for different merge variants, and applying customizable resolution patterns.

---

## Features

- **Automatic Merge Resolution:** Run resolution on conflicting branches with selectable patterns.
- **Build & Test Integration:** Automatically build projects and run tests for each variant.
- **Variant Analysis:** Inspect compilation results, test results, and resolution patterns.
- **Interactive Tree View:** Expand and explore variants, build, and test details.
- **Execution Metrics:** Displays current processing status and total execution time.
- **Customizable Patterns:** Choose which resolution strategies to apply via a dropdown.

---

## Requirements

- **IntelliJ IDEA**: `2022.3+` (Community or Ultimate)
- **Java**: 17+
- **Maven**:
- **Maven Daemon** (Optional)
- **[Maven Hook](../maven-hook/README.md)** (Required for cached builds)
- **[Plugin DevKit](https://plugins.jetbrains.com/plugin/22851-plugin-devkit)** (Required for further plugin development)
---

## Installation

### From Compiled ZIP

**Already compiled plugin:** [cicd-merge-oracle-1.0-SNAPSHOT.zip](plugin-zip/cicd-merge-oracle-1.0-SNAPSHOT.zip)

Install the plugin in IntelliJ IDEA:
    - Go to **Settings → Plugins → ⚙️ → Install Plugin from Disk**
    - Select the generated `.zip` file
    - Restart IntelliJ IDEA



---

### From Source (Optional)

Build the plugin:
```bash
./gradlew buildPlugin
```
> The output `.zip` file will be located in `build/distributions/`.

---

## Usage

1. Open a project with merge conflicts.
2. Open the **Merge Resolution Tool** (tab in the bottom right corner):
3. Select desired resolution patterns from the **Patterns** dropdown.
4. Click **Run Conflict Resolution**.
5. Explore the tree view of merge variants, compilation results, and tests.
6. Sort variants by test results with the **Sort by tests** button.
7. Apply variants using the context menu (right-click on a variant).

---

### Running via Sandbox

> HINT: `Plugin DevKit` is required !!!
```bash
./gradlew runIde
```

### Building Plugin

```bash
./gradlew build
```