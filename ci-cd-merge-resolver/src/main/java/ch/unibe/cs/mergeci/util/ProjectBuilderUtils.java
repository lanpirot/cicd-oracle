package ch.unibe.cs.mergeci.util;

import ch.unibe.cs.mergeci.model.Project;
import ch.unibe.cs.mergeci.model.ProjectClass;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeResult;

import ch.unibe.cs.mergeci.model.ConflictBlock;
import ch.unibe.cs.mergeci.model.IMergeBlock;
import ch.unibe.cs.mergeci.model.NonConflictBlock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class ProjectBuilderUtils {
    private Path gitRootPath;
    private Path temp_path;

    public ProjectBuilderUtils(Path gitRootPath, Path temp_path) {
        this.gitRootPath = gitRootPath;
        this.temp_path = temp_path;
    }

    public void saveProjects(List<Project> projects, Map<String, ObjectId> nonConflictObjects) throws IOException {
        int index = 0;

        for (Project project : projects) {
            Path projectNewRootPath = temp_path.resolve(gitRootPath.getFileName().getFileName() + "_" + index);

            Git git = GitUtils.getGit(gitRootPath);
            FileUtils.saveFilesFromObjectId(projectNewRootPath, nonConflictObjects, git);
            for (ProjectClass projectClass : project.getClasses()) {

                File filepath = projectNewRootPath.resolve(projectClass.getClassPath().toString()).toFile();

                if (filepath.getParentFile() != null) filepath.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(filepath)) {
                    out.write(projectClass.toString().getBytes());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            index++;
        }
    }

    public static ProjectClass getProjectClass(MergeResult<? extends Sequence> mergeResult, String classPath) {
        ProjectClass projectClass = new ProjectClass();
        projectClass.setClassPath(Paths.get(classPath));
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
