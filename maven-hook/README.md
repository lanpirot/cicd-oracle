# Maven Hook for Cache Extension

We implemented a **custom Maven extension (`MavenHook`)** to enhance our caching mechanism by automatically **clearing
previous test reports** before compilation and test compilation. This ensures that old `surefire-reports` do not
interfere with cached results when running automated merge builds.

---

## How It Works

`MavenHook` extends `AbstractEventSpy` and listens to Maven build lifecycle events:

**Event Handling (`onEvent`)**
- Listens for `ExecutionEvent`s emitted during the Maven lifecycle.
- When a Mojo starts (`MojoStarted`) with phase `compile` or `test-compile`:
    - Retrieves the current Maven project and its base directory.
    - Deletes the `target/surefire-reports` folder to ensure previous test results are removed.

**Key points:**

- Automatically removes old test reports for clean caching.
- Hooks into the Maven lifecycle without modifying POM files.
- Works for any Maven project using standard `compile` and `test-compile` phases.

---

## Installation

1. **Install plugin in local maven repository**
```bash
    mvn clean install
```

## Usage

No additional configuration is required to use this extension within our tool — it works out of the box.

If you want to use this extension **separately from our tool or plugin**, you can install it in your Maven project by adding it to the `.mvn/extensions.xml` file:

```xml
<extensions>
    <extension>
        <groupId>ch.unibe.cs</groupId>
        <artifactId>maven-hook</artifactId>
        <version>1.0-SNAPSHOT</version>
    </extension>
</extensions>