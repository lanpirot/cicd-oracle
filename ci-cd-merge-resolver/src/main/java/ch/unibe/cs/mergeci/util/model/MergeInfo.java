package ch.unibe.cs.mergeci.util.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.eclipse.jgit.revwalk.RevCommit;

import java.util.Map;

@Getter
@Setter
@RequiredArgsConstructor
public class MergeInfo {
    private final RevCommit resultedMergeCommit;
    private final RevCommit commit1;
    private final RevCommit commit2;
    private final Map<String, Integer> conflictingFiles;


    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Merge commit: ").append(resultedMergeCommit.getName())
                .append(" (parents: ").append(commit1.getName()).append(", ").append(commit2.getName()).append(")\n");
        for(Map.Entry<String, Integer> entry: conflictingFiles.entrySet()){
            sb.append("\t").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }
}
