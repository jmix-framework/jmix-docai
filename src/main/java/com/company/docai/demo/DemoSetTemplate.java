package com.company.docai.demo;

import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.DateFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A demo set as its {@code demo-data.json} writes it, with the dates as expressions of {@link DemoCalendar};
 * {@link #generate} gives the set of a day.
 *
 * @param locale  the language of the set as a tag, {@code en} or {@code ru}; English when left out
 * @param formats the formats of the dates the texts of the set write, by their names, as patterns of
 *                {@link java.time.format.DateTimeFormatter}
 */
public record DemoSetTemplate(@Nullable String locale, Map<String, String> formats, List<DemoSet.User> users,
                              List<DemoSet.Status> statuses, List<Folder> folders) {

    // a misspelt property of the description is a problem, not a value silently left out
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    // the languages with names of the months; a tag of none, such as ru_RU or rus, would name them as the root locale
    private static final Set<String> LANGUAGES = Arrays.stream(DateFormatSymbols.getAvailableLocales())
            .map(Locale::getLanguage)
            .filter(language -> !language.isEmpty())
            .collect(Collectors.toUnmodifiableSet());

    public DemoSetTemplate {
        formats = formats == null ? Map.of() : formats;
        users = users == null ? List.of() : users;
        statuses = statuses == null ? List.of() : statuses;
        folders = folders == null ? List.of() : folders;
    }

    /**
     * Reads the description of the set from its folder.
     */
    public static DemoSetTemplate read(String set) throws IOException {
        try (InputStream content = new ClassPathResource(DemoSet.LOCATION.formatted(set) + DemoSet.DESCRIPTION)
                .getInputStream()) {
            return JSON.readValue(content, DemoSetTemplate.class);
        }
    }

    /**
     * Returns the text a file of a version is made from: {@code texts/<file name>.txt} of the set.
     */
    public static ClassPathResource text(String set, String file) {
        String name = file.contains(".") ? file.substring(0, file.lastIndexOf('.')) : file;

        return new ClassPathResource(DemoSet.LOCATION.formatted(set) + "texts/" + name + ".txt");
    }

    /**
     * Returns the calendar of the set for a day: its dates in its language and formats.
     *
     * @throws IllegalArgumentException when the locale of the set is not a language with names of the months, or a
     *                                  format of the set is not a pattern of a date
     */
    public DemoCalendar calendar(LocalDate today) {
        Locale language = locale == null ? Locale.ENGLISH : Locale.forLanguageTag(locale);

        if (!LANGUAGES.contains(language.getLanguage())) {
            throw new IllegalArgumentException("the locale '%s' is not a language with names of the months"
                    .formatted(locale));
        }

        return new DemoCalendar(today, language, formats);
    }

    /**
     * Computes the set for a day.
     *
     * @throws IllegalArgumentException when an expression cannot be computed, as the check of the set lists it
     * @throws UncheckedIOException     when a text of the set cannot be read
     */
    public DemoSet generate(String set, LocalDate today) {
        DemoCalendar calendar = calendar(today);

        return new DemoSet(users, statuses, folders.stream()
                .map(folder -> folder.generate(set, calendar))
                .toList());
    }

    private static @Nullable String textOf(DemoCalendar.Dates dates, @Nullable String text) {
        return text == null ? null : dates.text(text);
    }

    private static @Nullable LocalDateTime timeOf(DemoCalendar.Dates dates, @Nullable String time) {
        return time == null ? null : dates.time(time);
    }

    private static DemoSet.@Nullable Deletion deletionOf(DemoCalendar.Dates dates, @Nullable Deletion deletion) {
        return deletion == null ? null : new DemoSet.Deletion(deletion.by(), timeOf(dates, deletion.at()));
    }

    /**
     * A folder of the set.
     *
     * @param name       the name of the folder, which may write a year
     * @param access     the own access of the folder, or null when it inherits the access of its parent
     * @param deleted    who deleted the folder and when, or null for a live folder
     * @param favoriteOf the logins of the users who marked the folder as a favorite
     */
    public record Folder(String name, @Nullable List<DemoSet.Access> access, List<Folder> folders,
                         List<Document> documents, @Nullable Deletion deleted, List<String> favoriteOf) {

        public Folder {
            favoriteOf = favoriteOf == null ? List.of() : favoriteOf;
            folders = folders == null ? List.of() : folders;
            documents = documents == null ? List.of() : documents;
        }

        private DemoSet.Folder generate(String set, DemoCalendar calendar) {
            DemoCalendar.Dates dates = calendar.datesOf(Map.of());

            List<DemoSet.Folder> subfolders = folders.stream()
                    .map(folder -> folder.generate(set, calendar))
                    .toList();

            List<DemoSet.Document> generated = documents.stream()
                    .map(document -> document.generate(set, calendar))
                    .toList();

            return new DemoSet.Folder(textOf(dates, name), access, subfolders, generated, deletionOf(dates, deleted),
                    favoriteOf);
        }
    }

    /**
     * A document of the set.
     *
     * @param name       the name of the document, which may write a named date and a year
     * @param dates      the expressions of the named dates of the document by their names
     * @param date       the date of the document: a named date, an expression or a date of ISO 8601
     * @param type       the English name of the document type, or null for a document without a type
     * @param fields     the values of the fields of the type by the code of the field: a named date for a field of a
     *                   date, a text that may write a year for another field
     * @param tags       the names of the tags of the document; a name is written the same way all over the set
     * @param status     {@code DRAFT} (when left out), {@code ACTIVE} or {@code ARCHIVED}
     * @param access     the own access of the document, or null when it inherits the access of its folder
     * @param versions   the versions, the first one first
     * @param comments   the comments, the oldest first
     * @param deleted    who deleted the document and when, or null for a live document
     * @param favoriteOf the logins of the users who marked the document as a favorite
     */
    public record Document(String name, Map<String, String> dates, @Nullable String date, @Nullable String type,
                           Map<String, String> fields, List<String> tags, @Nullable String status,
                           @Nullable List<DemoSet.Access> access, List<Version> versions, List<Comment> comments,
                           @Nullable Deletion deleted, List<String> favoriteOf) {

        public Document {
            dates = dates == null ? Map.of() : dates;
            favoriteOf = favoriteOf == null ? List.of() : favoriteOf;
            fields = fields == null ? Map.of() : fields;
            tags = tags == null ? List.of() : tags;
            versions = versions == null ? List.of() : versions;
            comments = comments == null ? List.of() : comments;
        }

        private DemoSet.Document generate(String set, DemoCalendar calendar) {
            DemoCalendar.Dates named = calendar.datesOf(dates);
            Map<String, String> values = new LinkedHashMap<>();
            fields.forEach((code, value) -> values.put(code, value == null ? null : named.value(value)));

            List<DemoSet.Version> generated = versions.stream()
                    .map(version -> version.generate(set, named))
                    .toList();

            List<DemoSet.Comment> written = comments.stream()
                    .map(comment -> comment.generate(named))
                    .toList();

            return new DemoSet.Document(textOf(named, name), date == null ? null : named.date(date), type, values,
                    tags, status, access, generated, written, deletionOf(named, deleted), favoriteOf);
        }
    }

    /**
     * A version of a document: a file of the set, or a file the loader makes from a text of the set.
     *
     * @param file     the name of the file in the {@code files} folder of the set, or null for a file made from a text
     * @param text     the name of the file made from the text {@link DemoSetTemplate#text(String, String)} returns, or
     *                 null for a file of the {@code files} folder
     * @param author   the login of the user who uploads the version
     * @param uploaded when the version was uploaded: a time of the history
     */
    public record Version(@Nullable String file, @Nullable String text, String author, @Nullable String uploaded) {

        private DemoSet.Version generate(String set, DemoCalendar.Dates dates) {
            if (text == null) {
                return new DemoSet.Version(file, null, author, timeOf(dates, uploaded));
            }

            try {
                String content = DemoSetTemplate.text(set, text)
                        .getContentAsString(StandardCharsets.UTF_8);

                return new DemoSet.Version(text, dates.text(content), author, timeOf(dates, uploaded));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * A comment on a document.
     *
     * @param author the login of the user who writes it
     * @param at     when it was written: a time of the history
     * @param text   the text of the comment, which may write a named date
     * @param edited when its author changed it last, or null for a comment never changed
     */
    public record Comment(String author, @Nullable String at, String text, @Nullable String edited) {

        private DemoSet.Comment generate(DemoCalendar.Dates dates) {
            return new DemoSet.Comment(author, timeOf(dates, at), textOf(dates, text), timeOf(dates, edited));
        }
    }

    /**
     * The deletion of a folder or a document that lies in the trash.
     *
     * @param by the login of the user who deleted it
     * @param at when it was deleted: a time of the history
     */
    public record Deletion(String by, @Nullable String at) {
    }
}
