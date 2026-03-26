package ch.unibe.cs.mergeci.conflict;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.experiment.DatasetReader;
import ch.unibe.cs.mergeci.model.ConflictBlock;
import ch.unibe.cs.mergeci.model.ConflictFile;
import ch.unibe.cs.mergeci.model.IMergeBlock;
import ch.unibe.cs.mergeci.model.NonConflictBlock;
import ch.unibe.cs.mergeci.model.patterns.PatternFactory;
import ch.unibe.cs.mergeci.runner.VariantBuildContext;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import ch.unibe.cs.mergeci.util.GitUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeResult;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves human / tentative / variant file triplets to disk during variant experiment analysis
 * so merges can be inspected later without re-cloning.
 *
 * For each processed merge one directory is written:
 *   <CONFLICT_FILES_DIR>/<projectName>_<mergeCommit[:8]>/
 *     meta.json
 *     <sanitized_file_path>/
 *       human.<ext>      – merge-commit-resolved file (from JGit object store)
 *       tentative.<ext>  – diff3-style replay with <<<<<<</||||||/======/>>>>>>> markers
 *       variant.<ext>    – best variant's pattern-applied resolution
 *       chunks.json      – per-conflict line ranges in all three files
 *
 * Files larger than SIZE_THRESHOLD_BYTES are windowed: only ±WINDOW_LINES lines around
 * each conflict chunk are kept; gaps are replaced with a single omission placeholder.
 */
public class ConflictFileSaver {

    private static final int SIZE_THRESHOLD_BYTES = 10 * 1024; // 10 kB
    private static final int WINDOW_LINES = 100;
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    // ── public API ──────────────────────────────────────────────────────────

    public static void save(
            VariantBuildContext context,
            Map<String, TestTotal> testResults,
            Map<String, CompilationResult> compilationResults,
            DatasetReader.MergeInfo info,
            Path outputDir) {

        String projectName = context.getProjectName();
        BestVariant best = findBestVariant(projectName, testResults, compilationResults,
                context.getConflictPatterns());
        if (best == null) return;

        String dirName = projectName + "_"
                + info.getMergeCommit().substring(0, AppConfig.HASH_PREFIX_LENGTH);
        Path caseDir = outputDir.resolve(dirName);

        try {
            Files.createDirectories(caseDir);
            Git git = GitUtils.getGit(context.getRepositoryPath());
            List<String> savedFiles = new ArrayList<>();

            for (Map.Entry<String, ConflictFile> entry : context.getConflictFileMap().entrySet()) {
                String filePath = entry.getKey();
                ConflictFile conflictFile = entry.getValue();

                ObjectId blobId = context.getMergeCommitObjects().get(filePath);
                if (blobId == null) continue;
                String humanContent = new String(
                        git.getRepository().open(blobId).getBytes(), StandardCharsets.UTF_8);

                // Build tentative with diff3 markers and variant with pattern resolution
                List<TentativeChunk> tentativeChunks = new ArrayList<>();
                List<String> tentativeLines = buildTentativeLines(conflictFile, tentativeChunks);

                List<String> bestPatterns = best.patternsForFile(filePath);
                List<int[]> variantRanges = new ArrayList<>();
                List<String> variantLines = buildVariantLines(conflictFile, bestPatterns, variantRanges);

                List<String> humanLines = splitLines(humanContent);
                List<int[]> humanRanges = computeHumanRanges(conflictFile, humanLines);

                // Apply windowing for large files
                boolean needsWindow = humanContent.length() > SIZE_THRESHOLD_BYTES
                        || tentativeLines.size() * 80L > SIZE_THRESHOLD_BYTES;

                List<int[]> tentativeWindows =
                        computeWindowsFromChunks(tentativeChunks, tentativeLines.size());
                List<int[]> humanWindows =
                        computeWindowsFromRanges(humanRanges, humanLines.size());
                List<int[]> variantWindows =
                        computeWindowsFromRanges(variantRanges, variantLines.size());

                List<String> finalTentative, finalHuman, finalVariant;
                List<TentativeChunk> finalTChunks;
                List<int[]> finalHRanges, finalVRanges;

                if (needsWindow) {
                    finalTentative = applyWindowing(tentativeLines, tentativeWindows);
                    finalHuman     = applyWindowing(humanLines,     humanWindows);
                    finalVariant   = applyWindowing(variantLines,   variantWindows);
                    finalTChunks   = remapTentativeChunks(tentativeChunks, tentativeWindows);
                    finalHRanges   = remapRanges(humanRanges,   humanWindows);
                    finalVRanges   = remapRanges(variantRanges, variantWindows);
                } else {
                    finalTentative = tentativeLines;
                    finalHuman     = humanLines;
                    finalVariant   = variantLines;
                    finalTChunks   = tentativeChunks;
                    finalHRanges   = humanRanges;
                    finalVRanges   = variantRanges;
                }

                String sanitized = sanitizePath(filePath);
                Path fileDir = caseDir.resolve(sanitized);
                Files.createDirectories(fileDir);
                String ext = getExtension(filePath);

                Files.writeString(fileDir.resolve("human"     + ext), String.join("\n", finalHuman));
                Files.writeString(fileDir.resolve("tentative" + ext), String.join("\n", finalTentative));
                Files.writeString(fileDir.resolve("variant"   + ext), String.join("\n", finalVariant));

                writeChunksJson(fileDir, finalTChunks, finalHRanges, finalVRanges, bestPatterns,
                        tentativeChunks, humanRanges, variantRanges);
                savedFiles.add(filePath);
            }

            writeMetaJson(caseDir, projectName, info, best, testResults, compilationResults,
                    savedFiles, git, context.getTotalChunks());

        } catch (Exception e) {
            System.err.printf("  [ConflictFileSaver] Failed for %s: %s%n",
                    info.getMergeCommit().substring(0, AppConfig.HASH_PREFIX_LENGTH),
                    e.getMessage());
        }
    }

    // ── internal records ────────────────────────────────────────────────────

    private record TentativeChunk(
            int markerOurs,
            int oursStart,  int oursEnd,
            int markerBase,
            int baseStart,  int baseEnd,
            int markerSep,
            int theirsStart, int theirsEnd,
            int markerEnd) {}

    private record BestVariant(
            int variantIndex,
            int passedTests,
            boolean compiles,
            List<Map<String, List<String>>> allConflictPatterns) {

        List<String> patternsForFile(String filePath) {
            if (variantIndex < 1 || allConflictPatterns == null) return List.of();
            // conflictPatterns is 0-indexed; variant 1 → index 0
            int idx = variantIndex - 1;
            if (idx >= allConflictPatterns.size()) return List.of();
            Map<String, List<String>> fileMap = allConflictPatterns.get(idx);
            return fileMap != null ? fileMap.getOrDefault(filePath, List.of()) : List.of();
        }
    }

    // ── find best variant ────────────────────────────────────────────────────

    private static BestVariant findBestVariant(
            String projectName,
            Map<String, TestTotal> testResults,
            Map<String, CompilationResult> compilationResults,
            List<Map<String, List<String>>> conflictPatterns) {

        int bestIdx = -1;
        int bestPassed = -1;
        boolean bestCompiles = false;

        for (String key : testResults.keySet()) {
            if (key.equals(projectName)) continue; // skip human baseline
            int idx;
            try {
                idx = Integer.parseInt(key.substring(key.lastIndexOf('_') + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            TestTotal tt = testResults.get(key);
            CompilationResult cr = compilationResults.get(key);
            int passed = tt != null
                    ? tt.getRunNum() - tt.getFailuresNum() - tt.getErrorsNum() : 0;
            boolean compiles = cr != null
                    && cr.getBuildStatus() == CompilationResult.Status.SUCCESS;

            boolean better = bestIdx < 0
                    || passed > bestPassed
                    || (passed == bestPassed && compiles && !bestCompiles)
                    || (passed == bestPassed && compiles == bestCompiles && idx < bestIdx);

            if (better) {
                bestIdx     = idx;
                bestPassed  = passed;
                bestCompiles = compiles;
            }
        }

        if (bestIdx < 0) return null;
        return new BestVariant(bestIdx, bestPassed, bestCompiles, conflictPatterns);
    }

    // ── build tentative (diff3 markers) ─────────────────────────────────────

    private static List<String> buildTentativeLines(
            ConflictFile cf, List<TentativeChunk> chunks) {

        List<String> lines = new ArrayList<>();
        int lineNum = 1;

        for (IMergeBlock block : cf.getMergeBlocks()) {
            if (block instanceof NonConflictBlock) {
                for (String line : block.getLines()) {
                    lines.add(line);
                    lineNum++;
                }
            } else if (block instanceof ConflictBlock cb) {
                int markerOurs = lineNum; lines.add("<<<<<<< OURS");   lineNum++;
                List<String> ours = getChunkLines(cb, CheckoutCommand.Stage.OURS);
                int oursStart = lineNum;
                lines.addAll(ours); lineNum += ours.size();
                int oursEnd = lineNum - 1;

                int markerBase = lineNum; lines.add("||||||| BASE");   lineNum++;
                List<String> base = getChunkLines(cb, CheckoutCommand.Stage.BASE);
                int baseStart = lineNum;
                lines.addAll(base); lineNum += base.size();
                int baseEnd = lineNum - 1;

                int markerSep = lineNum; lines.add("=======");         lineNum++;
                List<String> theirs = getChunkLines(cb, CheckoutCommand.Stage.THEIRS);
                int theirsStart = lineNum;
                lines.addAll(theirs); lineNum += theirs.size();
                int theirsEnd = lineNum - 1;

                int markerEnd = lineNum; lines.add(">>>>>>> THEIRS");  lineNum++;

                chunks.add(new TentativeChunk(
                        markerOurs, oursStart, oursEnd,
                        markerBase, baseStart, baseEnd,
                        markerSep,  theirsStart, theirsEnd,
                        markerEnd));
            }
        }
        return lines;
    }

    // ── build variant (pattern-applied) ─────────────────────────────────────

    private static List<String> buildVariantLines(
            ConflictFile cf, List<String> patterns, List<int[]> ranges) {

        List<String> lines = new ArrayList<>();
        int lineNum = 1;
        int chunkIdx = 0;

        for (IMergeBlock block : cf.getMergeBlocks()) {
            if (block instanceof NonConflictBlock) {
                for (String line : block.getLines()) {
                    lines.add(line);
                    lineNum++;
                }
            } else if (block instanceof ConflictBlock cb) {
                String patternName = chunkIdx < patterns.size() ? patterns.get(chunkIdx) : "OURS";
                ConflictBlock clone = cb.clone();
                clone.setPattern(PatternFactory.fromName(patternName));
                int start = lineNum;
                for (String line : clone.getLines()) {
                    lines.add(line);
                    lineNum++;
                }
                ranges.add(new int[]{start, lineNum - 1});
                chunkIdx++;
            }
        }
        return lines;
    }

    // ── compute human chunk ranges ───────────────────────────────────────────

    private static List<int[]> computeHumanRanges(
            ConflictFile cf, List<String> humanLines) {

        List<int[]> ranges = new ArrayList<>();
        List<IMergeBlock> blocks = cf.getMergeBlocks();
        int humanPos = 0; // 0-indexed current position

        for (int i = 0; i < blocks.size(); i++) {
            IMergeBlock block = blocks.get(i);
            if (block instanceof NonConflictBlock ncb) {
                humanPos += ncb.getLines().size();
            } else if (block instanceof ConflictBlock) {
                int start1 = humanPos + 1; // 1-indexed
                // Anchor: first line of the next non-conflict block
                String anchor = findNextNonConflictFirstLine(blocks, i + 1);
                int end1;
                if (anchor != null) {
                    int found = -1;
                    for (int k = humanPos; k < humanLines.size(); k++) {
                        if (humanLines.get(k).equals(anchor)) {
                            found = k;
                            break;
                        }
                    }
                    if (found >= 0) {
                        // Resolution occupies lines humanPos+1 .. found (1-indexed)
                        end1 = found; // 0-indexed found → 1-indexed = found+1 - 1 = found
                        humanPos = found;
                    } else {
                        end1 = humanLines.size();
                        humanPos = humanLines.size();
                    }
                } else {
                    end1 = humanLines.size();
                    humanPos = humanLines.size();
                }
                ranges.add(new int[]{start1, end1});
            }
        }
        return ranges;
    }

    private static String findNextNonConflictFirstLine(
            List<IMergeBlock> blocks, int fromIndex) {
        for (int i = fromIndex; i < blocks.size(); i++) {
            if (blocks.get(i) instanceof NonConflictBlock ncb) {
                List<String> lines = ncb.getLines();
                if (!lines.isEmpty()) return lines.get(0);
            }
        }
        return null;
    }

    // ── windowing ────────────────────────────────────────────────────────────

    private static List<int[]> computeWindowsFromChunks(
            List<TentativeChunk> chunks, int totalLines) {
        if (chunks.isEmpty()) return List.of(new int[]{1, totalLines});
        List<int[]> raw = new ArrayList<>();
        for (TentativeChunk c : chunks) {
            raw.add(new int[]{
                    Math.max(1, c.markerOurs() - WINDOW_LINES),
                    Math.min(totalLines, c.markerEnd() + WINDOW_LINES)
            });
        }
        return mergeWindows(raw, totalLines);
    }

    private static List<int[]> computeWindowsFromRanges(
            List<int[]> ranges, int totalLines) {
        if (ranges.isEmpty()) return List.of(new int[]{1, totalLines});
        List<int[]> raw = new ArrayList<>();
        for (int[] r : ranges) {
            raw.add(new int[]{
                    Math.max(1, r[0] - WINDOW_LINES),
                    Math.min(totalLines, r[1] + WINDOW_LINES)
            });
        }
        return mergeWindows(raw, totalLines);
    }

    private static List<int[]> mergeWindows(List<int[]> raw, int totalLines) {
        if (raw.isEmpty()) return List.of();
        List<int[]> sorted = new ArrayList<>(raw);
        sorted.sort(Comparator.comparingInt(a -> a[0]));
        List<int[]> merged = new ArrayList<>();
        int[] cur = sorted.get(0).clone();
        for (int i = 1; i < sorted.size(); i++) {
            int[] next = sorted.get(i);
            if (next[0] <= cur[1] + 1) {
                cur[1] = Math.max(cur[1], next[1]);
            } else {
                merged.add(cur);
                cur = next.clone();
            }
        }
        merged.add(cur);
        return merged;
    }

    private static List<String> applyWindowing(List<String> lines, List<int[]> windows) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < windows.size(); i++) {
            if (i > 0) {
                int gap = windows.get(i)[0] - windows.get(i - 1)[1] - 1;
                if (gap > 0) result.add("// ... " + gap + " lines omitted ...");
            }
            int[] w = windows.get(i);
            for (int j = w[0]; j <= w[1] && j <= lines.size(); j++) {
                result.add(lines.get(j - 1));
            }
        }
        return result;
    }

    private static List<TentativeChunk> remapTentativeChunks(
            List<TentativeChunk> chunks, List<int[]> windows) {
        List<TentativeChunk> out = new ArrayList<>();
        for (TentativeChunk c : chunks) {
            out.add(new TentativeChunk(
                    remap(c.markerOurs(),   windows),
                    remap(c.oursStart(),    windows), remap(c.oursEnd(),    windows),
                    remap(c.markerBase(),   windows),
                    remap(c.baseStart(),    windows), remap(c.baseEnd(),    windows),
                    remap(c.markerSep(),    windows),
                    remap(c.theirsStart(),  windows), remap(c.theirsEnd(),  windows),
                    remap(c.markerEnd(),    windows)));
        }
        return out;
    }

    private static List<int[]> remapRanges(List<int[]> ranges, List<int[]> windows) {
        List<int[]> out = new ArrayList<>();
        for (int[] r : ranges) {
            out.add(new int[]{remap(r[0], windows), remap(r[1], windows)});
        }
        return out;
    }

    /** Map a 1-indexed original line to its 1-indexed position in the windowed content. */
    private static int remap(int originalLine, List<int[]> windows) {
        int windowed = 0;
        for (int i = 0; i < windows.size(); i++) {
            if (i > 0) windowed++; // placeholder line between windows
            int[] w = windows.get(i);
            if (originalLine >= w[0] && originalLine <= w[1]) {
                return windowed + (originalLine - w[0] + 1);
            }
            windowed += w[1] - w[0] + 1;
        }
        return originalLine; // fallback
    }

    // ── JSON output ──────────────────────────────────────────────────────────

    private static void writeChunksJson(
            Path dir,
            List<TentativeChunk> tChunks, List<int[]> hRanges, List<int[]> vRanges,
            List<String> patterns,
            List<TentativeChunk> origTChunks, List<int[]> origHRanges, List<int[]> origVRanges)
            throws IOException {

        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < tChunks.size(); i++) {
            TentativeChunk tc  = tChunks.get(i);
            TentativeChunk otc = i < origTChunks.size() ? origTChunks.get(i) : tc;

            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("pattern", i < patterns.size() ? patterns.get(i) : "OURS");

            Map<String, Object> tent = new LinkedHashMap<>();
            tent.put("markerOurs",   tc.markerOurs());
            tent.put("oursStart",    tc.oursStart());   tent.put("oursEnd",    tc.oursEnd());
            tent.put("markerBase",   tc.markerBase());
            tent.put("baseStart",    tc.baseStart());   tent.put("baseEnd",    tc.baseEnd());
            tent.put("markerSep",    tc.markerSep());
            tent.put("theirsStart",  tc.theirsStart()); tent.put("theirsEnd",  tc.theirsEnd());
            tent.put("markerEnd",    tc.markerEnd());
            tent.put("originalLine", otc.markerOurs());
            chunk.put("tentative", tent);

            if (i < hRanges.size()) {
                Map<String, Object> hm = new LinkedHashMap<>();
                hm.put("start", hRanges.get(i)[0]);
                hm.put("end",   hRanges.get(i)[1]);
                hm.put("originalLine",
                        i < origHRanges.size() ? origHRanges.get(i)[0] : hRanges.get(i)[0]);
                chunk.put("human", hm);
            }

            if (i < vRanges.size()) {
                Map<String, Object> vm = new LinkedHashMap<>();
                vm.put("start", vRanges.get(i)[0]);
                vm.put("end",   vRanges.get(i)[1]);
                vm.put("originalLine",
                        i < origVRanges.size() ? origVRanges.get(i)[0] : vRanges.get(i)[0]);
                chunk.put("variant", vm);
            }

            out.add(chunk);
        }
        MAPPER.writeValue(dir.resolve("chunks.json").toFile(), out);
    }

    private static void writeMetaJson(
            Path caseDir,
            String projectName,
            DatasetReader.MergeInfo info,
            BestVariant best,
            Map<String, TestTotal> testResults,
            Map<String, CompilationResult> compilationResults,
            List<String> savedFiles,
            Git git,
            int numConflictChunks) throws IOException {

        TestTotal humanTT = testResults.get(projectName);
        CompilationResult humanCR = compilationResults.get(projectName);
        int humanPassed = humanTT != null
                ? humanTT.getRunNum() - humanTT.getFailuresNum() - humanTT.getErrorsNum() : 0;
        boolean humanCompiles = humanCR != null
                && humanCR.getBuildStatus() == CompilationResult.Status.SUCCESS;

        String repoUrl = git.getRepository().getConfig().getString("remote", "origin", "url");
        String commitDate = resolveCommitDate(git, info.getMergeCommit());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id",                  projectName + "_"
                + info.getMergeCommit().substring(0, AppConfig.HASH_PREFIX_LENGTH));
        meta.put("projectName",         projectName);
        meta.put("repoUrl",             repoUrl);
        meta.put("mergeCommit",         info.getMergeCommit());
        meta.put("parent1",             info.getParent1());
        meta.put("parent2",             info.getParent2());
        meta.put("commitDate",          commitDate);
        meta.put("numConflictFiles",    info.getNumConflictFiles());
        meta.put("numConflictChunks",   numConflictChunks);
        meta.put("hasTestConflict",     info.isHasTestConflict());
        meta.put("baselineBroken",      info.isBaselineBroken());
        meta.put("variantIndex",        best.variantIndex());
        meta.put("humanPassedTests",    humanPassed);
        meta.put("variantPassedTests",  best.passedTests());
        meta.put("humanCompiles",       humanCompiles);
        meta.put("variantCompiles",     best.compiles());
        meta.put("files",               savedFiles);

        MAPPER.writeValue(caseDir.resolve("meta.json").toFile(), meta);
    }

    private static String resolveCommitDate(Git git, String commitHash) {
        try (RevWalk rw = new RevWalk(git.getRepository())) {
            ObjectId oid = git.getRepository().resolve(commitHash);
            if (oid == null) return null;
            RevCommit rc = rw.parseCommit(oid);
            return Instant.ofEpochSecond(rc.getCommitTime()).toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ── utilities ────────────────────────────────────────────────────────────

    private static List<String> getChunkLines(ConflictBlock cb, CheckoutCommand.Stage stage) {
        MergeChunk chunk = cb.getChunks().get(stage);
        if (chunk == null) return List.of();
        @SuppressWarnings("unchecked")
        MergeResult<RawText> mr = (MergeResult<RawText>) cb.getMergeResult();
        RawText raw = mr.getSequences().get(chunk.getSequenceIndex());
        List<String> lines = new ArrayList<>();
        for (int i = chunk.getBegin(); i < chunk.getEnd(); i++) {
            lines.add(raw.getString(i));
        }
        return lines;
    }

    private static List<String> splitLines(String content) {
        if (content.isEmpty()) return new ArrayList<>();
        String[] arr = content.split("\n", -1);
        // Drop trailing empty element produced when content ends with \n
        int len = (arr.length > 0 && arr[arr.length - 1].isEmpty())
                ? arr.length - 1 : arr.length;
        List<String> lines = new ArrayList<>(len);
        for (int i = 0; i < len; i++) lines.add(arr[i]);
        return lines;
    }

    private static String sanitizePath(String filePath) {
        return filePath.replace("/", "__").replace("\\", "__").replace(":", "_");
    }

    private static String getExtension(String filePath) {
        int dot = filePath.lastIndexOf('.');
        return dot >= 0 ? filePath.substring(dot) : "";
    }
}
