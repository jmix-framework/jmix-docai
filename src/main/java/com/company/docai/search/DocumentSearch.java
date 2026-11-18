package com.company.docai.search;

import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.tag.Tag;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.FetchPlanBuilder;
import io.jmix.core.IdSerialization;
import io.jmix.core.MessageTools;
import io.jmix.core.Messages;
import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.search.searching.EntitySearcher;
import io.jmix.search.searching.FieldHit;
import io.jmix.search.searching.SearchContext;
import io.jmix.search.searching.SearchResult;
import io.jmix.search.searching.SearchResultEntry;
import org.apache.commons.lang3.Strings;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Searches documents by words and by the beginnings of words, see {@link WordsThenBeginningsStrategy}. Every found
 * document is read again through {@link DataManager}, so the user gets only the live documents they may see, and
 * nothing tells how many others match: the index counts them too.
 */
@Service
public class DocumentSearch {

    /**
     * The number of documents on a page.
     */
    public static final int PAGE_SIZE = 20;

    private static final Logger log = LoggerFactory.getLogger(DocumentSearch.class);

    private static final String PREFIX_STRATEGY = "startsWith";

    // the add-on names a hit in the beginnings after its field and keeps the instance names of a reference in that name
    private static final String BEGINNINGS = ".prefix";
    private static final String INSTANCE_NAMES = "._instance_name";

    private static final String FILE_TEXT = "currentRevision.file._content";
    private static final String NAME = "name";
    private static final String NAME_PREFIX = NAME + BEGINNINGS;
    private static final String FILE_NAME = "currentRevision.file._file_name";
    // the add-on names a hit in the instance names of a reference after the reference
    private static final String TAGS = "tags";
    private static final List<String> SNIPPET_ORDER = List.of(FILE_TEXT, NAME, FILE_NAME, TAGS);
    private static final List<String> NAME_FIELDS = List.of(NAME, NAME_PREFIX);

    private final EntitySearcher entitySearcher;
    private final WordsThenBeginningsStrategy wordsThenBeginnings;
    private final DataManager dataManager;
    private final FolderService folderService;
    private final IdSerialization idSerialization;
    private final Metadata metadata;
    private final MetadataTools metadataTools;
    private final MessageTools messageTools;
    private final Messages messages;

    public DocumentSearch(EntitySearcher entitySearcher,
                          WordsThenBeginningsStrategy wordsThenBeginnings,
                          DataManager dataManager,
                          FolderService folderService,
                          IdSerialization idSerialization,
                          Metadata metadata,
                          MetadataTools metadataTools,
                          MessageTools messageTools,
                          Messages messages) {
        this.entitySearcher = entitySearcher;
        this.wordsThenBeginnings = wordsThenBeginnings;
        this.dataManager = dataManager;
        this.folderService = folderService;
        this.idSerialization = idSerialization;
        this.metadata = metadata;
        this.metadataTools = metadataTools;
        this.messageTools = messageTools;
        this.messages = messages;
    }

    /**
     * Returns the first page of the documents that match the words: those with a word of the text or one of its forms
     * first, then those with a word that begins with a word of the text of three characters or more. A text of more
     * than eight such words is searched by whole words only.
     *
     * @throws SearchUnavailableException if the search engine does not answer
     */
    public DocumentSearchPage search(String text) {
        SearchContext context = new SearchContext(text)
                .setEntities(documentClass().getName())
                .setSize(PAGE_SIZE + 1);

        SearchResult result = engine(() -> entitySearcher.search(context, WordsThenBeginningsStrategy.NAME));

        return page(result, hitsOf(result));
    }

    /**
     * Returns the page that follows the given one.
     *
     * @throws SearchUnavailableException if the search engine does not answer
     */
    public DocumentSearchPage next(DocumentSearchPage page) {
        return page(page.result(), page.rest());
    }

    /**
     * Returns at most {@code max} documents with a word that begins with a word of the text of three characters or
     * more, or is a form of it, the most relevant first. If a word left is longer than eight characters, each word left
     * is matched as a beginning of the stemmed words instead.
     *
     * @throws SearchUnavailableException if the search engine does not answer
     * @throws IllegalArgumentException   if {@code max} is negative
     */
    public List<DocumentSearchHit> first(String text, int max) {
        if (max < 0) {
            throw new IllegalArgumentException("Negative max: " + max);
        }

        List<String> beginnings = wordsThenBeginnings.beginningsOf(text);

        if (beginnings.isEmpty()) {
            return List.of();
        }

        SearchContext context = new SearchContext(String.join(" ", beginnings))
                .setEntities(documentClass().getName())
                .setSize(PAGE_SIZE + 1);

        SearchResult result = engine(() -> entitySearcher.search(context, PREFIX_STRATEGY));
        List<SearchResultEntry> entries = List.copyOf(result.getAllEntries());
        Map<UUID, Document> documents = load(entries, DocumentSearch::firstFetchPlan);
        Map<UUID, List<String>> paths = new HashMap<>();

        return entries.stream()
                .filter(entry -> documents.containsKey(idOf(entry)))
                .limit(max)
                .map(entry -> nameHit(documents.get(idOf(entry)), entry, paths))
                .toList();
    }

    /**
     * Reads the document again as the search shows it, or nothing when the user may no longer see it.
     */
    public Optional<Document> reload(Document document) {
        return dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(DocumentSearch::fetchPlan)
                .optional();
    }

    /**
     * Returns up to {@code max} live documents with the tag the user may see, from {@code first}, latest changed first.
     */
    public List<DocumentSearchHit> findTagged(Tag tag, int first, int max) {
        Map<UUID, List<String>> paths = new HashMap<>();

        return dataManager.load(Document.class)
                // the id makes the order total, so that the next page neither repeats nor skips a document
                .query("select d from Document d join d.tags t where t = :tag"
                        + " order by d.changedDate desc, d.name, d.id")
                .parameter("tag", tag)
                .firstResult(first)
                .maxResults(max)
                .fetchPlan(DocumentSearch::fetchPlan)
                .list()
                .stream()
                .map(document -> new DocumentSearchHit(document, pathOf(document.getFolder(), paths), null, null))
                .toList();
    }

    private DocumentSearchPage page(SearchResult result, List<DocumentSearchHit> found) {
        List<DocumentSearchHit> hits = new ArrayList<>(found);
        SearchResult last = result;

        while (hits.size() <= PAGE_SIZE && last.isMoreDataAvailable()) {
            SearchResult previous = last;
            last = engine(() -> entitySearcher.searchNextPage(previous));
            hits.addAll(hitsOf(last));
        }

        int shown = Math.min(PAGE_SIZE, hits.size());

        return new DocumentSearchPage(hits.subList(0, shown), hits.subList(shown, hits.size()), last);
    }

    private List<DocumentSearchHit> hitsOf(SearchResult result) {
        List<SearchResultEntry> entries = List.copyOf(result.getAllEntries());
        Map<UUID, Document> documents = load(entries, DocumentSearch::fetchPlan);
        Map<UUID, List<String>> paths = new HashMap<>();

        return entries.stream()
                .filter(entry -> documents.containsKey(idOf(entry)))
                .map(entry -> hit(documents.get(idOf(entry)), entry, paths))
                .toList();
    }

    // ids() fails on a document that is deleted or hidden from the user, a query just leaves it out
    private Map<UUID, Document> load(List<SearchResultEntry> entries, Consumer<FetchPlanBuilder> fetchPlan) {
        if (entries.isEmpty()) {
            return Map.of();
        }

        return dataManager.load(Document.class)
                .query("select d from Document d where d.id in :ids")
                .parameter("ids", entries.stream().map(this::idOf).toList())
                .fetchPlan(fetchPlan)
                .list()
                .stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));
    }

    private DocumentSearchHit hit(Document document, SearchResultEntry entry, Map<UUID, List<String>> paths) {
        List<String> path = pathOf(document.getFolder(), paths);
        FieldHit snippet = snippetOf(entry.getFieldHits());

        return snippet == null
                ? new DocumentSearchHit(document, path, null, null)
                : new DocumentSearchHit(document, path, caption(fieldOf(snippet)), snippet.getHighlights());
    }

    private DocumentSearchHit nameHit(Document document, SearchResultEntry entry, Map<UUID, List<String>> paths) {
        List<String> path = pathOf(document.getFolder(), paths);
        FieldHit name = nameOf(entry.getFieldHits());

        return name == null
                ? new DocumentSearchHit(document, path, null, null)
                : new DocumentSearchHit(document, path, caption(NAME), name.getHighlights());
    }

    private List<String> pathOf(Folder folder, Map<UUID, List<String>> paths) {
        return paths.computeIfAbsent(folder.getId(), id -> folderService.getPath(folder).stream()
                .map(metadataTools::getInstanceName)
                .toList());
    }

    private static @Nullable FieldHit snippetOf(List<FieldHit> fieldHits) {
        Map<Boolean, List<FieldHit>> byBeginnings = fieldHits.stream()
                .collect(Collectors.partitioningBy(fieldHit -> fieldHit.getFieldName().endsWith(BEGINNINGS)));

        FieldHit word = snippetIn(byBeginnings.get(false));

        return word != null ? word : snippetIn(byBeginnings.get(true));
    }

    private static @Nullable FieldHit snippetIn(List<FieldHit> fieldHits) {
        for (String field : SNIPPET_ORDER) {
            for (FieldHit fieldHit : fieldHits) {
                if (field.equals(fieldOf(fieldHit))) {
                    return fieldHit;
                }
            }
        }

        return fieldHits.stream()
                .filter(fieldHit -> fieldHit.getFieldName().startsWith("+"))
                .findFirst()
                .orElse(null);
    }

    private static String fieldOf(FieldHit fieldHit) {
        String field = Strings.CS.removeEnd(fieldHit.getFieldName(), BEGINNINGS);

        return Strings.CS.removeEnd(field, INSTANCE_NAMES);
    }

    private static @Nullable FieldHit nameOf(List<FieldHit> fieldHits) {
        for (String field : NAME_FIELDS) {
            for (FieldHit fieldHit : fieldHits) {
                if (field.equals(fieldHit.getFieldName())) {
                    return fieldHit;
                }
            }
        }

        return null;
    }

    private String caption(String field) {
        return switch (field) {
            case FILE_TEXT -> messages.getMessage(DocumentSearch.class, "fileText");
            case FILE_NAME -> messages.getMessage(DocumentSearch.class, "fileName");
            default -> messageTools.getPropertyCaption(documentClass(), field);
        };
    }

    private UUID idOf(SearchResultEntry entry) {
        return (UUID) idSerialization.stringToId(entry.getDocId()).getValue();
    }

    private MetaClass documentClass() {
        return metadata.getClass(Document.class);
    }

    private static SearchResult engine(Supplier<SearchResult> search) {
        try {
            return search.get();
        } catch (RuntimeException e) {
            log.warn("The search engine does not answer", e);
            throw new SearchUnavailableException(e);
        }
    }

    private static void fetchPlan(FetchPlanBuilder fetchPlan) {
        fetchPlan.addFetchPlan(FetchPlan.BASE)
                .add("category", FetchPlan.INSTANCE_NAME)
                .add("folder", FetchPlan.INSTANCE_NAME)
                .add("status", FetchPlan.BASE)
                .add("tags", FetchPlan.BASE);
    }

    // the palette shows the icon of the type of the file
    private static void firstFetchPlan(FetchPlanBuilder fetchPlan) {
        fetchPlan(fetchPlan);
        fetchPlan.add("currentRevision", revision -> revision.add("contentType"));
    }
}
