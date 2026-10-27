package com.macemanager.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

public final class ConfigStore {
    private ConfigStore() {
    }

    public static void replace(java.io.File file, String key, String value) throws IOException {
        replace(file, Map.of(key, value));
    }

    public static void replace(java.io.File file, Map<String, String> values) throws IOException {
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        values.forEach((path, value) -> replace(lines, path.split("\\."), 0, 0, lines.size(), -1, value));
        Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
    }

    private static void replace(List<String> lines, String[] path, int depth,
                                int start, int end, int parentIndent, String value) {
        int childIndent = -1;
        for (int index = start; index < end; index++) {
            String line = lines.get(index);
            String code = line.stripLeading();
            if (code.isBlank() || code.startsWith("#")) {
                continue;
            }
            int indent = line.length() - code.length();
            if (childIndent < 0) {
                childIndent = indent;
            }
            if (indent != childIndent || !code.startsWith(path[depth] + ":")) {
                continue;
            }
            if (depth == path.length - 1) {
                lines.set(index, " ".repeat(indent) + path[depth] + ": " + value);
                return;
            }
            int sectionEnd = index + 1;
            while (sectionEnd < end) {
                String next = lines.get(sectionEnd);
                String stripped = next.stripLeading();
                if (!stripped.isBlank() && !stripped.startsWith("#")
                        && next.length() - stripped.length() <= indent) {
                    break;
                }
                sectionEnd++;
            }
            replace(lines, path, depth + 1, index + 1, sectionEnd, indent, value);
            return;
        }
        int indent = childIndent >= 0 ? childIndent : parentIndent < 0 ? 0 : parentIndent + 2;
        for (int part = depth; part < path.length; part++) {
            lines.add(end++, " ".repeat(indent) + path[part] + ":"
                    + (part == path.length - 1 ? " " + value : ""));
            indent += 2;
        }
    }
}
