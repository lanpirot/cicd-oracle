package org.example.demo.cicdMergeTool.service.projectRunners.maven;


import lombok.Getter;
import lombok.Setter;
import org.example.demo.cicdMergeTool.ui.ResolutionTableModel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Getter
@Setter
public class ResolutionResultDTO {
    private List<Variant> variants;
    private Consumer<ResolutionResultDTO.Variant> visitor;
    @Getter
    @Setter
    private static ResolutionTableModel resolutionTableModel;

    public ResolutionResultDTO() {
        this.variants = new ArrayList<>();
    }

    public void addVariant(Variant variant) {
        variant.setVisitor(visitor);
        variants.add(variant);
//        resolutionTableModel.addVariant(variant);
    }


    @Getter
    public static class Variant {
        private variantStatus status;
        private String name;
        private Path path;
        private CompilationResult compilationResult;
        private TestTotal testTotal;
        private ResolutionPatterns resolutionPatterns;

        public void setResolutionPatterns(Map<String, List<String>> conflictPatterns) {
            this.resolutionPatterns = new ResolutionPatterns(conflictPatterns);
            visitor.accept(this);
        }

        @Setter
        private Consumer<ResolutionResultDTO.Variant> visitor;

        public void setStatus(variantStatus status) {
            this.status = status;
//            resolutionTableModel.updateVariant(this);
           if(visitor!=null) visitor.accept(this);
        }

        public void setName(String name) {
            this.name = name;
        }

        public void setPath(Path path) {
            this.path = path;
//            resolutionTableModel.updateVariant(this);
            if(visitor!=null)visitor.accept(this);
        }

        public void setCompilationResult(CompilationResult compilationResult) {
            this.compilationResult = compilationResult;
//            resolutionTableModel.updateVariant(this);
            if(visitor!=null)visitor.accept(this);
        }

        public void setTestTotal(TestTotal testTotal) {
            this.testTotal = testTotal;
//            resolutionTableModel.updateVariant(this);
            if(visitor!=null)visitor.accept(this);
        }


        @Override
        public String toString() {
            return name;
        }

        @Getter
        public static class ResolutionPatterns {
            private final Map<String, List<String>> conflictPatterns;

            public ResolutionPatterns(Map<String, List<String>> conflictPatterns) {
                this.conflictPatterns = conflictPatterns;
            }

     /*       @Override
            public String toString() {
                return "ResolutionPatterns{" +
                        conflictPatterns +
                        '}';
            }*/

            @Override
            public String toString() {
                return "Resolution Patterns";
            }
        }
    }

    public static enum variantStatus {
        CREATING,
        CREATED,
        COMPILING,
        TESTING,
        ANALYZING,
        FINISHED
    }

    public Variant getVariantByName(String name) {
        return variants.stream().filter(variant -> variant.getName().equals(name)).findFirst().orElse(null);
    }


}
