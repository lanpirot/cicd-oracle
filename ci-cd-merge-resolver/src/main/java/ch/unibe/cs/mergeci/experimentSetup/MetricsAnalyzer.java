package ch.unibe.cs.mergeci.experimentSetup;

import ch.unibe.cs.mergeci.experimentSetup.evaluationCollection.AllMergesJSON;
import ch.unibe.cs.mergeci.experimentSetup.evaluationCollection.MergeOutputJSON;
import ch.unibe.cs.mergeci.service.projectRunners.maven.JacocoReportFinder;
import ch.unibe.cs.mergeci.service.projectRunners.maven.TestTotal;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Getter
public class MetricsAnalyzer {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private List<MergeOutputJSON> merges = new ArrayList<>();
    private final Path dir;

    public MetricsAnalyzer(Path dir) {
        this.dir = dir;
        List<MergeOutputJSON> allMerges = new ArrayList<>();

        for (File file : dir.toFile().listFiles()) {
            try {
                AllMergesJSON allMergesJSON = objectMapper.readValue(file, AllMergesJSON.class);
                merges.addAll(allMergesJSON.getMerges());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void makeFullAnalysis() {

        System.out.println("All Merges: " + merges.size());
        printfIfNonZero("Num of MultiModules: %d %d%% %n", merges.size(), countMultiModulesProjects(), countPercent(merges.size(), countMultiModulesProjects()));

        List<MergeOutputJSON> impactMerges = filterOutNoImpactMerges(merges);
        List<MergeOutputJSON> noImpactMerges = merges.stream().filter(x -> !impactMerges.contains(x)).toList();
        System.out.println("No Impact Merges: " + noImpactMerges.size());
        printfIfNonZero("Impact Merges: %d %d%% %n", merges.size(), impactMerges.size(), countPercent(merges.size(), impactMerges.size()));
        List<MergeOutputJSON> noImpactMergesSingleModule = getSingleModuleProjects(noImpactMerges);
        List<MergeOutputJSON> noImpactMergesMultiModule = getMultiModuleProjects(noImpactMerges);
        printfIfNonZero("No Impact Single module merges %d %d%% %n", merges.size(), noImpactMergesSingleModule.size(), countPercent(merges.size(), noImpactMergesSingleModule.size()));
        printfIfNonZero("No Impact Multi module merges %d %d%% %n", merges.size(), noImpactMergesMultiModule.size(), countPercent(merges.size(), noImpactMergesMultiModule.size()));
        List<MergeOutputJSON> noImpactMergesTotalCoverage = getMergesWithCoverage(noImpactMerges);
        List<MergeOutputJSON> noImpactMergesSingleModuleCoverage = getMergesWithCoverage(noImpactMergesSingleModule);
        List<MergeOutputJSON> noImpactMergesMultiModuleCoverage = getMergesWithCoverage(noImpactMergesMultiModule);
        System.out.printf("Coverage no impact total merges %f %% %n", noImpactMergesTotalCoverage.stream()
                .mapToDouble(x -> x.getCoverage().lineCoverage()).average().orElse(0.0));
        System.out.printf("Coverage no impact single module merges %f %% %n", noImpactMergesSingleModuleCoverage.stream()
                .mapToDouble(x -> x.getCoverage().lineCoverage()).average().orElse(0.0));
        System.out.printf("Coverage no impact multi module merges %f %% %n", noImpactMergesMultiModuleCoverage.stream()
                .mapToDouble(x -> x.getCoverage().lineCoverage()).average().orElse(0.0));


        List<MergeOutputJSON> impactMergesSingleModule = getSingleModuleProjects(impactMerges);
        List<MergeOutputJSON> impactMergesMultiModule = getMultiModuleProjects(impactMerges);
        List<MergeOutputJSON> impactMergesSingleModuleCoverage = getMergesWithCoverage(impactMergesSingleModule);
        List<MergeOutputJSON> impactMergesMultiModuleCoverage = getMergesWithCoverage(impactMergesMultiModule);
        printfIfNonZero("impact Single module merges %d %d%% %n", impactMerges.size(), impactMergesSingleModule.size(), countPercent(impactMerges.size(), impactMergesSingleModule.size()));
        printfIfNonZero("impact Multi module merges %d %d%% %n", impactMerges.size(), impactMergesMultiModule.size(), countPercent(impactMerges.size(), impactMergesMultiModule.size()));
        System.out.printf("Coverage  impact single module merges %f %% %n", impactMergesSingleModuleCoverage.stream()
                .mapToDouble(x -> x.getCoverage().lineCoverage()).average().orElse(0.0));
        System.out.printf("Coverage  impact multi module merges %f %% %n", impactMergesMultiModuleCoverage.stream()
                .mapToDouble(x -> x.getCoverage().lineCoverage()).average().orElse(0.0));

        List<MergeOutputJSON> atLeastOneResolutionTotal = atLeastOneResolution(impactMergesSingleModule);
        printfIfNonZero("At least one resolution %d/%d %d%% %n",
                impactMerges.size(),
                atLeastOneResolution(impactMerges).size(),
                impactMerges.size(),
                countPercent(impactMerges.size(),
                        atLeastOneResolution(impactMerges).size()));

        if (!impactMerges.isEmpty() && !impactMergesSingleModule.isEmpty()) {
            System.out.printf("At least one resolution in single Module %d %d%% just by single module: %d %% %n",
                    atLeastOneResolution(impactMergesSingleModule).size(),
                    countPercent(impactMerges.size(),
                            atLeastOneResolution(impactMergesSingleModule).size()),
                    countPercent(impactMergesSingleModule.size(),
                            atLeastOneResolution(impactMergesSingleModule).size()));
        }


        if (!impactMerges.isEmpty() && !impactMergesMultiModule.isEmpty()) {
            System.out.printf("At least one resolution in multi Module %d %d%%; just by multi module: %d %% %n",
                        atLeastOneResolution(impactMergesMultiModule).size(),
                        countPercent(impactMerges.size(),
                                atLeastOneResolution(impactMergesMultiModule).size()),
                        countPercent(impactMergesMultiModule.size(),
                                atLeastOneResolution(impactMergesMultiModule).size()));
        }

        printfIfNonZero("Merges that have resolution better than original: %d %d%% %n",
                impactMerges.size(),
                numberOfResolutionsThatPerformBetter(impactMerges).size(),
                countPercent(impactMerges.size(),
                        numberOfResolutionsThatPerformBetter(impactMerges).size()));

        List<String> patterns = uniformPatternResolution(impactMerges);
        printfIfNonZero("Uniform patterns: %d %d%% %n",
                atLeastOneResolution(impactMerges).size(),
                uniformPatternResolution(atLeastOneResolution(impactMerges)).size(),
                countPercent(atLeastOneResolution(impactMerges).size(),
                        uniformPatternResolution(atLeastOneResolution(impactMerges)).size()));

        List<String> singeModulePattern = uniformPatternResolution(impactMergesSingleModule);
        printfIfNonZero("Uniform patterns for single module: %d %d%% %n",
                atLeastOneResolution(impactMergesSingleModule).size(),
                uniformPatternResolution(atLeastOneResolution(impactMergesSingleModule)).size(),
                countPercent(atLeastOneResolution(impactMergesSingleModule).size(),
                        uniformPatternResolution(atLeastOneResolution(impactMergesSingleModule)).size()));

        List<String> multiModulePattern = uniformPatternResolution(impactMergesMultiModule);
        printfIfNonZero("Uniform patterns for multi module: %d %d%% %n",
                impactMergesMultiModule.size(),
                uniformPatternResolution(impactMergesSingleModule).size(),
                countPercent(impactMergesMultiModule.size(),
                        uniformPatternResolution(impactMergesMultiModule).size()));

        if (!patterns.isEmpty()) {
            groupPattern(patterns).forEach((k, v) -> System.out.printf("%s : %f %% %n", k, (float) v * 100 / patterns.size()));
        }

        System.out.println("\n\nRanking");
        Map<String, Integer> ranking = gerRanking(impactMerges).entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
        for (Map.Entry<String, Integer> entry : ranking.entrySet()) {
            if (!impactMerges.isEmpty()) {
                System.out.printf("%s :%d/%d %.2f %% %n",
                        entry.getKey(),
                        entry.getValue(),
                        impactMerges.size(),
                        (float) entry.getValue() * 100 / impactMerges.size());
            }
        }
        ;


        System.out.printf("Ratio of execution time between merge and variance: %f %n", ratioInExecutionTime(impactMerges));
        System.out.println(ratioInExecutionTimeDistribution(impactMerges));

        System.out.printf("Merges with coverage: %d %n", getMergesWithCoverage(impactMerges).size());

        rankByCoverage(impactMerges);

        System.out.println("\n");
        rankByNumberOfConflictChunks(impactMerges);
    }

    public int countMultiModulesProjects() {
        return (int) merges.stream().filter(x -> x.getIsMultiModule()).count();
    }

    public List<MergeOutputJSON> filterOutNoImpactMerges(List<MergeOutputJSON> merges) {
        List<MergeOutputJSON> mergedMerges = new ArrayList<>();

        for (MergeOutputJSON merge : merges) {
            if (merge.getNumConflictChunks() == 0) continue;
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

    public static List<String> uniformPatternResolution(List<MergeOutputJSON> merges) {
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

    private HashMap<String, Integer> groupPattern(List<String> patterns) {
        HashMap<String, Integer> groupedPattern = new HashMap<>();

        for (String pattern : patterns) {
            groupedPattern.put(pattern, groupedPattern.getOrDefault(pattern, 0) + 1);
        }

        return groupedPattern;
    }


    private static List<MergeOutputJSON> numberOfResolutionsThatPerformBetter(List<MergeOutputJSON> merges) {
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
        if (whole == 0)
            return 0;
        return part * 100 / whole;
    }

    /**
     * Prints formatted output only if the denominator is not zero.
     * This prevents division by zero errors in countPercent calls.
     *
     * @param format the format string
     * @param denominator the denominator to check (if 0, printing is skipped)
     * @param args the arguments for the format string
     */
    private static void printfIfNonZero(String format, int denominator, Object... args) {
        if (denominator != 0) {
            System.out.printf(format, args);
        }
    }

    public static List<MergeOutputJSON> getMultiModuleProjects(List<MergeOutputJSON> merges) {
        return merges.stream().filter(x -> x.getIsMultiModule()).toList();
    }

    public static List<MergeOutputJSON> getSingleModuleProjects(List<MergeOutputJSON> merges) {
        return merges.stream().filter(x -> !x.getIsMultiModule()).toList();
    }


    private static List<MergeOutputJSON> getMergesWithCoverage(List<MergeOutputJSON> merges) {
        return merges.stream()
                .peek(x -> {
                    if (x.getCoverage() == null) {
                        x.setCoverage(new JacocoReportFinder.CoverageDTO(Float.NaN, Float.NaN)); // Assign N/A
                    }
                })
                .toList();
    }

    private static Map<String, Integer> gerRanking(List<MergeOutputJSON> merges) {
        int numberOfOursBestResolutions = 0;
        int numberOfTheirsBestResolutions = 0;
        int numberOfMixedResolutions = 0;
        int numberOfHumanBestResolutions = 0;
        int numberAtLeastOneResolution = 0;

        for (MergeOutputJSON merge : merges) {
            int best = numberOfSuccessTest(merge.getTestResults());
            List<String> bestResolution = new ArrayList<>(List.of("Human"));

            for (MergeOutputJSON.Variant variant : merge.getVariantsExecution().getVariants()) {
                int variantBest = numberOfSuccessTest(variant.getTestResults());
                if (variantBest > best) {
                    best = variantBest;
                    String pattern = whichUniformPatternResolution(variant.getConflictPatterns());

                    bestResolution = new ArrayList<>(List.of());
                    bestResolution.add(pattern);
                } else if (variantBest == best) {
                    String pattern = whichUniformPatternResolution(variant.getConflictPatterns());
                    if (!bestResolution.contains(pattern)) {
                        bestResolution.add(pattern);
                    }
                }
            }

            for (String resolution : bestResolution) {
                switch (resolution) {
                    case "Human":
                        numberOfHumanBestResolutions++;
                        break;
                    case "OursPattern":
                        numberOfOursBestResolutions++;
                        break;
                    case "TheirsPattern":
                        numberOfTheirsBestResolutions++;
                        break;
                    case "Mixed":
                        numberOfMixedResolutions++;
                        break;
                    default:
                }
            }

            if (bestResolution.stream().anyMatch(x -> !x.equals("Human"))) { //TODO: this seems highly smelly to test equality not >= or >
                numberAtLeastOneResolution++;
            }


        }
        Map<String, Integer> ranking = new TreeMap<>();
        ranking.put("Human", numberOfHumanBestResolutions);
        ranking.put("Ours", numberOfOursBestResolutions);
        ranking.put("Theirs", numberOfTheirsBestResolutions);
        ranking.put("Mixed", numberOfMixedResolutions);
        ranking.put("AtLeastOneNonHuman", numberAtLeastOneResolution);

        return ranking;

    }

    /**
     * @return return name of unifrom pattern resolution or "Mixed" is case of mixed resolutions
     */
    private static String whichUniformPatternResolution(Map<String, List<String>> patterns) {
        Set<String> patternSet = new HashSet<>(patterns.values().stream().flatMap(Collection::stream).toList());

        if (patternSet.size() == 1) {
            return patternSet.iterator().next();
        } else {
            return "Mixed";
        }
    }

    private static void rankByCoverage(List<MergeOutputJSON> merges) {
        List<Double> coverageThreshold = List.of(0.0, 0.15, 0.4, 0.6, 1.0);

        for (int i = 0; i < coverageThreshold.size() - 1; i++) {
            double min = coverageThreshold.get(i);
            double max = coverageThreshold.get(i + 1);

            List<MergeOutputJSON> filteredMerges = merges.stream()
                    .filter(x -> x.getCoverage().lineCoverage() >= min
                            && x.getCoverage().lineCoverage() < max).toList();

            Map<String, Integer> ranking = gerRanking(filteredMerges).entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));

            System.out.printf("%nThreshold: [%.2f, %.2f) %n", min, max);
            for (Map.Entry<String, Integer> entry : ranking.entrySet()) {
                if (!filteredMerges.isEmpty()) {
                    System.out.printf("\t %s :%d/%d %.2f %% %n",
                            entry.getKey(),
                            entry.getValue(),
                            filteredMerges.size(),
                            (float) entry.getValue() * 100 / filteredMerges.size());
                }
            }
        }
    }

    private static void rankByNumberOfConflictChunks(List<MergeOutputJSON> merges) {
        Map<Integer, List<MergeOutputJSON>> groupedByNumberOfConflictChunks = merges.stream()
                .collect(Collectors.groupingBy(MergeOutputJSON::getNumConflictChunks));

        for (Map.Entry<Integer, List<MergeOutputJSON>> groupedByConflictEntry : groupedByNumberOfConflictChunks.entrySet()) {

            List<MergeOutputJSON> mergeOutputJSONS = groupedByConflictEntry.getValue();

            Map<String, Integer> ranking = gerRanking(mergeOutputJSONS).entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));

            System.out.printf("%n Number of conflict chunks: %d %n", groupedByConflictEntry.getKey());
            for (Map.Entry<String, Integer> entry : ranking.entrySet()) {
                if (!mergeOutputJSONS.isEmpty()) {
                    System.out.printf("\t %s: %d/%d %.2f %% %n",
                            entry.getKey(),
                            entry.getValue(),
                            mergeOutputJSONS.size(),
                            (float) entry.getValue() * 100 / mergeOutputJSONS.size());
                }
            }
        }
    }

}
