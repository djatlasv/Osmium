package org.osmium.anticheat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.osmium.OsmiumConfig;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Offline chat filter using a configurable word list.
 * Persists blocked words to osmium-words.json in the server root.
 * Supports exact matching (case-insensitive) and regex patterns.
 */
public class OsmiumChatFilter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<List<String>>() {}.getType();

    private static File dataFile;
    private static final List<String> rawPatterns = new ArrayList<>();
    private static final List<Pattern> compiledPatterns = new ArrayList<>();

    public static void init(File serverDir) {
        dataFile = new File(serverDir, "osmium-words.json");
        load();
    }

    private static void load() {
        if (dataFile == null || !dataFile.exists()) {
            // Create default file with common filter patterns
            rawPatterns.addAll(List.of(
                "regex:n+[i!1|l]+[gq9]+[gq9]*[e3]*[ra@]*s?",
                "regex:f+[ua@]+[gq9]+[gq9]*[o0]*[t+]*s?",
                "regex:r+[e3]+[t+]+[a@]+r+[d]+s?",
                "regex:f+[u]+c+k+",
                "regex:s+h+[i!1]+t+",
                "regex:b+[i!1]+t+c+h+",
                "regex:a+s+s+h+o+l+e+",
                "regex:c+[u]+n+t+",
                "regex:d+[i!1]+c+k+",
                "regex:w+h+[o0]+r+e+",
                "regex:s+l+[u]+t+",
                "regex:k+[i!1]+k+e+s?",
                "regex:s+p+[i!1]+c+s?",
                "regex:c+h+[i!1]+n+k+s?",
                "regex:t+r+[a@]+n+n+[yi!1]+e?s?",
                "regex:d+y+k+e+s?",
                "regex:k+y+s+",
                "regex:k+[i!1]+l+l+\\s*(y+o+u+r+)?\\s*s+e+l+f+",
                "regex:g+[o0]+\\s*k+[i!1]+l+l+",
                "regex:n+[e3]+g+r+[o0]+s?",
                "regex:(?i)(?:[^a-z]|^)(n\\s*i\\s*g\\s*g\\s*[ae3]\\s*r?)(?:[^a-z]|$)",
                "regex:(?i)(?:[^a-z]|^)(f\\s*a\\s*g\\s*g?\\s*[o0]?\\s*t?)(?:[^a-z]|$)"
            ));
            save();
            rawPatterns.clear();
            compiledPatterns.clear();
            return;
        }

        try (FileReader reader = new FileReader(dataFile)) {
            List<String> loaded = GSON.fromJson(reader, DATA_TYPE);
            if (loaded != null) {
                rawPatterns.clear();
                compiledPatterns.clear();
                for (String entry : loaded) {
                    rawPatterns.add(entry);
                    compiledPatterns.add(compileEntry(entry));
                }
            }
        } catch (IOException ex) {
            Bukkit.getLogger().log(Level.WARNING, "Could not load osmium-words.json", ex);
        }
    }

    /**
     * Reloads the word list from disk. Called on config reload.
     */
    public static void reload() {
        rawPatterns.clear();
        compiledPatterns.clear();
        load();
    }

    private static void save() {
        if (dataFile == null) return;

        try (FileWriter writer = new FileWriter(dataFile)) {
            GSON.toJson(rawPatterns, DATA_TYPE, writer);
        } catch (IOException ex) {
            Bukkit.getLogger().log(Level.WARNING, "Could not save osmium-words.json", ex);
        }
    }

    /**
     * Compiles a word list entry into a regex Pattern.
     * Entries prefixed with "regex:" are treated as raw regex.
     * Plain entries are matched as case-insensitive word boundaries.
     */
    private static Pattern compileEntry(String entry) {
        if (entry.startsWith("regex:")) {
            String regex = entry.substring("regex:".length());
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        }
        // Plain word — match case-insensitive, surrounded by word boundaries
        return Pattern.compile("\\b" + Pattern.quote(entry) + "\\b", Pattern.CASE_INSENSITIVE);
    }

    /**
     * Checks if a message contains any blocked words/patterns.
     * Returns the first matching pattern string, or null if clean.
     */
    public static String check(String message) {
        if (!OsmiumConfig.chatFilterEnabled) return null;

        for (int i = 0; i < compiledPatterns.size(); i++) {
            if (compiledPatterns.get(i).matcher(message).find()) {
                return rawPatterns.get(i);
            }
        }
        return null;
    }

    /**
     * Returns the action to take when a filter match is found.
     * One of: "block", "kick", "mute"
     */
    public static String getAction() {
        return OsmiumConfig.chatFilterAction;
    }
}
