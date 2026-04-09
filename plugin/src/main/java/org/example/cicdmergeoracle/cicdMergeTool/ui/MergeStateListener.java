package org.example.cicdmergeoracle.cicdMergeTool.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class MergeStateListener {
    public static void register(Project project, Runnable onChange) {
        MessageBusConnection connection = project.getMessageBus().connect();

        // React to VFS detecting MERGE_HEAD changes
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
