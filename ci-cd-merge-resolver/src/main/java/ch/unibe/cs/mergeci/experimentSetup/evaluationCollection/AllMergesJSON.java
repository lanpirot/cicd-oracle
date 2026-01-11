package ch.unibe.cs.mergeci.experimentSetup.evaluationCollection;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class AllMergesJSON {
    private String projectName;
    private String repoUrl;
    private List<MergeOutputJSON> merges;
}
