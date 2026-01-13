package org.example.demo.threeWayMergeTool;

import com.intellij.execution.testframework.AbstractTestProxy;
import com.intellij.execution.testframework.TestStatusListener;
import org.jetbrains.annotations.Nullable;

public class TestListener extends TestStatusListener  {
    @Override
    public void testSuiteFinished(@Nullable AbstractTestProxy root) {
        System.out.println("Tests finished");
    }
/*@Override
    public void testSuiteFinished(@Nullable AbstractTestProxy root) {
        System.out.println("Suite finished");
    }*/
}
