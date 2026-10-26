package com.company.dms.view.search;

import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentStatus;
import com.company.dms.search.DocumentSearch;
import com.company.dms.search.DocumentSearchHit;
import com.company.dms.search.DocumentSearchPage;
import com.company.dms.search.SearchUnavailableException;
import com.company.dms.view.document.DocumentDetailView;
import com.company.dms.view.main.MainView;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.function.Supplier;

/**
 * The documents found by the words from the address, the most relevant first, a page at a time. Every row
 * shows the folder, the type, the status and the matched text of a document the user may see; how many other
 * documents match is never shown.
 */
@Route(value = "search", layout = MainView.class)
@ViewController(id = "DocumentSearchView")
@ViewDescriptor(path = "document-search-view.xml")
public class DocumentSearchView extends StandardView {

    /**
     * The address parameter with the words to search for.
     */
    public static final String QUERY_PARAMETER = "q";

    private static final String MATCH_START = "<b>";
    private static final String MATCH_END = "</b>";

    @ViewComponent
    private Span messageText;
    @ViewComponent
    private VerticalLayout hitsBox;
    @ViewComponent
    private JmixButton showMoreButton;
    @ViewComponent
    private MessageBundle messageBundle;

    @Autowired
    private DocumentSearch documentSearch;
    @Autowired
    private DataManager dataManager;
    @Autowired
    private DialogWindows dialogWindows;
    @Autowired
    private Notifications notifications;
    @Autowired
    private UiComponents uiComponents;
    @Autowired
    private Icons icons;
    @Autowired
    private Messages messages;
    @Autowired
    private MetadataTools metadataTools;

    @Nullable
    private DocumentSearchPage lastPage;

    /**
     * Returns the trimmed words to search for from the address parameters, or an empty string.
     */
    public static String queryOf(QueryParameters parameters) {
        return parameters.getSingleParameter(QUERY_PARAMETER)
                .map(String::strip)
                .orElse("");
    }

    @Subscribe
    public void onQueryParametersChange(final QueryParametersChangeEvent event) {
        hitsBox.removeAll();
        lastPage = null;

        showMoreButton.setVisible(false);
        messageText.setVisible(false);
        String query = queryOf(event.getQueryParameters());

        if (query.isEmpty()) {
            showMessage("enterQuery");
            return;
        }

        load(() -> documentSearch.search(query));
    }

    @Subscribe(id = "showMoreButton", subject = "clickListener")
    public void onShowMoreButtonClick(final ClickEvent<JmixButton> event) {
        DocumentSearchPage page = lastPage;

        if (page != null) {
            load(() -> documentSearch.next(page));
        }
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

    private void showMessage(String key) {
        messageText.setText(messageBundle.getMessage(key));
        messageText.setVisible(true);
    }

    private Component createHitRow(DocumentSearchHit hit) {
        Document document = hit.document();

        JmixButton name = uiComponents.create(JmixButton.class);
        name.setText(document.getName());
        name.setIcon(icons.get(JmixFontIcon.FILE_TEXT));
        name.addClassName("document-search-name");
        name.addThemeVariants(ButtonVariant.TERTIARY);
        name.addClickListener(event -> openCard(document));

        Div title = uiComponents.create(Div.class);
        title.addClassName("document-search-title");
        title.add(name);

        if (document.getCategory() != null) {
            title.add(badge(metadataTools.getInstanceName(document.getCategory()), "v-neutral"));
        }

        DocumentStatus status = document.getStatus();
        title.add(badge(messages.getMessage(status), DocumentDetailView.statusColor(status)));

        Span path = uiComponents.create(Span.class);
        path.addClassName("document-search-path");
        path.setText(String.join(" / ", hit.folderPath()));

        Div row = uiComponents.create(Div.class);
        row.addClassName("document-search-hit");
        row.add(title, path);

        if (hit.snippet() != null) {
            row.add(createSnippet(hit.field(), hit.snippet()));
        }

        return row;
    }

    private Badge badge(String text, String colorClass) {
        Badge badge = uiComponents.create(Badge.class);
        badge.setText(text);
        badge.addClassName(colorClass);
        return badge;
    }

    /**
     * Builds the matched text from text nodes and match spans: the text comes from the users, so it is never
     * put into the page as HTML.
     */
    private Div createSnippet(@Nullable String field, String snippet) {
        Div div = uiComponents.create(Div.class);
        div.addClassName("document-search-snippet");

        if (field != null) {
            Span caption = uiComponents.create(Span.class);
            caption.addClassName("document-search-field");
            caption.setText(field);
            div.add(caption);
        }

        int position = 0;

        while (position < snippet.length()) {
            int start = snippet.indexOf(MATCH_START, position);
            int end = start < 0 ? -1 : snippet.indexOf(MATCH_END, start);

            if (end < 0) {
                div.add(new Text(snippet.substring(position)));
                break;
            }

            div.add(new Text(snippet.substring(position, start)));

            Span match = uiComponents.create(Span.class);
            match.addClassName("document-search-match");
            match.setText(snippet.substring(start + MATCH_START.length(), end));
            div.add(match);

            position = end + MATCH_END.length();
        }

        return div;
    }

    private void openCard(Document document) {
        // the card fails on a document deleted or hidden from the user since the search
        if (dataManager.load(Id.of(document)).optional().isEmpty()) {
            notifications.create(messages.getMessage(DocumentDetailView.class, "documentUnavailable"))
                    .withType(Notifications.Type.WARNING)
                    .show();
            return;
        }

        dialogWindows.detail(this, Document.class)
                .withViewClass(DocumentDetailView.class)
                .editEntity(document)
                .open();
    }
}
