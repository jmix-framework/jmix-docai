package com.company.docai.demo;

import com.company.docai.app.service.document.DocumentTypes;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentComment;
import com.company.docai.model.tag.Tag;
import io.jmix.core.DataManager;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.data.PersistenceHints;
import io.jmix.dynattr.AttributeType;
import io.jmix.dynattr.CategoryDefinition;
import io.jmix.security.role.ResourceRoleRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The check of a demo set for {@link DemoDataLoader}: the problems of its description and whether the database is
 * empty enough to take it. It writes nothing.
 */
@Component
public class DemoSetChecker {

    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Set<AttributeType> DATE_TYPES =
            EnumSet.of(AttributeType.DATE, AttributeType.DATE_WITHOUT_TIME);

    private final UnconstrainedDataManager unconstrainedDataManager;
    private final DataManager dataManager;
    private final DocumentTypes documentTypes;
    private final ResourceRoleRepository roleRepository;

    public DemoSetChecker(UnconstrainedDataManager unconstrainedDataManager, DataManager dataManager,
                          DocumentTypes documentTypes, ResourceRoleRepository roleRepository) {
        this.unconstrainedDataManager = unconstrainedDataManager;
        this.dataManager = dataManager;
        this.documentTypes = documentTypes;
        this.roleRepository = roleRepository;
    }

    /**
     * Returns the problems of the description of the set, as {@link DemoDataLoader#check(String)} lists them; none for
     * a set that loads. The dates come first: a set whose dates cannot be computed lists only their problems, and the
     * set the dates give for the day of the moment is checked then.
     *
     * @param now the moment of the load, in the time zone of the server
     */
    public List<String> problemsOf(String set, LocalDateTime now) {
        DemoSetTemplate template;

        try {
            template = DemoSetTemplate.read(set);
        } catch (IOException | JacksonException e) {
            return List.of("The description of the demo set '%s' cannot be read: %s".formatted(set, e.getMessage()));
        }

        List<String> dateProblems = new ArrayList<>();
        checkDates(set, template, now, dateProblems);

        if (!dateProblems.isEmpty()) {
            return dateProblems;
        }

        DemoSet description = template.generate(set, now.toLocalDate());

        Set<String> users = new HashSet<>(unconstrainedDataManager.loadValues("select u.username from User u")
                .properties("username")
                .list()
                .stream()
                .map(row -> (String) row.getValue("username"))
                .toList());

        Set<String> statusKeys = new HashSet<>(DemoSet.DEFAULT_STATUSES.keySet());
        description.statuses().forEach(entry -> statusKeys.add(entry.key()));
        Set<String> documentStatusKeys = new HashSet<>();

        List<String> problems = new ArrayList<>();
        checkUsers(description.users(), users, problems);
        checkFolders(set, description.folders(), "", users, statusKeys, documentStatusKeys, problems);
        checkStatuses(description.statuses(), documentStatusKeys, problems);
        checkTags(description.folders(), "", new HashMap<>(), problems);
        checkFavorites(description.folders(), "", false, users, problems);
        problems.addAll(description.historyProblems(now));

        return problems;
    }

    /**
     * Tells whether the database holds no folder but the root and no document, deleted ones included.
     */
    public boolean isEmpty() {
        long folders = unconstrainedDataManager.loadValue("select count(f) from Folder f", Long.class)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one();

        long documents = unconstrainedDataManager.loadValue("select count(d) from Document d", Long.class)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one();

        // the root folder comes with the changelogs
        return folders == 1 && documents == 0;
    }

    private static void checkUsers(List<DemoSet.User> described, Set<String> users, List<String> problems) {
        for (DemoSet.User names : described) {
            if (!users.contains(names.login())) {
                problems.add("users: there is no user '" + names.login() + "'");
            }
        }
    }

    // the statuses of the set over the defaults: a new one has a name and a color, a color is #rrggbb, a target is
    // a key, one status is given to new documents, and every status of the set or of a document can be reached from
    // that one
    private static void checkStatuses(List<DemoSet.Status> described, Set<String> documentStatusKeys,
                                      List<String> problems) {
        Map<String, Set<String>> targets = new LinkedHashMap<>();
        Map<String, Boolean> anyTarget = new HashMap<>();
        Map<String, Boolean> initial = new HashMap<>();
        Map<String, Boolean> locked = new HashMap<>();
        targets.put("DRAFT", new HashSet<>(Set.of("ACTIVE")));
        targets.put("ACTIVE", new HashSet<>(Set.of("ARCHIVED")));
        targets.put("ARCHIVED", new HashSet<>(Set.of("ACTIVE")));
        targets.keySet().forEach(key -> anyTarget.put(key, false));
        targets.keySet().forEach(key -> initial.put(key, key.equals("DRAFT")));
        targets.keySet().forEach(key -> locked.put(key, key.equals("ARCHIVED")));

        for (DemoSet.Status entry : described) {
            boolean isNew = !DemoSet.DEFAULT_STATUSES.containsKey(entry.key());

            if (isNew && (isBlank(entry.name()) || entry.color() == null)) {
                problems.add("statuses: the new status '" + entry.key() + "' needs a name and a color");
            }

            if (entry.color() != null && !COLOR.matcher(entry.color()).matches()) {
                problems.add("statuses: the color of the status '" + entry.key() + "' is not #rrggbb");
            }

            targets.putIfAbsent(entry.key(), new HashSet<>());
            anyTarget.putIfAbsent(entry.key(), true);
            initial.putIfAbsent(entry.key(), false);
            locked.putIfAbsent(entry.key(), false);

            if (entry.anyTarget() != null) {
                anyTarget.put(entry.key(), entry.anyTarget());
            }

            if (entry.initial() != null) {
                initial.put(entry.key(), entry.initial());
            }

            if (entry.locked() != null) {
                locked.put(entry.key(), entry.locked());
            }
        }

        for (DemoSet.Status entry : described) {
            if (entry.targets() != null) {
                targets.put(entry.key(), new HashSet<>(entry.targets()));

                entry.targets().stream()
                        .filter(key -> !targets.containsKey(key))
                        .forEach(key -> problems.add("statuses: the status '" + entry.key()
                                + "' moves to no status '" + key + "'"));
            }
        }

        List<String> initialKeys = initial.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .toList();

        if (initialKeys.size() != 1) {
            problems.add("statuses: exactly one status is given to new documents");
            return;
        }

        if (Boolean.TRUE.equals(locked.get(initialKeys.getFirst()))) {
            problems.add("statuses: the status '" + initialKeys.getFirst()
                    + "' is given to new documents and cannot lock them");
        }

        Set<String> reached = new HashSet<>(initialKeys);
        Deque<String> queue = new ArrayDeque<>(initialKeys);

        while (!queue.isEmpty()) {
            String current = queue.poll();

            for (String next : targets.keySet()) {
                boolean moves = Boolean.TRUE.equals(anyTarget.get(current)) || targets.get(current).contains(next);

                if (!next.equals(current) && moves && reached.add(next)) {
                    queue.add(next);
                }
            }
        }

        // a key of a document that names no status has its own problem already
        Set<String> checked = new LinkedHashSet<>();
        described.forEach(entry -> checked.add(entry.key()));

        documentStatusKeys.stream()
                .filter(targets::containsKey)
                .forEach(checked::add);

        checked.stream()
                .filter(key -> !reached.contains(key))
                .forEach(key -> problems.add("statuses: the status '" + key
                        + "' cannot be reached from the initial status"));
    }

    private void checkFolders(String set, List<DemoSet.Folder> folders, String path, Set<String> users,
                              Set<String> statusKeys, Set<String> documentStatusKeys, List<String> problems) {
        Set<String> names = new HashSet<>();

        for (DemoSet.Folder folder : folders) {
            String where = path + "/" + folder.name();

            if (isBlank(folder.name())) {
                problems.add(path + "/: a folder has no name");
            } else if (!names.add(folder.name())) {
                problems.add(where + ": two folders have this name");
            }

            checkAccess(folder.access(), where, users, problems);
            checkDeletion(folder.deleted(), where, users, problems);
            checkFolders(set, folder.folders(), where, users, statusKeys, documentStatusKeys, problems);
            checkDocuments(set, folder.documents(), where, users, statusKeys, documentStatusKeys, problems);
        }
    }

    private void checkDocuments(String set, List<DemoSet.Document> documents, String path, Set<String> users,
                                Set<String> statusKeys, Set<String> documentStatusKeys, List<String> problems) {
        Set<String> names = new HashSet<>();

        for (DemoSet.Document document : documents) {
            String where = path + "/" + document.name();

            if (isBlank(document.name())) {
                problems.add(path + ": a document has no name");
            } else if (!names.add(document.name())) {
                problems.add(where + ": two documents have this name");
            }

            if (document.versions().isEmpty()) {
                problems.add(where + ": the document has no versions");
            }

            for (DemoSet.Version version : document.versions()) {
                boolean missing = isBlank(version.file()) || !DemoSet.file(set, version.file()).exists();

                if (version.text() == null && missing) {
                    problems.add(where + ": there is no file '" + version.file() + "'");
                } else if (!version.file().contains(".")) {
                    // the loader takes the type of the file from its extension
                    problems.add(where + ": the file name '" + version.file() + "' has no extension");
                }

                if (!users.contains(version.author())) {
                    problems.add(where + ": there is no user '" + version.author() + "'");
                }

                if (version.uploaded() == null) {
                    problems.add(where + ": a version has no upload time");
                }
            }

            if (document.status() != null && !statusKeys.contains(document.status())) {
                problems.add(where + ": there is no status '" + document.status() + "'");
            }

            documentStatusKeys.add(document.status() == null ? "DRAFT" : document.status());

            checkType(document, where, problems);
            checkAccess(document.access(), where, users, problems);
            checkDeletion(document.deleted(), where, users, problems);
            checkComments(document.comments(), where, users, problems);
        }
    }

    // the vocabulary keeps one tag for a name whatever its case, so a set writes a name one way
    private static void checkTags(List<DemoSet.Folder> folders, String path, Map<String, String> spellings,
                                  List<String> problems) {
        for (DemoSet.Folder folder : folders) {
            String folderPath = path + "/" + folder.name();

            for (DemoSet.Document document : folder.documents()) {
                String where = folderPath + "/" + document.name();

                for (String tag : document.tags()) {
                    String spelling = isBlank(tag) ? null : spellings.putIfAbsent(tag.toLowerCase(Locale.ROOT), tag);

                    if (isBlank(tag)) {
                        problems.add(where + ": a tag has no name");
                    } else if (tag.length() > Tag.MAX_NAME_LENGTH) {
                        problems.add(where + ": the tag '" + tag + "' is longer than " + Tag.MAX_NAME_LENGTH
                                + " characters");
                    } else if (spelling != null && !spelling.equals(tag)) {
                        problems.add(where + ": the tag '" + tag + "' is written '" + spelling + "' elsewhere");
                    }
                }
            }

            checkTags(folder.folders(), folderPath, spellings, problems);
        }
    }

    // a mark of a folder or a document that the loader puts into the trash would never show
    private static void checkFavorites(List<DemoSet.Folder> folders, String path, boolean deleted, Set<String> users,
                                       List<String> problems) {
        for (DemoSet.Folder folder : folders) {
            String folderPath = path + "/" + folder.name();
            boolean folderDeleted = deleted || folder.deleted() != null;
            checkMarks(folder.favoriteOf(), folderPath, folderDeleted, users, problems);

            for (DemoSet.Document document : folder.documents()) {
                checkMarks(document.favoriteOf(), folderPath + "/" + document.name(),
                        folderDeleted || document.deleted() != null, users, problems);
            }

            checkFavorites(folder.folders(), folderPath, folderDeleted, users, problems);
        }
    }

    private static void checkMarks(List<String> logins, String where, boolean deleted, Set<String> users,
                                   List<String> problems) {
        if (deleted && !logins.isEmpty()) {
            problems.add(where + ": an item in the trash cannot be a favorite");
        }

        for (String login : logins) {
            if (!users.contains(login)) {
                problems.add(where + ": there is no user '" + login + "'");
            }
        }
    }

    private static void checkDeletion(DemoSet.@Nullable Deletion deletion, String where, Set<String> users,
                                      List<String> problems) {
        if (deletion == null) {
            return;
        }

        if (!users.contains(deletion.by())) {
            problems.add(where + ": there is no user '" + deletion.by() + "'");
        }

        if (deletion.at() == null) {
            problems.add(where + ": the deletion has no time");
        }
    }

    private static void checkComments(List<DemoSet.Comment> comments, String where, Set<String> users,
                                      List<String> problems) {
        for (DemoSet.Comment comment : comments) {
            if (!users.contains(comment.author())) {
                problems.add(where + ": there is no user '" + comment.author() + "'");
            }

            if (isBlank(comment.text())) {
                problems.add(where + ": a comment has no text");
            } else if (comment.text().strip().length() > DocumentComment.MAX_TEXT_LENGTH) {
                problems.add(where + ": a comment is longer than " + DocumentComment.MAX_TEXT_LENGTH + " characters");
            }

            if (comment.at() == null) {
                problems.add(where + ": a comment has no time");
            }
        }
    }

    private void checkDates(String set, DemoSetTemplate template, LocalDateTime now, List<String> problems) {
        DemoCalendar calendar;

        try {
            calendar = template.calendar(now.toLocalDate());
        } catch (IllegalArgumentException e) {
            // the problem names the locale or the format
            problems.add(e.getMessage());
            return;
        }

        checkFolderDates(set, template.folders(), "", calendar, problems);
    }

    // a document whose named dates cannot be computed gets one problem, and nothing that refers to them is checked
    private void checkFolderDates(String set, List<DemoSetTemplate.Folder> folders, String path, DemoCalendar calendar,
                                  List<String> problems) {
        for (DemoSetTemplate.Folder folder : folders) {
            String folderPath = path + "/" + folder.name();
            DemoCalendar.Dates folderDates = calendar.datesOf(Map.of());
            check(folder.name(), folderDates::text, folderPath, problems);
            check(folder.deleted() == null ? null : folder.deleted().at(), folderDates::time, folderPath, problems);

            for (DemoSetTemplate.Document document : folder.documents()) {
                String where = folderPath + "/" + document.name();

                try {
                    checkDocumentDates(set, document, calendar.datesOf(document.dates()), where, problems);
                } catch (IllegalArgumentException e) {
                    problems.add(where + ": " + e.getMessage());
                }
            }

            checkFolderDates(set, folder.folders(), folderPath, calendar, problems);
        }
    }

    private void checkDocumentDates(String set, DemoSetTemplate.Document document, DemoCalendar.Dates dates,
                                    String where, List<String> problems) {
        check(document.name(), dates::text, where, problems);
        check(document.date(), dates::date, where, problems);
        checkFieldDates(document, dates, where, problems);

        for (DemoSetTemplate.Version version : document.versions()) {
            check(version.uploaded(), dates::time, where, problems);
            checkText(set, version, dates, where, problems);
        }

        for (DemoSetTemplate.Comment comment : document.comments()) {
            check(comment.at(), dates::time, where, problems);
            check(comment.text(), dates::text, where, problems);
            check(comment.edited(), dates::time, where, problems);
        }

        check(document.deleted() == null ? null : document.deleted().at(), dates::time, where, problems);
    }

    // a field of a date holds a named date or a date of ISO 8601, and another field may write a year
    private void checkFieldDates(DemoSetTemplate.Document document, DemoCalendar.Dates dates, String where,
                                 List<String> problems) {
        Set<String> dateFields = dateFieldsOf(document.type());

        document.fields().forEach((code, value) -> {
            if (value != null && dateFields.contains(code.toLowerCase(Locale.ROOT))
                    && !ISO_DATE.matcher(value).matches() && !document.dates().containsKey(value)) {
                problems.add(where + ": the field '" + code + "' refers to '" + value
                        + "', which the document does not declare");
            } else {
                check(value, dates::value, where, problems);
            }
        });
    }

    // the codes of the fields of a date of the type, in lower case, as the type rules match them
    private Set<String> dateFieldsOf(@Nullable String typeName) {
        if (typeName == null) {
            return Set.of();
        }

        return documentTypes.find(typeName)
                .map(type -> type.getAttributeDefinitions()
                        .stream()
                        .filter(attribute -> DATE_TYPES.contains(attribute.getDataType()))
                        .map(attribute -> attribute.getCode().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    private static void checkText(String set, DemoSetTemplate.Version version, DemoCalendar.Dates dates,
                                  String where, List<String> problems) {
        if (version.text() == null) {
            return;
        }

        if (version.file() != null) {
            problems.add(where + ": a version names both a file and a text");
        }

        ClassPathResource text = DemoSetTemplate.text(set, version.text());

        if (!text.exists()) {
            problems.add(where + ": there is no text '" + text.getFilename() + "'");
            return;
        }

        try {
            dates.text(text.getContentAsString(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            problems.add(where + ": the text '" + text.getFilename() + "': " + e.getMessage());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void check(@Nullable String value, Function<String, ?> computation, String where,
                              List<String> problems) {
        if (value == null) {
            return;
        }

        try {
            computation.apply(value);
        } catch (IllegalArgumentException e) {
            problems.add(where + ": " + e.getMessage());
        }
    }

    private void checkType(DemoSet.Document document, String where, List<String> problems) {
        if (document.type() == null) {
            if (!document.fields().isEmpty()) {
                problems.add(where + ": the fields need a type");
            }

            return;
        }

        // the same rules the AI follows when it fills the type of a document
        CategoryDefinition type = documentTypes.find(document.type())
                .orElse(null);

        if (type == null) {
            problems.add(where + ": there is no type '" + document.type() + "'");
            return;
        }

        DocumentTypes.Filling filling = documentTypes.fill(dataManager.create(Document.class), type,
                DemoSet.fieldValuesOf(document), null);

        for (String skipped : filling.skipped()) {
            problems.add(where + ": the type does not accept " + skipped);
        }
    }

    private void checkAccess(@Nullable List<DemoSet.Access> entries, String where, Set<String> users,
                             List<String> problems) {
        if (entries == null) {
            return;
        }

        for (DemoSet.Access entry : entries) {
            if ((entry.user() == null) == (entry.role() == null)) {
                problems.add(where + ": an access entry must name a user or a role");
            } else if (entry.user() != null && !users.contains(entry.user())) {
                problems.add(where + ": there is no user '" + entry.user() + "'");
            } else if (entry.role() != null && roleRepository.findRoleByCode(entry.role()) == null) {
                problems.add(where + ": there is no role '" + entry.role() + "'");
            }

            if (entry.level() == null || DemoSet.levelOf(entry.level()) == null) {
                problems.add(where + ": there is no access level '" + entry.level() + "'");
            }
        }
    }

    private static boolean isBlank(@Nullable String text) {
        return text == null || text.isBlank();
    }
}
