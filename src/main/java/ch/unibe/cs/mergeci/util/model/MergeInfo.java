package ch.unibe.cs.mergeci.util.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@RequiredArgsConstructor
public class MergeInfo {
    private final String objectId1;
    private final String objectId2;
    private final Map<String, Integer> conflictingFiles;


    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(" ").append(objectId1).append(" ").append(objectId2).append("\n");
        for(Map.Entry<String, Integer> entry: conflictingFiles.entrySet()){
            sb.append("\t").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }


}
