Tel# Exception Handling Analysis

## 🔴 CRITICAL Issues (Data Loss / Silent Failures)

### 1. **FileUtils.java:48-51** - Silent exception with null return
```java
try {
    ObjectLoader objectLoader = git.getRepository().open(objectId);
    return objectLoader.openStream();
} catch (Exception e) {
    System.out.println();  // ❌ Only prints blank line!
}
return null;  // ❌ Returns null, caller doesn't know failure occurred
```
**Problem**: Catches all exceptions, only prints empty line, returns null
**Impact**: Caller doesn't know if null means "object doesn't exist" or "exception occurred"
**Severity**: CRITICAL - Can cause data corruption if null is treated as valid empty object

### 2. **DatasetCollectionOrchestrator.java:110-112** - Exception swallowed, returns partial data
```java
try (Git git = GitUtils.getGit(projectPath)) {
    totalMergeCount = GitUtils.getTotalMergeCount(git);
    mergesWithConflicts = GitUtils.getConflictCommits(maxConflictMerges, git);
} catch (Exception e) {
    e.printStackTrace();  // ❌ Only prints stack trace
}
return new MergeLoadResult(mergesWithConflicts, totalMergeCount);  // ❌ Returns with defaults (0/empty)
```
**Problem**: Returns partial/default data after exception
**Impact**: Caller thinks there are 0 merges when actually Git failed
**Severity**: CRITICAL - Leads to incorrect analysis results

### 3. **MavenCommandResolver.java:80-82** - Silent failure
```java
} catch (IOException e) {
    // Silently fail - wrapper will still work in most cases
}
```
**Problem**: Completely silent failure with only a comment
**Impact**: mvnw line ending fix fails silently, might cause "permission denied" on Unix
**Severity**: CRITICAL - Can cause builds to fail mysteriously

### 4. **GitUtils.java:282-285** - Conditional silence
```java
} catch (Exception e) {
    if (!(e instanceof NoMergeBaseException))
        e.printStackTrace();
}
```
**Problem**: Catches broad Exception, only some are logged, execution continues
**Impact**: Important git errors might be hidden
**Severity**: CRITICAL - Hides repository corruption or access issues

---

## 🟡 MODERATE Issues (printStackTrace without proper logging)

### Multiple Files - Only printStackTrace, no logging framework

| File | Lines | Issue |
|------|-------|-------|
| **MavenCacheManager.java** | 34, 61, 75, 97 | Cache operations fail silently with printStackTrace |
| **FileUtils.java** | 38, 130 | File I/O fails with printStackTrace then re-throws |
| **GitUtils.java** | 118, 159, 300 | Git operations fail with printStackTrace |
| **Strategy classes** | Various | Parallel test execution failures only printed |
| **ProjectBuilderUtils.java** | 55 | IOException in variant building only printed |
| **CoverageCalculator.java** | 69, 90, 99, 111, 121, 146 | Coverage calculation failures swallowed |
| **ExcelWriter.java** | 91 | InvalidFormatException printed then wrapped |

**Problem**: `printStackTrace()` goes to stderr, not to log files, hard to debug in production
**Impact**: Failures are visible in console but not properly logged for analysis
**Severity**: MODERATE - Makes debugging difficult, no structured logging

---

## ✅ GOOD Exception Handling (for reference)

### Proper patterns found in codebase:

1. **MavenProcessExecutor.java:65-67** - InterruptedException handled correctly
```java
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();  // ✅ Restore interrupt status
}
```

2. **Clone pattern classes** - CloneNotSupportedException properly handled
```java
} catch (CloneNotSupportedException e) {
    throw new AssertionError();  // ✅ Should never happen, fail fast
}
```

3. **MergeCheckoutProcessor.java:64-67** - Error logged then re-thrown
```java
} catch (IOException e) {
    System.err.println("Error copying folder: " + e.getMessage());
    throw e;  // ✅ Re-throw after logging
}
```

---

## 📋 Recommendations

### Immediate Fixes (Critical):

1. **FileUtils.getObjectStream()** - Throw IOException instead of returning null
2. **DatasetCollectionOrchestrator.loadMerges()** - Throw exception instead of returning defaults
3. **MavenCommandResolver.fixUnixLineEndings()** - Log warning on failure
4. **GitUtils.getConflictCommits()** - Add proper error handling and logging

### Medium-term Improvements:

1. Replace `printStackTrace()` with proper logging framework (SLF4J + Logback)
2. Add custom exception types for domain-specific errors
3. Use Result/Optional types to distinguish "no data" from "error"
4. Add exception handling tests for critical paths

### Code Patterns to Adopt:

```java
// ✅ GOOD: Specific exception, logged, re-thrown
try {
    riskyOperation();
} catch (IOException e) {
    log.error("Failed to perform operation: {}", e.getMessage(), e);
    throw new RuntimeException("Operation failed", e);
}

// ✅ GOOD: Return Optional to distinguish absence from error
public Optional<InputStream> getObjectStream(ObjectId objectId) throws IOException {
    try {
        ObjectLoader loader = git.getRepository().open(objectId);
        return Optional.of(loader.openStream());
    } catch (MissingObjectException e) {
        return Optional.empty();  // Object doesn't exist
    }
    // Other IOExceptions propagate to caller
}

// ❌ BAD: Silent failure
try {
    riskyOperation();
} catch (Exception e) {
    // Silent or just printStackTrace()
}
```

---

## Test Coverage Needed

Files needing exception handling tests:
- ✅ FileUtils (critical path)
- ✅ DatasetCollectionOrchestrator (merge loading)
- ✅ MavenCommandResolver (wrapper execution)
- ✅ GitUtils (repository operations)
- MavenCacheManager (cache operations)
- Strategy classes (parallel execution)
