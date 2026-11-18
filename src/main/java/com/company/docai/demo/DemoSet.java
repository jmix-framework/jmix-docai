package com.company.docai.demo;

import com.company.docai.app.service.document.DocumentTypes;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.document.DocumentStatus;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A demo set for one day, which {@link DemoSetTemplate#generate} computes from the {@code demo-data.json} of the set.
 *
 * @param users    the names the set gives the users of the changelogs
 * @param statuses the statuses of the set: the default ones renamed in the language of the set, and new ones
 */
public record DemoSet(List<User> users, List<Status> statuses, List<Folder> folders) {

    /**
     * The classpath folder of a demo set, a format that takes the name of the set.
     */
    public static final String LOCATION = "com/company/docai/demo/%s/";

    /**
     * The file in the folder of a set that describes the set.
     */
    public static final String DESCRIPTION = "demo-data.json";

    /**
     * The keys of the default statuses of the changelog, which a set names in its language.
     */
    public static final Map<String, UUID> DEFAULT_STATUSES = Map.of(
            "DRAFT", DocumentStatus.DRAFT_ID,
            "ACTIVE", DocumentStatus.ACTIVE_ID,
            "ARCHIVED", DocumentStatus.ARCHIVED_ID);

    public DemoSet {
        users = users == null ? List.of() : users;
        statuses = statuses == null ? List.of() : statuses;
        folders = folders == null ? List.of() : folders;
    }

    /**
     * Returns a file of the set from the {@code files} folder of the set.
     */
    public static ClassPathResource file(String set, String file) {
        return new ClassPathResource(LOCATION.formatted(set) + "files/" + file);
    }

    /**
     * Returns the values of the fields of the document, as text, in the form {@link DocumentTypes#fill} takes.
     */
    public static List<DocumentTypes.FieldValue> fieldValuesOf(Document document) {
        return document.fields()
                .entrySet()
                .stream()
                .map((Map.Entry<String, String> field) -> new DocumentTypes.FieldValue(field.getKey(),
                        field.getValue()))
                .toList();
    }

    /**
     * Returns the access level with the name, or null for a name that is not a level.
     */
    public static @Nullable AccessLevel levelOf(String name) {
        try {
            return AccessLevel.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Returns the problems of the history of the set at a moment, such as an event after it.
     *
     * @param now the moment of the load, in the time zone of the server
     */
    public List<String> historyProblems(LocalDateTime now) {
        List<String> problems = new ArrayList<>();
        collectHistoryProblems(folders, "", now, problems);

        return problems;
    }

    private static void collectHistoryProblems(List<Folder> folders, String path, LocalDateTime now,
                                               List<String> problems) {
        for (Folder folder : folders) {
            String folderPath = path + "/" + folder.name();

            checkNotLater(folder.deleted() == null ? null : folder.deleted().at(), now, folderPath,
                    "the folder is deleted", problems);

            for (Document document : folder.documents()) {
                collectHistoryProblems(document, folderPath + "/" + document.name(), now, problems);
            }

            collectHistoryProblems(folder.folders(), folderPath, now, problems);
        }
    }

    private static void collectHistoryProblems(Document document, String where, LocalDateTime now,
                                               List<String> problems) {
        for (Version version : document.versions()) {
            checkNotLater(version.uploaded(), now, where, "a version is uploaded", problems);
        }

        for (Comment comment : document.comments()) {
            checkNotLater(comment.at(), now, where, "a comment is written", problems);
            checkNotLater(comment.edited(), now, where, "a comment is edited", problems);

            if (comment.at() != null && comment.edited() != null && comment.edited().isBefore(comment.at())) {
                problems.add(where + ": a comment is edited before it is written");
            }
        }

        checkNotLater(document.deleted() == null ? null : document.deleted().at(), now, where,
                "the document is deleted", problems);

        // the earlier versions of a document may be drafts, the last one carries its date
        LocalDateTime uploaded = document.versions().isEmpty() ? null : document.versions().getLast().uploaded();

        if (document.date() != null && uploaded != null && uploaded.toLocalDate().isBefore(document.date())) {
            problems.add(where + ": the last version is uploaded before the date of the document");
        }
    }

    private static void checkNotLater(@Nullable LocalDateTime time, LocalDateTime now, String where, String event,
                                      List<String> problems) {
        if (time != null && time.isAfter(now)) {
            problems.add(where + ": " + event + " in the future, " + time);
        }
    }

    /**
     * A user of the changelogs, named in the language of the set.
     */
    public record User(String login, String firstName, String lastName, String email) {
    }

    /**
     * A status of the set. The keys {@code DRAFT}, {@code ACTIVE} and {@code ARCHIVED} are the default statuses of the
     * changelog; another key makes a new status, or takes the status that has the name already. A field left out keeps
     * what the status has.
     *
     * @param key       the key the documents and the targets of the set refer to
     * @param color     the color, {@code #rrggbb}
     * @param locked    whether the documents in the status keep their content
     * @param anyTarget whether a document moves from the status to any status
     * @param targets   the keys of the statuses a document moves to from this one
     */
    public record Status(String key, @Nullable String name, @Nullable String color, @Nullable Integer position,
                         @Nullable Boolean initial, @Nullable Boolean locked, @Nullable Boolean anyTarget,
                         @Nullable List<String> targets) {
    }

    /**
     * A folder of the set.
     *
     * @param access     the own access of the folder, or null when it inherits the access of its parent
     * @param deleted    who deleted the folder and when, or null for a live folder
     * @param favoriteOf the logins of the users who marked the folder as a favorite
     */
    public record Folder(String name, @Nullable List<Access> access, List<Folder> folders, List<Document> documents,
                         @Nullable Deletion deleted,
                         List<String> favoriteOf) {

        public Folder {
            favoriteOf = favoriteOf == null ? List.of() : favoriteOf;
            folders = folders == null ? List.of() : folders;
            documents = documents == null ? List.of() : documents;
        }
    }

    /**
     * An entry of an own access: a user or a role, and its level.
     *
     * @param user  the login of the user, or null for a role
     * @param role  the code of the role, or null for a user
     * @param level {@code VIEW}, {@code EDIT} or {@code MANAGE}
     */
    public record Access(@Nullable String user, @Nullable String role, String level) {
    }

    /**
     * A document of the set.
     *
     * @param type       the English name of the document type, or null for a document without a type
     * @param fields     the values of the fields of the type by the code of the field, as text
     * @param tags       the names of the tags of the document; a name is written the same way all over the set
     * @param status     {@code DRAFT} (when left out), {@code ACTIVE} or {@code ARCHIVED}
     * @param access     the own access of the document, or null when it inherits the access of its folder
     * @param versions   the versions, the first one first
     * @param comments   the comments, the oldest first
     * @param deleted    who deleted the document and when, or null for a live document
     * @param favoriteOf the logins of the users who marked the document as a favorite
     */
    public record Document(String name, @Nullable LocalDate date, @Nullable String type, Map<String, String> fields,
                           List<String> tags, @Nullable String status, @Nullable List<Access> access,
                           List<Version> versions, List<Comment> comments, @Nullable Deletion deleted,
                           List<String> favoriteOf) {

        public Document {
            favoriteOf = favoriteOf == null ? List.of() : favoriteOf;
            fields = fields == null ? Map.of() : fields;
            tags = tags == null ? List.of() : tags;
            versions = versions == null ? List.of() : versions;
            comments = comments == null ? List.of() : comments;
        }
    }

    /**
     * A version of a document.
     *
     * @param file     the name of the file in the {@code files} folder of the set, or of the file made from a text
     * @param text     the text the file is made from, with the dates of the day, or null for a file of the
     *                 {@code files} folder
     * @param author   the login of the user who uploads the version
     * @param uploaded when the version was uploaded, in the time zone of the server
     */
    public record Version(String file, @Nullable String text, String author, LocalDateTime uploaded) {
    }

    /**
     * A comment on a document.
     *
     * @param author the login of the user who writes it
     * @param at     when it was written, in the time zone of the server
     * @param edited when its author changed it last, in the time zone of the server, or null for a comment never
     *               changed
     */
    public record Comment(String author, LocalDateTime at, String text, @Nullable LocalDateTime edited) {
    }

    /**
     * The deletion of a folder or a document that lies in the trash.
     *
     * @param by the login of the user who deleted it
     * @param at when it was deleted, in the time zone of the server
     */
    public record Deletion(String by, LocalDateTime at) {
    }
}
