package org.example.demo.cicdMergeTool.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class MergeStateListener {
    public static void register(Project project, Runnable onChange) {
/*        project.getMessageBus().connect(project).subscribe(ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED, (VcsListener) () -> {
            System.out.printf("VCS has been changed%n");
            onChange.run();
        });*/
        MessageBusConnection connection = project.getMessageBus().connect();

        connection.subscribe(
                VirtualFileManager.VFS_CHANGES,
                new BulkFileListener() {
                    @Override
                    public void after(@NotNull List<? extends VFileEvent> events) {
                        for (VFileEvent e : events) {
                            String path = e.getPath();
                            if (path != null && path.contains("/.git/MERGE_HEAD")) {
                                onChange.run();
                                break;
                            }
                        }
                    }
                }
        );
    }
}
