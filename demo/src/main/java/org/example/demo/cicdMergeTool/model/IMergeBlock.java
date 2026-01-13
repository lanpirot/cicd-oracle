package org.example.demo.cicdMergeTool.model;

import java.util.List;

public interface IMergeBlock extends Cloneable {
    List<String> getLines();
    IMergeBlock clone() throws CloneNotSupportedException;
}
