# Code Coverage Analysis Report

**Generated:** 2026-03-13
**Tool:** JaCoCo Maven Plugin
**Overall Line Coverage:** 62.2%

---

## 📊 Executive Summary

- **Total Classes:** 92
- **Well Tested (>70%):** 58 classes (63.0%)
- **Moderately Tested (50-70%):** 8 classes (8.7%)
- **Low Coverage (20-50%):** 7 classes (7.6%)
- **Very Low (<20%):** 6 classes (6.5%)
- **Zero Coverage (0%):** 13 classes (14.1%)

---

## 🔴 Critical Priority: Zero Coverage Classes (>10 lines)

These classes have **0% test coverage** and are substantial enough to warrant immediate testing:

### 1. **JacocoReportFinder** (45 lines)
- **Package:** `ch.unibe.cs.mergeci.service.projectRunners.maven`
- **Purpose:** Finds and processes Jacoco coverage reports
- **Risk:** Coverage calculation may fail silently
- **Test Needed:** Unit tests for report finding and parsing logic

### 2. **CacheParallelStrategy** (40 lines)
- **Package:** `ch.unibe.cs.mergeci.service.projectRunners.maven.strategy`
- **Purpose:** Parallel execution strategy with caching
- **Risk:** Performance optimizations unverified
- **Test Needed:** Integration tests for parallel execution with cache

### 3. **MavenExecutionFactory.new IRunner() {...}** (38 lines)
- **Package:** `ch.unibe.cs.mergeci.service`
- **Purpose:** Deprecated runner creation (legacy budget-based)
- **Risk:** Legacy code paths untested
- **Test Needed:** Tests or removal if truly deprecated

### 4. **CoverageStrategy** (24 lines)
- **Package:** `ch.unibe.cs.mergeci.service.projectRunners.maven.strategy`
- **Purpose:** Strategy for running with coverage
- **Risk:** Coverage collection may not work
- **Test Needed:** Integration tests with Jacoco

### 5. **MavenExecutionFactory.new IRunner() {...}** (18 lines)
- **Package:** `ch.unibe.cs.mergeci.service`
- **Purpose:** Another deprecated runner
- **Risk:** Legacy code untested
- **Test Needed:** Tests or removal

---

## 🟠 High Priority: Very Low Coverage Classes (<20%)

### Analysis & Reporting Module

#### **VariantResolutionAnalyzer** (1.9% coverage, 52 lines)
- **Current:** Only 1 line covered out of 52
- **Purpose:** Analyzes variant resolution patterns
- **Test Gap:** Core analysis logic untested
- **Recommendation:** Add tests for pattern analysis, resolution tracking

#### **VariantRankingAnalyzer** (4.5% coverage, 66 lines)
- **Current:** Only 3 lines covered out of 66
- **Purpose:** Ranks variants by effectiveness
- **Test Gap:** Ranking algorithms untested
- **Recommendation:** Add tests for ranking logic, coverage comparison, conflict chunk ranking

### Maven Execution Module

#### **MavenCacheManager** (2.4% coverage, 42 lines)
- **Current:** Only 1 line covered out of 42
- **Purpose:** Manages Maven dependency cache
- **Test Gap:** Cache operations untested
- **Risk:** Cache corruption, disk space issues
- **Recommendation:** Add tests for cache creation, cleanup, error handling

### Coverage Calculation Module

#### **CoverageCalculator** (15.4% coverage, 65 lines)
- **Current:** Only 10 lines covered out of 65
- **Purpose:** Calculates code coverage for merge variants
- **Test Gap:** Most calculation logic untested
- **Risk:** Incorrect coverage metrics
- **Recommendation:** Add tests for coverage extraction, calculation, edge cases

### Application Layer

#### **CiCdMergeResolverApplication** (4.3% coverage, 23 lines)
- **Current:** Only 1 line covered out of 23
- **Purpose:** Main application entry point
- **Test Gap:** Application startup, configuration loading
- **Recommendation:** Add integration tests for application lifecycle

---

## 🟡 Medium Priority: Low Coverage Classes (21-50%)

### **StatisticsReporter** (42.4% coverage, 257 lines)
- **Largest class with low coverage**
- **Purpose:** Generates experiment statistics reports
- **Test Gap:** 148 lines untested
- **Recommendation:** Add tests for report generation, formatting, statistics calculation

### **ExcelWriter** (42.3% coverage, 71 lines)
- **Purpose:** Writes dataset results to Excel files
- **Test Gap:** Excel writing logic partially untested
- **Recommendation:** Add tests for Excel generation, column mapping, edge cases

### **TestTotalXml** (33.3% coverage, 33 lines)
- **Purpose:** Parses Maven Surefire XML test reports
- **Test Gap:** XML parsing logic partially untested
- **Recommendation:** Add tests for various XML formats, malformed XML handling

### **ExecutionTimeAnalyzer** (21.4% coverage, 28 lines)
- **Purpose:** Analyzes execution time metrics
- **Test Gap:** Most analysis logic untested
- **Recommendation:** Add tests for time calculation, comparison logic

---

## 📋 Functional Area Breakdown

### By Module:

| Module | Classes <70% | Avg Coverage | Priority |
|--------|-------------|--------------|----------|
| Analysis & Reporting | 7 | 25.4% | 🔴 HIGH |
| Maven Execution | 9 | 28.1% | 🔴 HIGH |
| Coverage Calculation | 1 | 15.4% | 🟠 MEDIUM |
| Experiment Setup | 4 | 45.1% | 🟡 MEDIUM |
| Service Layer | 13 | 31.2% | 🔴 HIGH |
| Utilities | 2 | 66.6% | ✅ LOW |
| Model | 2 | 38.2% | 🟡 MEDIUM |

---

## ✅ Well-Tested Areas (>70% coverage)

These areas have good test coverage and serve as examples:

- **Pattern System**: OursPattern, TheirsPattern, CompoundPattern, PatternFactory (>80%)
- **Merge Processing**: MergeAnalyzer (95.6%), DatasetReader (100%)
- **Git Operations**: GitUtils (76.6%), RepositoryManager (78.2%)
- **Variant Building**: VariantBuildContext, ProjectBuilderUtils (>80%)
- **Core Model**: MergeInfo, VariantBuildContext, Project (>75%)

---

## 🎯 Recommended Testing Strategy

### Phase 1: Critical Path (Weeks 1-2)
1. **JacocoReportFinder** - Coverage report parsing is critical for experiments
2. **MavenCacheManager** - Cache failures could corrupt experiments
3. **CoverageCalculator** - Incorrect coverage means invalid results

### Phase 2: Analysis Layer (Weeks 3-4)
4. **VariantRankingAnalyzer** - Ranking logic determines best resolutions
5. **VariantResolutionAnalyzer** - Pattern effectiveness analysis
6. **StatisticsReporter** - Report generation for experiment results

### Phase 3: Execution Strategies (Week 5)
7. **CacheParallelStrategy** - Performance optimization validation
8. **CoverageStrategy** - Coverage collection verification
9. Deprecated runners - Either test or remove

### Phase 4: Integration (Week 6)
10. **CiCdMergeResolverApplication** - End-to-end application tests
11. **ExcelWriter** - Output validation
12. **ExecutionTimeAnalyzer** - Performance metrics

---

## 📈 Coverage Goals

### Short-term (1 month)
- Bring overall coverage from 62.2% to **75%**
- Zero classes with 0% coverage
- All classes >50 lines should have >50% coverage

### Long-term (3 months)
- Overall coverage **>85%**
- All critical paths >90% coverage
- All public APIs fully tested

---

## 🔧 How to Run Coverage Report

```bash
# Run tests with coverage
mvn clean test

# View report
open target/site/jacoco/index.html

# Or check CSV for programmatic analysis
cat target/site/jacoco/jacoco.csv
```

---

## 📚 Reference

- **JaCoCo Report:** `target/site/jacoco/index.html`
- **CSV Export:** `target/site/jacoco/jacoco.csv`
- **XML Export:** `target/site/jacoco/jacoco.xml`
