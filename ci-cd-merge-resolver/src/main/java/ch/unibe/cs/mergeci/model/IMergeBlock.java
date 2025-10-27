package ch.unibe.cs.mergeci.model;

import java.util.List;

public interface IMergeBlock extends Cloneable {
    List<String> getLines();
    IMergeBlock clone() throws CloneNotSupportedException;
}
