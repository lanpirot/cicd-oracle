package ch.unibe.cs.mergeci.runner;

import java.nio.file.Path;
import java.util.List;

public interface IRunner {
   RunExecutionTIme run(Path mainProject,
                        List<Path> variants,
                        Boolean useMvnDaemon);
}
