package com.macemanager.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public final class ConfigStore {
    private ConfigStore() {
    }

    public static void replace(java.io.File file, String key, String value) throws IOException {
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        boolean replaced = false;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int comment = line.indexOf('#');
            String code = comment >= 0 ? line.substring(0, comment) : line;
            int keyAt = code.indexOf(key);
            if (keyAt < 0 || !code.substring(0, keyAt).trim().isEmpty()) {
                continue;
            }
            lines.set(index, code.substring(0, keyAt) + key + ": " + value);
            replaced = true;
            break;
        }
        if (!replaced) {
            lines.add(key + ": " + value);
        }
        Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
    }
}
