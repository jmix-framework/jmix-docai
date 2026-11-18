package com.company.docai.view.search;

import com.company.docai.ai.AiSettings;
import com.company.docai.app.service.favorite.FavoriteService;
import com.company.docai.app.service.folder.FolderIndex;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.service.folder.FolderTree;
import com.company.docai.app.service.tag.TagCount;
import com.company.docai.app.service.tag.TagService;
import com.company.docai.app.ui.component.searchpalette.SearchPaletteLine;
import com.company.docai.app.ui.component.searchpalette.SearchPaletteMotion;
import com.company.docai.model.document.Document;
import com.company.docai.model.favorite.FavoriteItem;
import com.company.docai.model.folder.Folder;
import com.company.docai.search.DocumentSearch;
import com.company.docai.search.DocumentSearchHit;
import com.company.docai.search.SearchUnavailableException;
import com.company.docai.view.assistant.AssistantNavigation;
import com.company.docai.view.folder.FolderFilter;
import com.company.docai.view.main.MainView;
import com.company.docai.view.search.RecentQueries.RecentQuery;
import com.google.common.base.Strings;
import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.FocusNotifier;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.KeyModifier;
import com.vaadin.flow.component.ScrollIntoViewOption;
import com.vaadin.flow.component.Shortcuts;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.AfterNavigationEvent;
import io.jmix.core.Messages;
import io.jmix.flowui.OpenedDialogWindows;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.StandardOutcome;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.View;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewControllerUtils;
import io.jmix.flowui.view.ViewDescriptor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The search palette, a dialog the header's search field opens; the main view gives it what each choice opens.
 */
@ViewController(id = "SearchPaletteView")
@ViewDescriptor(path = "search-palette-view.xml")
@DialogMode(width = "48em", top = "12vh", draggable = false, closeOnEsc = true, closeOnOutsideClick = true)
public class SearchPaletteView extends StandardView {

    private static final int MIN_QUERY_LENGTH = 2;
    private static final int FAVORITE_ROWS = 5;

    private static final String ROW_ID = "search-palette-row-";
    private static final String HEADING_ID = "search-palette-heading-";

    @ViewComponent
    private TypedTextField<String> queryField;
    @ViewComponent
    private JmixButton searchModeButton;
    @ViewComponent
    private Div panel;
    @ViewComponent
    private JmixButton allChip;
    @ViewComponent
    private JmixButton documentsChip;
    @ViewComponent
    private JmixButton foldersChip;
    @ViewComponent
    private JmixButton tagsChip;
    @ViewComponent
    private Span chipStatus;
    @ViewComponent
    private Span messageText;
    @ViewComponent
    private Div resultsBox;
    @ViewComponent
    private Div hintsBox;
    @ViewComponent
    private Span removeHint;
    @ViewComponent
    private SearchPaletteMotion motion;
    @ViewComponent
    private SearchPaletteLine line;
    @ViewComponent
    private MessageBundle messageBundle;

    @Autowired
    private Messages messages;
    @Autowired
    private AiSettings aiSettings;
    @Autowired
    private AssistantNavigation assistantNavigation;
    @Autowired
    private RecentQueries recentQueries;
    @Autowired
    private FavoriteService favoriteService;
    @Autowired
    private DocumentSearch documentSearch;
    @Autowired
    private FolderService folderService;
    @Autowired
    private FolderFilter folderFilter;
    @Autowired
    private TagService tagService;
    @Autowired
    private SearchRenderers searchRenderers;
    @Autowired
    private OpenedDialogWindows openedDialogWindows;

    @Nullable
    private Component origin;
    private String query = "";
    private int opening;
    private boolean aiMode;
    private Consumer<Boolean> modeChangeHandler = ai -> {
    };
    private BiConsumer<String, Boolean> searchHandler = (text, ai) -> {
    };
    private Consumer<String> assistantHandler = question -> {
    };
    private Consumer<Document> documentOpenHandler = document -> {
    };
    private Consumer<Folder> folderOpenHandler = folder -> {
    };
    private Consumer<String> tagOpenHandler = tag -> {
    };
    @Nullable
    private MenuFolders menuFolders;

    private final Map<Chip, JmixButton> chipButtons = new EnumMap<>(Chip.class);
    private Chip chip = Chip.ALL;
    // the text, without the spaces around it, that the list shows; none before the first list
    @Nullable
    private String shownText;

    private boolean assistantAvailable;
    private List<RecentQuery> recent = List.of();
    private List<FavoriteItem> favorites = List.of();
    private final List<Row> rows = new ArrayList<>();
    // the places of the rows of the recent searches among the rows, in the order of the searches
    private final List<Integer> recentRows = new ArrayList<>();
    private int selected = -1;
    private int groups;

    // one choice per palette: a second click or Enter that was already on its way when the palette closed does nothing
    private boolean chosen;

    /**
     * Sets the field the line flies out of and back into; without one the palette opens and closes in place.
     */
    public void setOrigin(@Nullable Component origin) {
        this.origin = origin;
    }

    /**
     * Sets the text the line opens with: the text of the field in the header.
     */
    public void setQuery(@Nullable String query) {
        this.query = Strings.nullToEmpty(query);
    }

    /**
     * Sets the number the browser gave the request that opens the palette, so that a palette whose opening an Esc
     * cancelled closes as it comes.
     */
    public void setOpening(int opening) {
        this.opening = opening;
    }

    /**
     * Sets the mode the palette opens in, as the wand of the header shows it: true for the search by meaning.
     */
    public void setAiMode(boolean aiMode) {
        this.aiMode = aiMode;
    }

    /**
     * Receives the new mode after a click on the wand of the palette: true for the search by meaning.
     */
    public void setModeChangeHandler(Consumer<Boolean> modeChangeHandler) {
        this.modeChangeHandler = modeChangeHandler;
    }

    /**
     * Receives the text and the mode to open the search page with, true for the search by meaning.
     */
    public void setSearchHandler(BiConsumer<String, Boolean> searchHandler) {
        this.searchHandler = searchHandler;
    }

    /**
     * Receives a question for the assistant, to open its chat with the question as the first message.
     */
    public void setAssistantHandler(Consumer<String> assistantHandler) {
        this.assistantHandler = assistantHandler;
    }

    /**
     * Receives a document to open in its card over the palette; it may have been deleted or hidden since.
     */
    public void setDocumentOpenHandler(Consumer<Document> documentOpenHandler) {
        this.documentOpenHandler = documentOpenHandler;
    }

    /**
     * Receives a folder to open in the tree of the main menu.
     */
    public void setFolderOpenHandler(Consumer<Folder> folderOpenHandler) {
        this.folderOpenHandler = folderOpenHandler;
    }

    /**
     * Receives the name of a tag to open the page of its documents.
     */
    public void setTagOpenHandler(Consumer<String> tagOpenHandler) {
        this.tagOpenHandler = tagOpenHandler;
    }

    @Subscribe
    public void onInit(final InitEvent event) {
        chipButtons.put(Chip.ALL, allChip);
        chipButtons.put(Chip.DOCUMENTS, documentsChip);
        chipButtons.put(Chip.FOLDERS, foldersChip);
        chipButtons.put(Chip.TAGS, tagsChip);
        searchModeButton.setVisible(aiSettings.isAvailable());

        assistantAvailable = assistantNavigation.isAvailable();

        line.setField(queryField);
        line.setList(resultsBox);
        line.addKeyListener(this::onQueryKey);

        // the shortcut of the header does nothing over the palette, and the browser would take it
        for (KeyModifier modifier : List.of(KeyModifier.META, KeyModifier.CONTROL)) {
            Shortcuts.addShortcutListener(this, () -> {
                    }, Key.KEY_K, modifier)
                    .listenOn(queryField);
        }

        resultsBox.getElement().setAttribute("role", "listbox");
        resultsBox.getElement().setAttribute("aria-label", messageBundle.getMessage("resultsBox.ariaLabel"));
        messageText.getElement().setAttribute("role", "status");
        chipStatus.getElement().setAttribute("aria-live", "polite");
        hintsBox.getElement().setAttribute("aria-hidden", "true");
    }

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        recent = recentQueries.load();
        favorites = firstFavorites();
        showSearchMode();
        showChips();
        queryField.setValue(query);

        // a text the field had already sends no value change
        if (shownText == null) {
            showList(query.strip());
        }
    }

    // the window sends it once the dialog is open, but the dialog reaches the page only before the response: the calls
    // wait for it in one task and keep their order there, the keys first, then the flight
    @Subscribe
    public void onReady(final ReadyEvent event) {
        if (origin != null) {
            Component headerField = origin;

            UI.getCurrent().beforeClientResponse(this, context -> {
                line.takeOver(headerField, opening);
                motion.open(headerField, queryField, panel);
            });
        }
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        // the palette is always a dialog, and a closed dialog stays on the page until the browser has played
        // the closing: the navigation a choice starts would announce the closed palette as opened and ready again
    }

    @Subscribe("queryField")
    public void onQueryFieldComponentValueChange(
            final AbstractField.ComponentValueChangeEvent<TypedTextField<String>, String> event) {
        String text = Strings.nullToEmpty(event.getValue()).strip();

        if (!text.equals(shownText)) {
            showList(text);
        }
    }

    // the field selects its whole text on every focus, and only the opening asks for that: the focus that comes back
    // later, after a card over the palette or when the browser focuses the page again, keeps the caret where it is
    @Subscribe("queryField")
    public void onQueryFieldFocus(final FocusNotifier.FocusEvent<TypedTextField<String>> event) {
        queryField.setAutoselect(false);
        event.unregisterListener();
    }

    @Subscribe(id = "searchModeButton", subject = "clickListener")
    public void onSearchModeButtonClick(final ClickEvent<JmixButton> event) {
        aiMode = !aiMode;
        modeChangeHandler.accept(aiMode);
        showSearchMode();
        showList(Strings.nullToEmpty(shownText));
        line.focusAtEnd();
    }

    @Subscribe(id = "allChip", subject = "clickListener")
    public void onAllChipClick(final ClickEvent<JmixButton> event) {
        chooseChip(Chip.ALL);
    }

    @Subscribe(id = "documentsChip", subject = "clickListener")
    public void onDocumentsChipClick(final ClickEvent<JmixButton> event) {
        chooseChip(Chip.DOCUMENTS);
    }

    @Subscribe(id = "foldersChip", subject = "clickListener")
    public void onFoldersChipClick(final ClickEvent<JmixButton> event) {
        chooseChip(Chip.FOLDERS);
    }

    @Subscribe(id = "tagsChip", subject = "clickListener")
    public void onTagsChipClick(final ClickEvent<JmixButton> event) {
        chooseChip(Chip.TAGS);
    }

    private void onQueryKey(SearchPaletteLine.KeyEvent event) {
        String text = event.getText().strip();

        // the line sends its text after a pause: an arrow goes through the list of the text the line shows
        if (event.getKey().startsWith("Arrow") && !text.equals(shownText)) {
            showList(text);
        }

        switch (event.getKey()) {
            case "ArrowDown" -> select(selected + 1 < rows.size() ? selected + 1 : -1);
            case "ArrowUp" -> select(selected < 0 ? rows.size() - 1 : selected - 1);
            case "Tab" -> chooseChip(chip.next(event.isShift()), text);
            case "Enter" -> enter(event.getText());
            case "Delete" -> {
                if (event.isShift()) {
                    removeSelectedRecent();
                }
            }
        }
    }

    // the text of the key is what the line showed at the press: a row counts only in the list built for that text
    private void enter(String text) {
        String stripped = text.strip();

        if (selected >= 0 && stripped.equals(shownText)) {
            rows.get(selected).choice().run();
        } else if (!stripped.isEmpty()) {
            choose(() -> searchHandler.accept(stripped, aiMode));
        }
    }

    private void chooseChip(Chip pressed) {
        chooseChip(pressed, Strings.nullToEmpty(shownText));
    }

    private void chooseChip(Chip pressed, String text) {
        chip = pressed;
        showChips();
        chipStatus.setText(chipButtons.get(pressed).getText());
        showList(text);
        line.focusAtEnd();
    }

    // the palette closes first, so that the focus it gives back reaches the header before the destination takes it
    private void choose(Runnable action) {
        if (chosen || covered()) {
            return;
        }

        chosen = true;
        close(StandardOutcome.SELECT);
        action.run();
    }

    private void openCard(Document document) {
        if (chosen || covered()) {
            return;
        }

        documentOpenHandler.accept(document);

        Optional<View<?>> card = openedDialogWindows.getCurrentDialog()
                .filter(dialog -> dialog != this);

        if (card.isEmpty()) {
            showWhatIsLeft();
            return;
        }

        // a click on a row leaves the focus on the dialog, and the card takes it anyway: the line gets it back
        ViewControllerUtils.addAfterCloseListener(card.get(), closeEvent -> line.focusAtEnd());
    }

    // the palette takes a choice only while it is the dialog on top: a second click or Enter of the same choice finds
    // the card of the document over it
    private boolean covered() {
        return openedDialogWindows.getCurrentDialog()
                .filter(dialog -> dialog == this)
                .isEmpty();
    }

    private void showWhatIsLeft() {
        favorites = firstFavorites();
        showList(Strings.nullToEmpty(shownText));
    }

    private List<FavoriteItem> firstFavorites() {
        return favoriteService.loadItems()
                .stream()
                .limit(FAVORITE_ROWS)
                .toList();
    }

    private void removeSelectedRecent() {
        int place = recentRows.indexOf(selected);

        if (place >= 0) {
            removeRecent(recent.get(place));
        }
    }

    private void removeRecent(RecentQuery query) {
        int place = recentRows.indexOf(selected);
        boolean selectedRemoved = place >= 0 && recent.get(place).equals(query);

        recentQueries.remove(query.text(), query.ai());
        recent = recentQueries.load();
        showList(Strings.nullToEmpty(shownText));

        if (selectedRemoved && !recentRows.isEmpty()) {
            select(recentRows.get(Math.min(place, recentRows.size() - 1)));
        }

        showStatus(messageBundle.formatMessage("recentRemoved", query.text()), false);
    }

    private void showList(String text) {
        shownText = text;
        rows.clear();
        recentRows.clear();
        selected = -1;
        groups = 0;
        resultsBox.removeAll();
        showStatus("", false);

        if (text.length() < MIN_QUERY_LENGTH) {
            showSections();
        } else {
            showResults(text);
        }

        line.setActiveRow(null);
        removeHint.setVisible(!recentRows.isEmpty());
    }

    private void showSections() {
        addGroup(messageBundle.getMessage("recentSection.title"), recent.stream()
                .map(this::recentRow)
                .toList());

        addGroup(messageBundle.getMessage("favoritesSection.title"), favorites.stream()
                .map(this::favoriteRow)
                .toList());

        if (assistantAvailable) {
            String first = messageBundle.getMessage("aiQuestion1.text");
            String second = messageBundle.getMessage("aiQuestion2.text");

            addGroup(messageBundle.getMessage("askAiSection.title"),
                    List.of(assistantRow(first, first), assistantRow(second, second)));
        }
    }

    private void showResults(String text) {
        if (shows(Chip.DOCUMENTS)) {
            showDocuments(text, chip.rowLimit);
        }

        if (shows(Chip.FOLDERS)) {
            MenuFolders folders = menuFolders();

            List<Div> folderRows = folderFilter.findByName(folders.inTreeOrder(), text, chip.rowLimit)
                    .stream()
                    .map(folder -> folderRow(folder, folders))
                    .toList();

            addGroup(messageBundle.getMessage("foldersGroup.title"), folderRows);
        }

        if (shows(Chip.TAGS)) {
            List<Div> tagRows = tagService.findByName(text, chip.rowLimit)
                    .stream()
                    .map(this::tagRow)
                    .toList();

            addGroup(messageBundle.getMessage("tagsGroup.title"), tagRows);
        }

        if (groups == 0) {
            showStatus(messageBundle.getMessage("nothingFound"), true);
        }

        resultsBox.add(allResultsRow(text));

        if (assistantAvailable) {
            resultsBox.add(assistantRow(messageBundle.formatMessage("askAiRow.text", text), text));
        }
    }

    private void showDocuments(String text, int limit) {
        String title = messageBundle.getMessage("documentsGroup.title");
        List<DocumentSearchHit> hits;

        try {
            hits = documentSearch.first(text, limit);
        } catch (SearchUnavailableException e) {
            String unavailable = messageBundle.getMessage("searchUnavailable");

            addGroup(title, List.of(searchRenderers.notice(unavailable)));
            showStatus(unavailable, false);

            return;
        }

        addGroup(title, hits.stream()
                .map(this::documentRow)
                .toList());
    }

    private boolean shows(Chip group) {
        return chip == Chip.ALL || chip == group;
    }

    private MenuFolders menuFolders() {
        if (menuFolders == null) {
            FolderTree tree = folderService.loadTree();
            FolderIndex index = FolderIndex.of(tree.folders());
            // as the tree of the menu shows them, each folder right after its parent: the tree loads them by name alone
            menuFolders = new MenuFolders(index.inTreeOrder(), tree.greyed(), index);
        }

        return menuFolders;
    }

    private Div documentRow(DocumentSearchHit hit) {
        Runnable choice = () -> openCard(hit.document());

        return register(searchRenderers.documentRow(hit, choice), choice);
    }

    private Div folderRow(Folder folder, MenuFolders folders) {
        Runnable choice = () -> choose(() -> folderOpenHandler.accept(folder));

        return register(searchRenderers.folderRow(folder, folders.index().pathOf(folder),
                folders.greyed().contains(folder.getId()), choice), choice);
    }

    private Div tagRow(TagCount tag) {
        Runnable choice = () -> choose(() -> tagOpenHandler.accept(tag.name()));

        return register(searchRenderers.tagRow(tag, choice), choice);
    }

    private Div recentRow(RecentQuery query) {
        Runnable choice = () -> choose(() -> searchHandler.accept(query.text(), query.ai()));

        // the click on the cross took the focus from the line
        Runnable remove = () -> {
            removeRecent(query);
            line.focusAtEnd();
        };

        Div row = register(searchRenderers.recentRow(query, choice, remove), choice);
        recentRows.add(rows.size() - 1);

        return row;
    }

    private Div favoriteRow(FavoriteItem item) {
        Runnable choice = item.getFolder() != null
                ? () -> choose(() -> folderOpenHandler.accept(item.getFolder()))
                : () -> openCard(item.getDocument());

        return register(searchRenderers.favoriteRow(item, choice), choice);
    }

    private Div assistantRow(String shown, String question) {
        Runnable choice = () -> choose(() -> assistantHandler.accept(question));

        return register(searchRenderers.assistantRow(shown, choice), choice);
    }

    private Div allResultsRow(String text) {
        Runnable choice = () -> choose(() -> searchHandler.accept(text, aiMode));

        return register(searchRenderers.allResultsRow(text, aiMode, choice), choice);
    }

    private void addGroup(String title, List<? extends Component> content) {
        if (content.isEmpty()) {
            return;
        }

        resultsBox.add(searchRenderers.group(HEADING_ID + groups++, title, content));
    }

    // the arrows go through the rows in the order they are made, which is their order on the screen
    private Div register(Div row, Runnable choice) {
        row.setId(ROW_ID + rows.size());
        rows.add(new Row(row, choice));

        return row;
    }

    private void select(int index) {
        if (selected >= 0) {
            showSelected(rows.get(selected).element(), false);
        }

        selected = index;

        if (selected >= 0) {
            Div row = rows.get(selected).element();
            showSelected(row, true);
            row.scrollIntoView(ScrollIntoViewOption.Block.NEAREST);
        }

        line.setActiveRow(selected < 0 ? null : ROW_ID + selected);
    }

    private static void showSelected(Div row, boolean selected) {
        row.setClassName("search-palette-row-active", selected);
        row.getElement().setAttribute("aria-selected", String.valueOf(selected));
    }

    private void showChips() {
        chipButtons.forEach((each, button) ->
                button.getElement().setAttribute("aria-pressed", String.valueOf(each == chip)));
    }

    private void showSearchMode() {
        searchModeButton.setTooltipText(messages.getMessage(MainView.class,
                aiMode ? "searchModeButton.on" : "searchModeButton.off"));

        searchModeButton.getElement().setAttribute("aria-pressed", String.valueOf(aiMode));

        // the hints of the header field, which the line flies out of and back into: no text changes in flight
        queryField.setPlaceholder(messages.getMessage(MainView.class,
                aiMode ? "searchField.aiPlaceholder" : "searchField.placeholder"));
    }

    // never hidden, only out of sight: a screen reader hears a status only from a region already on the page
    private void showStatus(String text, boolean shown) {
        messageText.setText(text);
        messageText.setClassName("search-palette-live", !shown);
    }

    // the chips in the order of Tab, each with the number of rows it shows of a group
    private enum Chip {

        ALL(5),
        DOCUMENTS(10),
        FOLDERS(10),
        TAGS(10);

        private final int rowLimit;

        Chip(int rowLimit) {
            this.rowLimit = rowLimit;
        }

        private Chip next(boolean back) {
            Chip[] chips = values();

            return chips[Math.floorMod(ordinal() + (back ? -1 : 1), chips.length)];
        }
    }

    private record Row(Div element, Runnable choice) {
    }

    private record MenuFolders(List<Folder> inTreeOrder, Set<UUID> greyed, FolderIndex index) {
    }
}
