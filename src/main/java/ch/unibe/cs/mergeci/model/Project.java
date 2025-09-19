package ch.unibe.cs.mergeci.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class Project implements Cloneable {
    private String projectName;
    private List<ProjectClass> classes;


    @Override
    public Project clone() {
        try {
            Project clone = (Project) super.clone();
            List<ProjectClass> clonedClasses = new ArrayList<>();
            for (ProjectClass projectClass : classes) {
                clonedClasses.add(projectClass.clone());
            }
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }
}
