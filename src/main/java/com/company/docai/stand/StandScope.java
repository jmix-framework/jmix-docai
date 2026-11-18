package com.company.docai.stand;

import org.jspecify.annotations.Nullable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The part of the stand's data that a thread works with: a visitor's copy or the template of a language.
 *
 * @param id the name of the scope, {@code copy_<key>} or {@code tpl_<language>}
 */
public record StandScope(String id) {

    private static final String COPY_PREFIX = "copy_";

    private static final String TEMPLATE_PREFIX = "tpl_";

    private static final String COPIES = "copies";

    private static final Pattern KEY = Pattern.compile("[0-9a-f]{32}");

    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2}");

    private static final Pattern ID = Pattern.compile(COPY_PREFIX + KEY.pattern() + "|" + TEMPLATE_PREFIX
            + LANGUAGE.pattern());

    // a key in a text of another library: in the name of an index or a database, or in a path, with either separator
    private static final Pattern KEY_IN_TEXT = Pattern.compile("(" + COPY_PREFIX + "|" + COPIES + "[/\\\\])("
            + KEY.pattern() + ")(?![0-9a-f])");

    private static final int TAG_BYTES = 4;

    /**
     * Makes the scope with the id.
     *
     * @throws IllegalArgumentException if the id is neither {@code copy_<key>} nor {@code tpl_<language>}
     */
    public StandScope {
        // the id may be a key a character off, so the message does not repeat it
        if (!isId(id)) {
            throw new IllegalArgumentException("A scope is copy_<key> or tpl_<language>");
        }
    }

    /**
     * Makes the scope of a copy.
     *
     * @param key 32 lower-case hexadecimal characters
     * @throws IllegalArgumentException if the key is not well-formed
     */
    public static StandScope copy(String key) {
        if (!isKey(key)) {
            throw new IllegalArgumentException("A copy key is 32 lower-case hexadecimal characters");
        }

        return new StandScope(COPY_PREFIX + key);
    }

    /**
     * Whether the text is a well-formed key of a copy: 32 lower-case hexadecimal characters.
     */
    public static boolean isKey(String key) {
        return KEY.matcher(key)
                .matches();
    }

    /**
     * Makes the scope of a template.
     *
     * @param language two lower-case letters
     * @throws IllegalArgumentException if the language is not well-formed
     */
    public static StandScope template(String language) {
        if (!LANGUAGE.matcher(language)
                .matches()) {
            throw new IllegalArgumentException("A template language is two lower-case letters");
        }

        return new StandScope(TEMPLATE_PREFIX + language);
    }

    /**
     * The scope with the id, or null when the text names no scope: for an id kept where a stale or a forged value can
     * come from, such as an attribute of an HTTP session.
     */
    public static @Nullable StandScope fromId(@Nullable String id) {
        return id != null && isId(id)
                ? new StandScope(id)
                : null;
    }

    /**
     * The name of the scope in logs and in the messages of exceptions: the id of a template, or {@code copy#} with the
     * first eight hexadecimal characters of the SHA-256 of the key of a copy. The key opens the copy through the cookie
     * of its browser, and the tag does not give it away.
     */
    public String tag() {
        if (!isCopy()) {
            return id;
        }

        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(key().getBytes(StandardCharsets.US_ASCII));

            return "copy#" + HexFormat.of()
                    .formatHex(hash, 0, TAG_BYTES);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The text with the key of each copy it names replaced by the tag of the copy, see {@link #tag()}: for a text the
     * stand did not write, such as a message of OpenSearch, of the database or of the file system.
     */
    public static String withTags(String text) {
        return KEY_IN_TEXT.matcher(text)
                .replaceAll(found -> Matcher.quoteReplacement(tagged(found.group(1), found.group(2))));
    }

    /**
     * The throwable as a log prints it, with its causes and their frames, and with the key of each copy it names
     * replaced by the tag of the copy, see {@link #withTags(String)}.
     */
    public static String withTags(Throwable throwable) {
        StringWriter trace = new StringWriter();
        throwable.printStackTrace(new PrintWriter(trace));

        return withTags(trace.toString()
                .stripTrailing());
    }

    /**
     * Whether the scope is a visitor's copy.
     */
    public boolean isCopy() {
        return id.startsWith(COPY_PREFIX);
    }

    /**
     * Whether the scope is the template of a language.
     */
    public boolean isTemplate() {
        return id.startsWith(TEMPLATE_PREFIX);
    }

    /**
     * The key of a copy.
     *
     * @throws IllegalStateException for a template
     */
    public String key() {
        if (!isCopy()) {
            throw new IllegalStateException("Only a copy has a key: " + id);
        }

        return id.substring(COPY_PREFIX.length());
    }

    /**
     * The language of a template.
     *
     * @throws IllegalStateException for a copy
     */
    public String language() {
        if (!isTemplate()) {
            throw new IllegalStateException("Only a template has a language: " + tag());
        }

        return id.substring(TEMPLATE_PREFIX.length());
    }

    /**
     * The URL of the in-memory database of the scope.
     */
    public String databaseUrl() {
        return "jdbc:hsqldb:mem:" + id;
    }

    /**
     * The prefix of the search indexes of this scope.
     */
    public String indexPrefix(String basePrefix) {
        return basePrefix + id + "_";
    }

    /**
     * The folder of the scope's script and files.
     *
     * @param dataDir the folder of all templates and copies
     */
    public Path folder(Path dataDir) {
        if (isCopy()) {
            return dataDir.resolve(COPIES)
                    .resolve(key());
        }

        return dataDir.resolve("templates")
                .resolve(language());
    }

    /**
     * The scope by its tag, so that a scope that gets into a log by chance gives no key away.
     */
    @Override
    public String toString() {
        return "StandScope[" + tag() + "]";
    }

    private static boolean isId(String id) {
        return ID.matcher(id)
                .matches();
    }

    // copy_<key> in a name becomes the tag, copies/<key> in a path becomes copies/<tag>
    private static String tagged(String before, String key) {
        String tag = copy(key)
                .tag();

        return before.equals(COPY_PREFIX)
                ? tag
                : before + tag;
    }
}
