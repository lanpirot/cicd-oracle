package org.example.demo.threeWayMergeTool;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.merge.MergeData;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.vcsUtil.VcsUtil;
import git4idea.GitBranch;
import git4idea.commands.GitCommand;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitImpl;
import git4idea.commands.GitLineHandler;
import git4idea.merge.GitMergeUtil;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;


public class GitUtils {
    public static String getBranchCode(Project project, String branchName) {
        GitRepositoryManager repositoryManager = GitRepositoryManager.getInstance(project);
        GitRepository repository = repositoryManager.getRepositories().get(0);  // Берём первый репозиторий
        GitBranch branch = repository.getCurrentBranch();

        if (branch != null) {
            // Here we can implement logic to get code from the desired branch
            // For example, execute git checkout branchName and read the contents of the files
        }

        return branch.getName(); // Returns the contents of files from the desired branch
    }

    public static MergeContent getMergeContent(Project project) throws Exception {
        FileEditorManager fileEditorManager = FileEditorManager.getInstance(Objects.requireNonNull(project));
        VirtualFile currentFile = fileEditorManager.getSelectedFiles()[0];
        VirtualFile virtualFile = getGitRoot(project);
        MergeData mergeData = GitMergeUtil.loadMergeData(project, virtualFile, VcsUtil.getFilePath(currentFile), false);
        byte[] incomingVersion = mergeData.LAST;
        byte[] originalVersion = mergeData.ORIGINAL;
        byte[] currentVersion = mergeData.CURRENT;

        String currentText = new String(currentVersion, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String originalText = new String(originalVersion, StandardCharsets.UTF_8).replace("\r\n", "\n");
        ;
        String incomingText = new String(incomingVersion, StandardCharsets.UTF_8).replace("\r\n", "\n");
        ;

        return new MergeContent(originalText, currentText, incomingText);
    }

    public static VirtualFile getGitRoot(Project project) throws Exception {
        // Get the currently selected file
        FileEditorManager fileEditorManager = FileEditorManager.getInstance(Objects.requireNonNull(project));
        VirtualFile currentFile = fileEditorManager.getSelectedFiles()[0];

        // Get the root of the repository where the file is located
        GitRepositoryManager repositoryManager = GitRepositoryManager.getInstance(project);
        GitRepository repository = repositoryManager.getRepositoryForFileQuick(currentFile);

        if (repository == null) {
            throw new Exception("Could not find Git repository for file: " + currentFile.getPath());
        }

        VirtualFile root = repository.getRoot();
        return root;
    }

    private static void checkoutConflict(Project project, String type) {
        final FileEditorManager fileEditorManager = FileEditorManager.getInstance(Objects.requireNonNull(project));
        final VirtualFile currentFile = Objects.requireNonNull(fileEditorManager.getSelectedTextEditor()).getVirtualFile();


        GitRepositoryManager repositoryManager = GitRepositoryManager.getInstance(project);
        GitRepository repository = repositoryManager.getRepositoryForFileQuick(currentFile);

        assert repository != null;
        GitLineHandler handler = new GitLineHandler(project, repository.getRoot(), GitCommand.CHECKOUT);

        // Add command parameters
        handler.addParameters(type); // Flag to display base version in conflict
        handler.addParameters(currentFile.getPath());

        // execute the command
        GitImpl git = new GitImpl();
        GitCommandResult gitCommandResult = git.runCommand(handler);
        System.out.println(gitCommandResult.getErrorOutput());

        ApplicationManager.getApplication().invokeLater(() -> {
            ApplicationManager.getApplication().runWriteAction(() -> {
                VirtualFileManager.getInstance().syncRefresh();
                FileDocumentManager.getInstance().reloadFiles(currentFile);
            });
        });
        /*// Refresh VFS to reflect changes
        VirtualFileManager.getInstance().syncRefresh();

        // Reload files from disk
        FileDocumentManager.getInstance().reloadFiles(currentFile);*/
    }

    public static void checkoutWithDiff3Conflict(Project project) {
        checkoutConflict(project, "--conflict=diff3");
    }

    public static void checkoutWithMergeConflict(Project project) {
        checkoutConflict(project, "--conflict=merge");
    }

    private String detectConflictStyle(VirtualFile file) {
        try {
            // read the contents of the file as a string
            String content = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);

            // Check for the string ||||||| to determine the diff3 style
            if (content.contains("|||||||")) {
                return "diff3";
            } else if (content.contains("<<<<<<<") && content.contains("=======") && content.contains(">>>>>>>")) {
                return "merge";
            } else {
                return "no conflicts";
            }
        } catch (IOException e) {
            e.printStackTrace();
            return "error";
        }
    }
}

