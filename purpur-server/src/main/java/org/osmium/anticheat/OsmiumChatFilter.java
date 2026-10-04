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
    static final List<String> rawPatterns = new ArrayList<>();
    static final List<Pattern> compiledPatterns = new ArrayList<>();
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
            rawPatterns.clear();
            compiledPatterns.clear();
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
            // Keep the defaults active in memory too — clearing the lists
            // here left the filter dead until the next config reload.
            compiledPatterns.clear();
            for (String entry : rawPatterns) {
                compiledPatterns.add(compileEntry(entry));
            }
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
     * Cheat clients evade pattern matching with lookalike characters that a
     * human reads as ASCII but the word-stripper deletes ("nіgger" with a
     * Cyrillic і, "ｆｕｃｋ" fullwidth). Normalize before matching: NFKC folds
     * fullwidth/compatibility forms, the map folds the common
     * Cyrillic/Greek lookalikes onto their ASCII letters. Anything else
     * non-ASCII still gets stripped downstream, as before.
     */
    static String normalize(String message) {
        String lowered = message.toLowerCase(Locale.ROOT);
        String nfkc = java.text.Normalizer.normalize(lowered, java.text.Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(nfkc.length());
        for (int i = 0; i < nfkc.length(); i++) {
            char c = nfkc.charAt(i);
            Character replacement = LOOKALIKES.get(c);
            sb.append(replacement != null ? replacement.charValue() : c);
        }
        return sb.toString();
    }

    private static final Map<Character, Character> LOOKALIKES = buildLookalikes();

    private static Map<Character, Character> buildLookalikes() {
        Map<Character, Character> map = new HashMap<>();
        // input is already lowercased, so only lowercase lookalikes matter
        map.put('а', 'a'); map.put('е', 'e'); map.put('о', 'o'); map.put('р', 'p');
        map.put('с', 'c'); map.put('у', 'y'); map.put('х', 'x'); map.put('і', 'i');
        map.put('ѕ', 's'); map.put('һ', 'h'); map.put('к', 'k'); map.put('м', 'm');
        map.put('т', 't'); map.put('в', 'b'); map.put('н', 'h'); map.put('ј', 'j');
        map.put('ɡ', 'g'); map.put('ԛ', 'q'); map.put('ԝ', 'w'); map.put('ԁ', 'd');
        map.put('ο', 'o'); map.put('α', 'a'); map.put('ε', 'e'); map.put('ι', 'i');
        map.put('κ', 'k'); map.put('ν', 'v'); map.put('ρ', 'p'); map.put('τ', 't');
        map.put('υ', 'u'); map.put('χ', 'x'); map.put('ς', 's'); map.put('β', 'b');
        map.put('μ', 'm');
        return map;
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
        message = normalize(message);

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
        String stripped = strippedBuilder.toString().toLowerCase(Locale.ROOT);
        String lowerMessage = message.toLowerCase(Locale.ROOT);

        for (int i = 0; i < compiledPatterns.size(); i++) {
            Pattern pattern = compiledPatterns.get(i);
            if (hasForbiddenMatch(pattern, lowerMessage)) {
                return rawPatterns.get(i);
            }
            if (hasForbiddenMatch(pattern, stripped)) {
                return rawPatterns.get(i);
            }
        }
        return null;
    }

    /**
     * True when the pattern matches and the match is NOT confined to a
     * whitelisted word. The whitelist exemption is span-scoped to the match
     * itself: a whitelisted word elsewhere in the message must not exempt
     * the whole text (the old all-words check let "fuck night" bypass every
     * pattern because "night" was whitelisted).
     */
    private static boolean hasForbiddenMatch(Pattern pattern, String text) {
        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (!coveredByWhitelist(text, matcher.start(), matcher.end())) {
                return true;
            }
        }
        return false;
    }

    /** True when a single whitespace-delimited token covers [start, end)
     *  AND that token is whitelisted (handles custom non-anchored regex
     *  entries matching inside words like "scunthorpe"). Multi-word spans
     *  ("kill yourself") are never exempt. */
    private static boolean coveredByWhitelist(String text, int start, int end) {
        int len = text.length();
        int i = 0;
        while (i < len) {
            while (i < len && text.charAt(i) == ' ') i++;
            int wordStart = i;
            while (i < len && text.charAt(i) != ' ') i++;
            if (wordStart < i && wordStart <= start && i >= end
                    && WHITELIST.contains(text.substring(wordStart, i))) {
                return true;
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
