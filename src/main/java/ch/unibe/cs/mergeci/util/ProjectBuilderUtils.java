package ch.unibe.cs.mergeci.util;

import ch.unibe.cs.mergeci.model.ConflictBlock;
import ch.unibe.cs.mergeci.model.IMergeBlock;
import ch.unibe.cs.mergeci.model.NonConflictBlock;
import ch.unibe.cs.mergeci.model.Project;
import ch.unibe.cs.mergeci.model.ProjectClass;
import ch.unibe.cs.mergeci.model.patterns.IPattern;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeResult;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ProjectBuilderUtils {
    private String gitRootPath;
    private String TEMP_PATH = "temp";

    public ProjectBuilderUtils(String gitRootPath) {
        this.gitRootPath = gitRootPath;
    }

    public void saveProjects(List<Project> projects) {
        int index = 0;
        for (Project project : projects) {

            for (ProjectClass projectClass : project.getClasses()) {
                String filepath = TEMP_PATH + File.separator + projectClass.getProjectName().getParent() + "_"
                        + index + projectClass.getProjectName().getFileName();
                File file = new File(filepath);
                if (file.getParentFile() != null) file.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(file)) {
                    out.write(projectClass.toString().getBytes());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            index++;
        }
    }


    public List<Project> getProjects(Map<String, List<ProjectClass>> projectClassMap) {
        List<Project> projectList = new ArrayList<>();
        getProjectsRecursive(projectClassMap, projectClassMap.keySet().stream().toList(), new ArrayList<>(), projectList, 0);
        return projectList;
    }

    public void getProjectsRecursive(Map<String, List<ProjectClass>> projectClassMap, List<String> keys, List<ProjectClass> projectsPrevious, List<Project> result, int index) {


        if (projectClassMap.size() == index) {
            Project project = new Project();
            project.setProjectName(Paths.get(this.gitRootPath));
            project.setClasses(projectsPrevious);
            result.add(project);
            return;
        }
        String key = keys.get(index);

        List<ProjectClass> projectClasses = projectClassMap.get(key);


        for (ProjectClass projectClass : projectClasses) {
            List<ProjectClass> projects = new ArrayList<>(projectsPrevious);
            projects.add(projectClass);
            getProjectsRecursive(projectClassMap, keys, projects, result, index + 1);
        }
    }

    public static Map<String, List<ProjectClass>> getAllPossibleConflictResolution(Map<String, ProjectClass> projectClassMap, List<IPattern> patterns) {
        Map<String, List<ProjectClass>> map = new HashMap<>();
        for (Map.Entry<String, ProjectClass> entry : projectClassMap.entrySet()) {
            List<ProjectClass> projectClasses = getAllPossibleConflictResolution(entry.getValue(), patterns);
            map.put(entry.getKey(), projectClasses);
        }

        return map;
    }

    public static List<ProjectClass> getAllPossibleConflictResolution(ProjectClass projectClass, List<IPattern> patterns) {
        List<List<IMergeBlock>> resolvedMergedConflicts = new ArrayList<>();
        resolveConflicts(projectClass.getMergeBlocks(), new ArrayList<>(), resolvedMergedConflicts, patterns, 0);

        List<ProjectClass> projectClasses = new ArrayList<>();
        for (List<IMergeBlock> mergeBlocks : resolvedMergedConflicts) {
            ProjectClass projectClassResolved = new ProjectClass();
            projectClassResolved.setProjectName(projectClass.getProjectName());
            projectClassResolved.setMergeBlocks(mergeBlocks);
            projectClasses.add(projectClassResolved);
        }
        return projectClasses;
    }

    public static void resolveConflicts(List<IMergeBlock> original, List<IMergeBlock> previous, List<List<IMergeBlock>> general, List<IPattern> patterns, int counter) {

        if (counter == original.size() - 1) {
            general.add(previous);
            return;
        }

        IMergeBlock currentBlock = original.get(counter);
        if (currentBlock instanceof ConflictBlock) {
            for (IPattern pattern : patterns) {
                List<IMergeBlock> currentList = new ArrayList<>(previous);
                ConflictBlock conflictBlock = ((ConflictBlock) currentBlock).clone();
                conflictBlock.setPattern(pattern);
                currentList.add(conflictBlock);
                resolveConflicts(original, currentList, general, patterns, counter + 1);
            }
        } else if (currentBlock instanceof NonConflictBlock) {
            previous.add(currentBlock);
            resolveConflicts(original, previous, general, patterns, counter + 1);
        }
    }

    public static ProjectClass getProjectClass(MergeResult<? extends Sequence> mergeResult, String classPath) {
        ProjectClass projectClass = new ProjectClass();
        projectClass.setProjectName(Paths.get(classPath));
        List<IMergeBlock> mergeBlockList = new ArrayList<>();
        for (Iterator<MergeChunk> i = mergeResult.iterator(); i.hasNext(); ) {
            MergeChunk mergeChunk = i.next();
            if (mergeChunk.getConflictState() == MergeChunk.ConflictState.NO_CONFLICT) {
                NonConflictBlock nonConflictBlock = new NonConflictBlock((MergeResult<RawText>) mergeResult, mergeChunk);
                mergeBlockList.add(nonConflictBlock);
            } else {
                Map<CheckoutCommand.Stage, MergeChunk> chunks = new HashMap<>();
                chunks.put(CheckoutCommand.Stage.OURS, mergeChunk);
                chunks.put(CheckoutCommand.Stage.BASE, i.next());
                chunks.put(CheckoutCommand.Stage.THEIRS, i.next());
                ConflictBlock conflictBlock = new ConflictBlock((MergeResult<RawText>) mergeResult, chunks);
                mergeBlockList.add(conflictBlock);
            }
        }
        projectClass.setMergeBlocks(mergeBlockList);
        return projectClass;
    }
}
