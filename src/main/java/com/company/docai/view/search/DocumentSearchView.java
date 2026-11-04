package com.company.docai.view.search;

import com.company.docai.ai.AiSettings;
import com.company.docai.app.service.tag.TagService;
import com.company.docai.model.document.Document;
import com.company.docai.model.tag.Tag;
import com.company.docai.search.DocumentSearch;
import com.company.docai.search.DocumentSearchHit;
import com.company.docai.search.DocumentSearchPage;
import com.company.docai.search.SearchUnavailableException;
import com.company.docai.search.semantic.DocumentVectorIndexer;
import com.company.docai.search.semantic.SemanticSearch;
import com.company.docai.view.main.MainView;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import io.jmix.flowui.Fragments;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.asynctask.UiAsyncTasks;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * The documents found by the words from the address, the most relevant first, a page at a time, or in the
 * {@link #AI_MODE} by meaning: at most {@link SemanticSearch#PAGE_DOCUMENTS} of the nearest documents that a model
 * confirmed, the nearest first. With a {@link #TAG_PARAMETER} the page lists the documents with the tag instead, the
 * latest changed first. Every row shows the folder, the type, the status, the tags and the matched text of a document
 * the user may see; how many other documents match is never shown.
 */
@Route(value = "search", layout = MainView.class)
@ViewController(id = "DocumentSearchView")
@ViewDescriptor(path = "document-search-view.xml")
public class DocumentSearchView extends StandardView {

    /**
     * The address parameter with the words to search for.
     */
    public static final String QUERY_PARAMETER = "q";

    /**
     * The address parameter with the mode of the search: {@link #AI_MODE} or none, for the search by words.
     */
    public static final String MODE_PARAMETER = "mode";

    /**
     * The mode of the search by meaning.
     */
    public static final String AI_MODE = "ai";

    /**
     * The address parameter with the name of a tag, whose documents the page lists.
     */
    public static final String TAG_PARAMETER = "tag";

    private static final Logger log = LoggerFactory.getLogger(DocumentSearchView.class);

    @ViewComponent
    private Span messageText;
    @ViewComponent
    private Span foundText;
    @ViewComponent
    private Span noteText;
    @ViewComponent
    private JmixButton searchByWordsButton;
    @ViewComponent
    private VerticalLayout hitsBox;
    @ViewComponent
    private JmixButton showMoreButton;
    @ViewComponent
    private MessageBundle messageBundle;

    @Autowired
    private DocumentSearch documentSearch;
    @Autowired
    private TagService tagService;
    @Autowired
    private SemanticSearch semanticSearch;
    @Autowired
    private DocumentVectorIndexer vectorIndexer;
    @Autowired
    private AiSettings aiSettings;
    @Autowired
    private UiAsyncTasks uiAsyncTasks;
    @Autowired
    private ViewNavigators viewNavigators;
    @Autowired
    private Fragments fragments;

    @Nullable
    private DocumentSearchPage lastPage;

    @Nullable
    private Tag shownTag;
    private int taggedShown;

    private String query = "";

    // the search by meaning answers in seconds, and the answer to a query the person has replaced is dropped
    private int searchNumber;

    /**
     * Returns the trimmed words to search for from the address parameters, or an empty string.
     */
    public static String queryOf(QueryParameters parameters) {
        return parameters.getSingleParameter(QUERY_PARAMETER)
                .map(String::strip)
                .orElse("");
    }

    /**
     * Returns whether the address parameters ask for the search by meaning.
     */
    public static boolean isAiMode(QueryParameters parameters) {
        return parameters.getSingleParameter(MODE_PARAMETER)
                .map(AI_MODE::equals)
                .orElse(false);
    }

    @Subscribe
    public void onQueryParametersChange(final QueryParametersChangeEvent event) {
        searchNumber++;
        hitsBox.removeAll();
        lastPage = null;
        shownTag = null;
        setPageTitle(messageBundle.getMessage("documentSearchView.title"));

        showMoreButton.setVisible(false);
        searchByWordsButton.setVisible(false);
        messageText.setVisible(false);
        foundText.setVisible(false);
        noteText.setVisible(false);
        query = queryOf(event.getQueryParameters());

        Optional<String> tag = event.getQueryParameters().getSingleParameter(TAG_PARAMETER)
                .map(String::strip)
                .filter(name -> !name.isEmpty());

        if (tag.isPresent()) {
            showTag(tag.get());
            return;
        }

        if (query.isEmpty()) {
            showMessage("enterQuery");
            return;
        }

        if (isAiMode(event.getQueryParameters()) && aiSettings.isAvailable()) {
            searchByMeaning(query);
            return;
        }

        if (isAiMode(event.getQueryParameters())) {
            showNote("aiSearchOff");
        }

        String words = query;
        load(() -> documentSearch.search(words));
    }

    @Subscribe(id = "searchByWordsButton", subject = "clickListener")
    public void onSearchByWordsButtonClick(final ClickEvent<JmixButton> event) {
        viewNavigators.view(this, DocumentSearchView.class)
                .withQueryParameters(QueryParameters.of(QUERY_PARAMETER, query))
                .navigate();
    }

    @Subscribe(id = "showMoreButton", subject = "clickListener")
    public void onShowMoreButtonClick(final ClickEvent<JmixButton> event) {
        DocumentSearchPage page = lastPage;

        if (shownTag != null) {
            loadTagged(shownTag);
        } else if (page != null) {
            load(() -> documentSearch.next(page));
        }
    }

    private void showTag(String name) {
        Tag tag = tagService.find(name)
                .orElse(null);

        if (tag == null) {
            messageText.setText(messageBundle.formatMessage("tagNotFound", name));
            messageText.setVisible(true);
            return;
        }

        shownTag = tag;
        taggedShown = 0;
        setPageTitle(messageBundle.formatMessage("tagTitle", tag.getName()));
        loadTagged(tag);

        // the documents in the trash are out of sight, so the page says where they are
        if (hitsBox.getComponentCount() == 0) {
            messageText.setText(messageBundle.formatMessage(
                    tagService.isInTrash(tag) ? "taggedOnlyInTrash" : "nothingTagged", tag.getName()));
            messageText.setVisible(true);
        }
    }

    // one document more than the page proves that the next page is not empty
    private void loadTagged(Tag tag) {
        List<DocumentSearchHit> hits = documentSearch.findTagged(tag, taggedShown, DocumentSearch.PAGE_SIZE + 1);

        hits.stream()
                .limit(DocumentSearch.PAGE_SIZE)
                .forEach(hit -> hitsBox.add(createHitRow(hit)));

        taggedShown += DocumentSearch.PAGE_SIZE;
        showMoreButton.setVisible(hits.size() > DocumentSearch.PAGE_SIZE);
    }

    private void load(Supplier<DocumentSearchPage> search) {
        DocumentSearchPage page;

        try {
            page = search.get();
        } catch (SearchUnavailableException e) {
            showMoreButton.setVisible(false);
            showMessage("searchUnavailable");
            return;
        }

        lastPage = page;
        page.hits().forEach(hit -> hitsBox.add(createHitRow(hit)));
        showMoreButton.setVisible(page.hasMore());

        if (hitsBox.getComponentCount() == 0) {
            showMessage("nothingFound");
        }
    }

    private void searchByMeaning(String text) {
        int number = searchNumber;
        showMessage("searchingByMeaning");

        uiAsyncTasks.supplierConfigurer(() -> semanticSearch.search(text))
                .withResultHandler(hits -> {
                    if (number == searchNumber) {
                        showMeaningHits(hits);
                    }
                })
                .withExceptionHandler(e -> {
                    if (number == searchNumber) {
                        showMeaningFailure(e);
                    }
                })
                .supplyAsync();
    }

    private void showMeaningHits(List<DocumentSearchHit> hits) {
        messageText.setVisible(false);

        if (hits.isEmpty()) {
            showMessage("nothingFoundByMeaning");
            searchByWordsButton.setVisible(true);
        } else {
            foundText.setText(messageBundle.formatMessage("foundCount", hits.size()));
            foundText.setVisible(true);
            hits.forEach(hit -> hitsBox.add(createHitRow(hit)));
        }

        if (vectorIndexer.isBusy()) {
            showNote("indexFilling");
        }
    }

    private void showMeaningFailure(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;

        if (cause instanceof SearchUnavailableException) {
            showMessage("searchUnavailable");
            return;
        }

        log.warn("The search by meaning failed", cause);
        showMessage("aiSearchFailed");
        searchByWordsButton.setVisible(true);
    }

    private void showMessage(String key) {
        messageText.setText(messageBundle.getMessage(key));
        messageText.setVisible(true);
    }

    private void showNote(String key) {
        noteText.setText(messageBundle.getMessage(key));
        noteText.setVisible(true);
    }

    private Component createHitRow(DocumentSearchHit hit) {
        DocumentSearchHitFragment row = fragments.create(this, DocumentSearchHitFragment.class);
        row.setHit(hit);
        row.setAfterSaveHandler(() -> showSaved(hit, row));

        return row;
    }

    // the row shows the document as the card saved it; one the user may no longer see, or that lost the tag
    // of the page, leaves the page
    private void showSaved(DocumentSearchHit hit, Component row) {
        Document saved = documentSearch.reload(hit.document())
                .orElse(null);

        if (saved == null || (shownTag != null && !saved.getTags().contains(shownTag))) {
            hitsBox.remove(row);
            return;
        }

        hitsBox.replace(row, createHitRow(new DocumentSearchHit(saved, hit.folderPath(), hit.field(), hit.snippet())));
    }
}
