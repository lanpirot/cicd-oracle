package ch.unibe.cs.mergeci.experimentSetup;

import ch.unibe.cs.mergeci.experimentSetup.evaluationCollection.AllMergesJSON;
import ch.unibe.cs.mergeci.experimentSetup.evaluationCollection.MergeOutputJSON;
import ch.unibe.cs.mergeci.service.projectRunners.maven.TestTotal;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MetricsAnalyzer {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ArrayList<MergeOutputJSON> merges = new ArrayList<>();

    public MetricsAnalyzer(File dir) {
        List<MergeOutputJSON> allMerges = new ArrayList<>();

        for (File file : dir.listFiles()) {
            try {
                AllMergesJSON allMergesJSON = objectMapper.readValue(file, AllMergesJSON.class);

                merges.addAll(allMergesJSON.getMerges());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        System.out.println("All Merges: " + merges.size());
        System.out.printf("Num of MultiModules: %d %d%% %n", countMultiModulesProjects(), countPercent(merges.size(), countMultiModulesProjects()));

        List<MergeOutputJSON> filteredMerges = filterNoImpactMerges(merges);

        System.out.printf("Filtered Merges: %d %d%% %n", filteredMerges.size(), countPercent(merges.size(), filteredMerges.size()));

        System.out.printf("At least one resolution %d %d%% %n",
                atLeastOneResolution(filteredMerges).size(),
                countPercent(filteredMerges.size(),
                        atLeastOneResolution(filteredMerges).size()));

        System.out.printf("Merges that have resolution better than original: %d %d%% %n",
                numberOfResolutionsThatPerformBetter(filteredMerges).size(),
                countPercent(filteredMerges.size(),
                        numberOfResolutionsThatPerformBetter(filteredMerges).size()));

        List<String> patterns = absolutePatternResolution(filteredMerges);
        System.out.printf("Absolute patterns: %d %d%% %n",
                absolutePatternResolution(filteredMerges).size(),
                countPercent(filteredMerges.size(),
                        absolutePatternResolution(filteredMerges).size()));

        groupPattern(patterns).forEach((k, v) -> System.out.printf("%s : %f %% %n", k, (float) v * 100 / patterns.size()));

        System.out.printf("Ratio of execution time between merge and variance: %f %n", ratioInExecutionTime(filteredMerges));
        System.out.println(ratioInExecutionTimeDistribution(filteredMerges));

    }

    public int countMultiModulesProjects() {
        return (int) merges.stream().filter(x -> x.getIsMultiModule()).count();
    }

    public List<MergeOutputJSON> filterNoImpactMerges(List<MergeOutputJSON> merges) {
        List<MergeOutputJSON> mergedMerges = new ArrayList<>();

        for (MergeOutputJSON merge : merges) {
            int numberOfSucceedTests = numberOfSuccessTest(merge.getTestResults());

            for (MergeOutputJSON.Variant variant : merge.getVariantsExecution().getVariants()) {
                int numberOfSucceedTestsInVariant = numberOfSuccessTest(variant.getTestResults());

                if (numberOfSucceedTestsInVariant != numberOfSucceedTests) {
                    mergedMerges.add(merge);
                    break;
                }
            }
        }

        return mergedMerges;
    }

    public static double ratioInExecutionTime(List<MergeOutputJSON> merges) {
        List<Double> ratios = new ArrayList<>();
        for (MergeOutputJSON merge : merges) {
            double ratio = merge.getVariantsExecution().getExecutionTimeSeconds() / merge.getCompilationResult().getTotalTime();
            ratios.add(ratio);
        }

        return ratios.stream().mapToDouble(x -> x).average().orElse(0.0);
    }

    public static Map<Integer, Double> ratioInExecutionTimeDistribution(List<MergeOutputJSON> merges) {
        Map<Integer, List<MergeOutputJSON>> map = new HashMap<>();
        for (MergeOutputJSON merge : merges) {
            map.putIfAbsent(merge.getNumConflictChunks(), new ArrayList<>());
            map.get(merge.getNumConflictChunks()).add(merge);
        }

        Map<Integer, Double> ratios = new HashMap<>();

        map.forEach((k, v) -> {
            ratios.put(k, ratioInExecutionTime(v));
        });

        return ratios;
    }

    public static List<MergeOutputJSON> atLeastOneResolution(List<MergeOutputJSON> merges) {
        List<MergeOutputJSON> mergedMerges = new ArrayList<>();

        for (MergeOutputJSON merge : merges) {
            int numberOfSucceedTests = numberOfSuccessTest(merge.getTestResults());

            for (MergeOutputJSON.Variant variant : merge.getVariantsExecution().getVariants()) {
                int numberOfSucceedTestsInVariant = numberOfSuccessTest(variant.getTestResults());

                if (numberOfSucceedTests <= numberOfSucceedTestsInVariant) {
                    mergedMerges.add(merge);
                    break;
                }
            }
        }

        return mergedMerges;
    }

    public static List<String> absolutePatternResolution(List<MergeOutputJSON> merges) {
        List<String> patternList = new ArrayList<>();

        for (MergeOutputJSON merge : merges) {
            int numberOfSucceedTests = numberOfSuccessTest(merge.getTestResults());

            for (MergeOutputJSON.Variant variant : merge.getVariantsExecution().getVariants()) {
                int numberOfSucceedTestsInVariant = numberOfSuccessTest(variant.getTestResults());

                if (numberOfSucceedTests <= numberOfSucceedTestsInVariant) {

                    List<String> patterns = variant.getConflictPatterns().values().stream().flatMap(Collection::stream).toList();

                    Set<String> patternSet = new HashSet<>(patterns);

                    if (patternSet.size() == 1) {
                        patternList.addAll(patternSet);
                    }
                }
            }
        }

        return patternList;
    }

    public HashMap<String, Integer> groupPattern(List<String> patterns) {
        HashMap<String, Integer> groupedPattern = new HashMap<>();

        for (String pattern : patterns) {
            groupedPattern.put(pattern, groupedPattern.getOrDefault(pattern, 0) + 1);
        }

        return groupedPattern;
    }


    public static List<MergeOutputJSON> numberOfResolutionsThatPerformBetter(List<MergeOutputJSON> merges) {
        List<MergeOutputJSON> mergedMerges = new ArrayList<>();

        for (MergeOutputJSON merge : merges) {
            int numberOfSucceedTests = numberOfSuccessTest(merge.getTestResults());

            for (MergeOutputJSON.Variant variant : merge.getVariantsExecution().getVariants()) {
                int numberOfSucceedTestsInVariant = numberOfSuccessTest(variant.getTestResults());

                if (numberOfSucceedTests < numberOfSucceedTestsInVariant) {
                    mergedMerges.add(merge);
                    break;
                }
            }
        }

        return mergedMerges;
    }

    private static int numberOfSuccessTest(TestTotal testTotal) {
        return testTotal.getRunNum() - testTotal.getErrorsNum() - testTotal.getFailuresNum();
    }

    private static int countPercent(int whole, int part) {
        return part * 100 / whole;
    }


}
