# CI/CD-Enhanced Conflict Resolution – Team Discussion Notes

# 10/09/2025

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

- Too many conflicts → can we deal with that?

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
