package ch.unibe.cs.mergeci;

import ch.unibe.cs.mergeci.util.GitUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

@SpringBootApplication
public class CiCdMergeResolverApplication {

    public static void main(String[] args) throws IOException, GitAPIException {
        SpringApplication.run(CiCdMergeResolverApplication.class, args);
        File project = new File("src/test/resources/test-merge-projects/myTest");
        GitUtils gitUtils = new GitUtils(project);
        gitUtils.getConflictChunks("","");


    }

}
