# CI/CD-Enhanced Conflict Resolution – Team Discussion Notes



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
