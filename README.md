# CI/CD-Enhanced Conflict Resolution

This repository contains a research-oriented toolchain for **automated merge conflict resolution**
with **CI/CD-driven validation**.

The project explores heuristic-based conflict resolution strategies and evaluates merge variants
using compilation and test results collected automatically from build pipelines.

---

## Repository Structure

This repository consists of three main components:

### 🔁 CI/CD Conflict Resolution Core
Core logic for resolve merge conflicts

📄 Documentation:  
[README – CI/CD Core](ci-cd-merge-resolver/README.md)

---

### 📦 Maven Plugin
Extends Maven build, test lifecycles for cache plugin

📄 Documentation:  
[README – Maven Plugin](maven-hook/README.md)

---

### 🔌 IntelliJ IDEA Plugin
Provides a graphical interface for running conflict resolution

📄 Documentation:  
[README – IntelliJ IDEA Plugin](demo/README.md)


---

## Research Context

This project investigates:
- Variant explosion during merge conflict resolution
- Heuristic pruning strategies
- CI/CD-based validation of merge outcomes
- Automated detection of invalid or empty resolutions

---


# CI/CD-Enhanced Conflict Resolution – Team Discussion Notes

# 24/09/2025

---
## Current Problems

- Surefire reports folder is not deleted during recompilation. (Done via maven-hook plugin)

- Heuristic approach is a possible solution to the problem of the explosion of variants.

- Resolved file that ends up empty after resolution is still being saved in the project. 
(Not a problem for finding optimal solution)

## Possible Heuristic approach workflow:
1. At the beginning, build the project using only the dependencies from our branch.

2. Iterate through every conflict chunk in sequence.

3. For each chunk:
   -  Replace the conflict with the "theirs" version.
   -  Check whether the change leads to any improvement (e.g., successful build or more passing tests).
   -  If the change improves the result &rarr; accept it and move on to the next chunk.
   -  If not → keep the previous state and continue to the next chunk.

## Pros & Cons of Heuristic approach
\+ Efficient search for optimal merge `O(logN)`

\+ Incremental improvement

\+ Adaptive decision making

\- Linear conflict processing

\- Limited parallelism

\- Possible local optimum trap (Linear traversal through conflicts may get stuck in a locally optimal solution 
without finding the globally best one.)

---
# 10/10/2025

---
## Does Not Work: Discard

- No Maven
- No tests
- Too hard to get to run (e.g., missing database, requires Java 6, or other configuration issues)
- No merge conflicts

## Does Not Work: Our Approach Won’t Work

- Tests don’t cover the conflict region

## Does Not Work: Our Approach Won’t Work Completely

- Tests don’t cover the conflict region → maybe compilation can still help with ranking?

## Does Not Work Out of the Box

- Too many conflicts &rarr; can we deal with that?

---

### Suggestion of a Minor Refinement

In the first place, what you would observe is just that there are **no differences in the test results** across the different conflict resolution variants that you generated.

- “Tests don’t cover conflict region” is already one possible explanation for not seeing differences.
- But there might be others — for example, even if a test touches the conflict region, **test results across variants might still be the same**.

---
# 24/09/2025

---

## Plugin Feedback 
- The plugin should provide feedback on the **user-chosen resolution**.

---

## Experiment Setup
Given a list of repositories with tests (e.g., Java repositories using Maven):

1. **Replay merges**.
2. For merges with **conflicts**:
    - Generate different **resolution types**.
    - Compile each resolution.
    - Test each resolution.
    - Compare results to **ground truth**.

---

## Evaluation Questions
- **How many resolutions are “good”?**
- **Are some resolutions better than the ground truth?**

---

## Measurements
- **Time measurement**
    - When is the approach not viable?
    - How to deal with search space explosion?
- **Resolution strategy**
    - Which resolution types to include, depending on number of conflict chunks?
- **Optimization**
    - Does iterative compiling/testing mitigate costs?
    - Can we use a clever order of testing to reduce overhead?
- **Special cases**
    - Is a **partial merge** viable?

---

## Research Questions
- **RQ1:** How many of the generated resolutions are “good”?  
  How many are **better than the ground truth**?

- **RQ2:** How can we deal with the **explosion of the search space**?

---

## Integration
- **Cherry:** Integrate the approach into the already existing plugin.
