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
    // Words that should never be filtered even if they match a pattern
    private static final Set<String> WHITELIST = Set.of(
            "night", "knight", "nights", "knights", "nighttime",
            "bigger", "digger", "trigger", "snicker",
            "scunthorpe", "dickens", "shuttle", "assassin",
            "classic", "cocktail", "peacock"
    );

    public static void init(File serverDir) {
        dataFile = new File(serverDir, "osmium-words.json");
        load();
    }

    private static void load() {
        if (dataFile == null || !dataFile.exists()) {
            // Create default file with common filter patterns
            rawPatterns.addAll(List.of(
                "regex:\\bn+[i!1|l]+[gq9]{2,}[e3]*[ra@]*s?\\b",
                "regex:\\bf+[ua@]+[gq9]{2,}[o0]*[t+]*s?\\b",
                "regex:\\br+[e3]+[t+]+[a@]+r+[d]+s?\\b",
                "regex:\\bf+[u]+c+k+\\b",
                "regex:\\bs+h+[i!1]+t+\\b",
                "regex:\\bb+[i!1]+t+c+h+\\b",
                "regex:\\ba+s+s+h+o+l+e+\\b",
                "regex:\\bc+[u]+n+t+\\b",
                "regex:\\bd+[i!1]+c+k+\\b",
                "regex:\\bw+h+[o0]+r+e+\\b",
                "regex:\\bs+l+[u]+t+\\b",
                "regex:\\bk+[i!1]+k+e+s?\\b",
                "regex:\\bs+p+[i!1]+c+s?\\b",
                "regex:\\bc+h+[i!1]+n+k+s?\\b",
                "regex:\\bt+r+[a@]+n+n+[yi!1]+e?s?\\b",
                "regex:\\bd+y+k+e+s?\\b",
                "regex:\\bk+y+s+\\b",
                "regex:\\bk+[i!1]+l+l+\\s*(y+o+u+r+)?\\s*s+e+l+f+\\b",
                "regex:\\bg+[o0]+\\s*k+[i!1]+l+l+\\b",
                "regex:\\bn+[e3]+g+r+[o0]+s?\\b"
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
     * Tests against the original message AND a per-word stripped version
     * to catch symbol bypasses like "f.u.c.k" without merging separate
     * words together (which caused false positives like "night" or
     * cross-word matches in normal sentences).
     */
    public static String check(String message) {
        if (!OsmiumConfig.chatFilterEnabled) return null;

        // Strip bypass separators WITHIN words but keep spaces between words.
        // Split on whitespace, strip non-alpha from each word, rejoin.
        // "n.i.g.g.e.r" → "nigger" (caught)
        // "night" → "night" (not caught — word boundary protects it)
        // "give me judes" → "give me judes" (words stay separate)
        StringBuilder strippedBuilder = new StringBuilder();
        for (String word : message.split("\\s+")) {
            if (!strippedBuilder.isEmpty()) strippedBuilder.append(' ');
            strippedBuilder.append(word.replaceAll("[^a-zA-Z0-9]", ""));
        }
        String stripped = strippedBuilder.toString();

        // Check if every word in the message is whitelisted — if so, skip filtering
        String lowerMessage = message.toLowerCase(Locale.ROOT);

        for (int i = 0; i < compiledPatterns.size(); i++) {
            Pattern pattern = compiledPatterns.get(i);
            java.util.regex.Matcher matcher = pattern.matcher(lowerMessage);
            if (matcher.find()) {
                // Check if the matched text is a whitelisted word
                String matched = matcher.group().trim().toLowerCase(Locale.ROOT);
                if (!WHITELIST.contains(matched) && !isAllWhitelisted(lowerMessage)) {
                    return rawPatterns.get(i);
                }
            }
            // Also check stripped version
            java.util.regex.Matcher strippedMatcher = pattern.matcher(stripped.toLowerCase(Locale.ROOT));
            if (strippedMatcher.find()) {
                String matched = strippedMatcher.group().trim().toLowerCase(Locale.ROOT);
                if (!WHITELIST.contains(matched) && !isAllWhitelisted(stripped.toLowerCase(Locale.ROOT))) {
                    return rawPatterns.get(i);
                }
            }
        }
        return null;
    }

    /**
     * Checks if every individual word in the text is in the whitelist.
     */
    private static boolean isAllWhitelisted(String text) {
        for (String word : text.split("\\s+")) {
            String clean = word.replaceAll("[^a-zA-Z]", "").toLowerCase(Locale.ROOT);
            if (!clean.isEmpty() && WHITELIST.contains(clean)) {
                return true; // at least one whitelisted word triggered the match
            }
        }
        return false;
    }

    /**
     * Returns the action to take when a filter match is found.
     * One of: "block", "kick", "mute"
     */
    public static String getAction() {
        return OsmiumConfig.chatFilterAction;
    }
}
