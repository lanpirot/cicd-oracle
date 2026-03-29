package ch.unibe.cs.mergeci.util;

import ch.unibe.cs.mergeci.config.AppConfig;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Thread-safe read-modify-write utility for a shared CSV of LaTeX variables.
 * Format: {@code name,value,description} (with header row).
 *
 * <p>Both Java pipelines and Python scripts use the same file
 * ({@link AppConfig#LATEX_VARIABLES_FILE}) so that a single CSV
 * can be loaded by the LaTeX {@code datatool} package.
 */
public class LatexVariableWriter {

    private static final Object LOCK = new Object();

    /** Upsert a single variable. */
    public static void put(String name, String value, String description) {
        putAll(Map.of(name, new String[]{value, description}));
    }

    /** Upsert a single variable (no description). */
    public static void put(String name, String value) {
        put(name, value, "");
    }

    /**
     * Batch upsert. Each map entry: name → [value, description].
     */
    public static void putAll(Map<String, String[]> entries) {
        synchronized (LOCK) {
            Path file = AppConfig.LATEX_VARIABLES_FILE;
            LinkedHashMap<String, String[]> existing = readCsv(file);
            existing.putAll(entries);
            writeCsv(file, existing);
        }
    }

    private static LinkedHashMap<String, String[]> readCsv(Path file) {
        LinkedHashMap<String, String[]> map = new LinkedHashMap<>();
        if (!Files.exists(file)) return map;
        try (BufferedReader br = Files.newBufferedReader(file)) {
            String header = br.readLine(); // skip header
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = parseCsvLine(line);
                if (parts.length >= 2) {
                    String desc = parts.length >= 3 ? parts[2] : "";
                    map.put(parts[0], new String[]{parts[1], desc});
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: could not read " + file + ": " + e.getMessage());
        }
        return map;
    }

    private static void writeCsv(Path file, LinkedHashMap<String, String[]> entries) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (BufferedWriter bw = Files.newBufferedWriter(tmp)) {
                bw.write("name,value,description");
                bw.newLine();
                for (Map.Entry<String, String[]> e : entries.entrySet()) {
                    bw.write(csvField(e.getKey()));
                    bw.write(',');
                    bw.write(csvField(e.getValue()[0]));
                    bw.write(',');
                    bw.write(csvField(e.getValue().length > 1 ? e.getValue()[1] : ""));
                    bw.newLine();
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("Warning: could not write " + file + ": " + e.getMessage());
        }
    }

    /** Quote a CSV field if it contains comma, quote, or newline. */
    private static String csvField(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /** Parse a single CSV line respecting quoted fields. */
    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    sb.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        fields.add(sb.toString());
        return fields.toArray(new String[0]);
    }
}
