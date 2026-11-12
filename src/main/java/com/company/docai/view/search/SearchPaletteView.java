package com.company.docai.view.search;

import com.company.docai.ai.AiSettings;
import com.company.docai.app.service.favorite.FavoriteService;
import com.company.docai.app.service.folder.FolderIndex;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.service.folder.FolderTree;
import com.company.docai.app.service.tag.TagCount;
import com.company.docai.app.service.tag.TagService;
import com.company.docai.model.document.Document;
import com.company.docai.model.favorite.FavoriteItem;
import com.company.docai.model.folder.Folder;
import com.company.docai.search.DocumentSearch;
import com.company.docai.search.DocumentSearchHit;
import com.company.docai.search.DocumentSearchHit.Part;
import com.company.docai.search.SearchUnavailableException;
import com.company.docai.view.document.DocumentRenderers;
import com.company.docai.view.folder.FolderFilter;
import com.company.docai.view.main.MainView;
import com.company.docai.view.search.RecentQueries.RecentQuery;
import com.company.docai.view.status.StatusBadges;
import com.google.common.base.Strings;
import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.KeyModifier;
import com.vaadin.flow.component.ScrollIntoViewOption;
import com.vaadin.flow.component.Shortcuts;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.dom.DomListenerRegistration;
import com.vaadin.flow.router.AfterNavigationEvent;
import io.jmix.core.AccessManager;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.OpenedDialogWindows;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.accesscontext.UiShowViewContext;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.icon.JmixFontIcon;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The search palette: a dialog at the top of the window that the search field of the header opens. Before the person
 * types it lists the recent searches, the favorites and questions for the assistant; from two characters on, the
 * documents found by the beginnings of their words, the folders and the tags with the text in their name, then the
 * rows that open the search page and ask the assistant. The keys never take the focus off the line: the arrows go
 * through the rows, Tab through the chips, Enter opens the selected row or searches, Shift+Delete removes the selected
 * recent search. The palette goes nowhere itself: the main view gives it what each choice opens. A document opens its
 * card over the palette, which stays as it is for the next choice; for any other choice the palette closes before it
 * hands the choice over.
 */
@ViewController(id = "SearchPaletteView")
@ViewDescriptor(path = "search-palette-view.xml")
@DialogMode(width = "48em", top = "12vh", draggable = false, closeOnEsc = true, closeOnOutsideClick = true)
public class SearchPaletteView extends StandardView {

    private static final int MIN_QUERY_LENGTH = 2;
    private static final int FAVORITE_ROWS = 5;

    private static final String ROW_ID = "search-palette-row-";
    private static final String HEADING_ID = "search-palette-heading-";

    private static final String REMOVE_SHORTCUT = "Shift+Delete";

    private static final String ASSISTANT_CHAT_VIEW = "aitls_AiChatView";

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
    private UiComponents uiComponents;
    @Autowired
    private Icons icons;
    @Autowired
    private AccessManager accessManager;
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
    private StatusBadges statusBadges;
    @Autowired
    private MetadataTools metadataTools;
    @Autowired
    private CurrentAuthentication currentAuthentication;
    @Autowired
    private OpenedDialogWindows openedDialogWindows;

    @Nullable
    private Component origin;
    private String query = "";
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
    // the folders of the menu tree, read at the first text of an opening that looks for folders
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
    // the rows in the order of the arrows; none is selected at -1
    private final List<Row> rows = new ArrayList<>();
    // the places of the rows of the recent searches among the rows, in the order of the searches
    private final List<Integer> recentRows = new ArrayList<>();
    private int selected = -1;
    private int groups;

    // one choice per palette: a second click or Enter that was already on its way when the palette closed does nothing
    private boolean chosen;

    /**
     * Sets the field the line flies out of and back into: the search field of the header. Without one the palette
     * opens and closes in place.
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
     * Receives the text and the mode to open the search page with.
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
     * Receives a document to open in its card, a view dialog over the palette, which stays open under it. The
     * document may have been deleted or hidden from the user since it was shown; then no card opens.
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

    /**
     * Returns the plural form of the number for the language: {@code one}, {@code few} or {@code many}. Russian picks
     * it by the last digits of the number; the other languages take {@code one} for 1 only.
     */
    public static String pluralForm(long count, Locale locale) {
        if (!"ru".equals(locale.getLanguage())) {
            return count == 1 ? "one" : "many";
        }

        long lastDigit = count % 10;
        long lastTwoDigits = count % 100;

        if (lastDigit == 1 && lastTwoDigits != 11) {
            return "one";
        }

        if (lastDigit >= 2 && lastDigit <= 4 && (lastTwoDigits < 12 || lastTwoDigits > 14)) {
            return "few";
        }

        return "many";
    }

    @Subscribe
    public void onInit(final InitEvent event) {
        chipButtons.put(Chip.ALL, allChip);
        chipButtons.put(Chip.DOCUMENTS, documentsChip);
        chipButtons.put(Chip.FOLDERS, foldersChip);
        chipButtons.put(Chip.TAGS, tagsChip);
        searchModeButton.setVisible(aiSettings.isAvailable());

        // the questions go to the chat of the assistant, so they need the AI and that chat
        UiShowViewContext assistantChat = new UiShowViewContext(ASSISTANT_CHAT_VIEW);
        accessManager.applyRegisteredConstraints(assistantChat);
        assistantAvailable = aiSettings.isAvailable() && assistantChat.isPermitted();

        // the line keeps the focus, and the palette handles the arrows, Tab and Enter itself
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

    // the window sends it once the dialog is open, and the browser gets the call with the dialog itself
    @Subscribe
    public void onReady(final ReadyEvent event) {
        if (origin != null) {
            motion.open(origin, queryField, panel);
        }
    }

    // the palette is always a dialog, and a closed dialog stays on the page until the browser has played the closing:
    // the navigation a choice starts would announce the closed palette as opened and ready again, while a dialog is
    // announced once, when it opens
    @Override
    public void afterNavigation(AfterNavigationEvent event) {
    }

    @Subscribe("queryField")
    public void onQueryFieldComponentValueChange(
            final AbstractField.ComponentValueChangeEvent<TypedTextField<String>, String> event) {
        String text = Strings.nullToEmpty(event.getValue()).strip();

        // the spaces around the text change nothing in the list
        if (!text.equals(shownText)) {
            showList(text);
        }
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
            // from none to the first row and on to the last, then none again; up goes back
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

    // a click carries no text, so the list keeps the text it shows
    private void chooseChip(Chip pressed) {
        chooseChip(pressed, Strings.nullToEmpty(shownText));
    }

    // a click on a chip gives the focus back to the line
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

    // the card opens over the palette, which keeps its text, chip and row; a document gone since the list was made
    // gives a notice instead
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

    // the document that is gone may be one of the favorites the palette read at its opening
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

    // a selected search gives the selection to the recent search that takes its place, else to the one before it
    private void removeRecent(RecentQuery query) {
        int place = recentRows.indexOf(selected);
        boolean selectedRemoved = place >= 0 && recent.get(place).equals(query);

        recentQueries.remove(query.text(), query.ai());
        recent = recentQueries.load();
        showList(Strings.nullToEmpty(shownText));

        if (selectedRemoved && !recentRows.isEmpty()) {
            select(recentRows.get(Math.min(place, recentRows.size() - 1)));
        }

        // the list shows the removal, and a screen reader hears it
        showStatus(messageBundle.formatMessage("recentRemoved", query.text()), false);
    }

    // every new list starts without a selected row and with nothing to say
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

    // only the groups the chip shows are asked for; the rows of the page and of the assistant follow them
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

        // the page finds the forms of the words too, and the assistant may know the answer
        if (groups == 0) {
            showStatus(messageBundle.getMessage("nothingFound"), true);
        }

        Component searchIcon = aiMode ? aiIcon(JmixFontIcon.MAGIC) : icons.get(JmixFontIcon.SEARCH);

        resultsBox.add(row(searchIcon, name(messageBundle.formatMessage("allResultsRow.text", text)), null,
                () -> searchHandler.accept(text, aiMode)));

        if (assistantAvailable) {
            resultsBox.add(assistantRow(messageBundle.formatMessage("askAiRow.text", text), text));
        }
    }

    // an index that does not answer is said in its group: the folders and the tags come from the database
    private void showDocuments(String text, int limit) {
        String title = messageBundle.getMessage("documentsGroup.title");
        List<DocumentSearchHit> hits;

        try {
            hits = documentSearch.first(text, limit);
        } catch (SearchUnavailableException e) {
            String unavailable = messageBundle.getMessage("searchUnavailable");

            Span notice = uiComponents.create(Span.class);
            notice.addClassName("search-palette-message");
            // a screen reader hears it once, from the status line
            notice.getElement().setAttribute("aria-hidden", "true");
            notice.setText(unavailable);
            addGroup(title, List.of(notice));
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
            menuFolders = new MenuFolders(inTreeOrder(tree.folders(), index), tree.greyed(), index);
        }

        return menuFolders;
    }

    // as the tree of the menu shows them, each folder right after its parent: the tree loads them by name alone
    private static List<Folder> inTreeOrder(List<Folder> folders, FolderIndex index) {
        Map<UUID, Integer> positions = new HashMap<>();

        for (int i = 0; i < folders.size(); i++) {
            positions.put(folders.get(i).getId(), i);
        }

        Comparator<Folder> byPath = Comparator.comparing(
                (Folder folder) -> index.pathOf(folder)
                        .stream()
                        .mapToInt(step -> positions.get(step.getId()))
                        .toArray(),
                Arrays::compare);

        return folders.stream()
                .sorted(byPath)
                .toList();
    }

    private Div documentRow(DocumentSearchHit hit) {
        Document document = hit.document();

        Div row = cardRow(icons.get(DocumentRenderers.fileIcon(document)), documentName(hit),
                String.join(" / ", hit.folderPath()), document);

        row.add(statusBadges.create(document.getStatus()));

        return row;
    }

    // the name is marked only where the snippet is the whole name: a long name may come back cut
    private Span documentName(DocumentSearchHit hit) {
        String name = hit.document().getName();
        List<Part> parts = hit.snippet() == null ? List.of() : DocumentSearchHit.partsOf(hit.snippet());

        String shown = parts.stream()
                .map(Part::text)
                .collect(Collectors.joining());

        if (!shown.equals(name)) {
            return name(name);
        }

        Span marked = uiComponents.create(Span.class);
        marked.addClassName("search-palette-name");

        for (Part part : parts) {
            if (part.matched()) {
                Span match = uiComponents.create(Span.class);
                match.addClassName("document-search-match");
                match.setText(part.text());
                marked.add(match);
            } else {
                marked.add(new Text(part.text()));
            }
        }

        return marked;
    }

    // the place of a folder is the way to its parent; a folder the user only passes through is greyed, as in the tree
    private Div folderRow(Folder folder, MenuFolders folders) {
        List<Folder> path = folders.index().pathOf(folder);

        String place = path.subList(0, Math.max(0, path.size() - 1))
                .stream()
                .map(metadataTools::getInstanceName)
                .collect(Collectors.joining(" / "));

        Span name = name(metadataTools.getInstanceName(folder));
        name.setClassName("folder-greyed", folders.greyed().contains(folder.getId()));

        return row(icons.get(JmixFontIcon.FOLDER), name, place, () -> folderOpenHandler.accept(folder));
    }

    private Div tagRow(TagCount tag) {
        String key = "tagDocuments." + pluralForm(tag.documents(), currentAuthentication.getLocale());

        return row(icons.get(JmixFontIcon.TAG), name(tag.name()), messageBundle.formatMessage(key, tag.documents()),
                () -> tagOpenHandler.accept(tag.name()));
    }

    // a search by meaning keeps its wand while the AI is on; without it the search goes by words
    private Div recentRow(RecentQuery query) {
        Component icon = query.ai() && aiSettings.isAvailable()
                ? aiIcon(JmixFontIcon.MAGIC)
                : icons.get(JmixFontIcon.TIME_BACKWARD);

        Div row = row(icon, name(query.text()), null, () -> searchHandler.accept(query.text(), query.ai()));
        recentRows.add(rows.size() - 1);
        row.add(removeButton(query));

        // the name of the cross would join the name of the row; the keys never reach the cross, so the row names the
        // key that removes it
        row.getElement().setAttribute("aria-label", query.text());
        row.getElement().setAttribute("aria-keyshortcuts", REMOVE_SHORTCUT);

        return row;
    }

    // the cross shows on the row the mouse is over or the arrows chose; a click on it is its own, as the row under it
    // would run the search
    private JmixButton removeButton(RecentQuery query) {
        JmixButton button = uiComponents.create(JmixButton.class);
        button.addClassName("search-palette-remove");
        button.setIcon(icons.get(JmixFontIcon.CLOSE));
        button.addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.SMALL);
        button.setTabIndex(-1);
        button.setAriaLabel(messageBundle.getMessage("removeRecentButton.ariaLabel"));
        button.setTooltipText(messageBundle.getMessage("removeRecentButton.tooltip"));

        // the class literal of a generic event is raw, so the listener gets its type from a cast
        @SuppressWarnings("unchecked")
        Class<ClickEvent<JmixButton>> click = (Class<ClickEvent<JmixButton>>) (Class<?>) ClickEvent.class;

        // the second click of a double click lands on the cross of the search that moved up under the pointer, and the
        // first click took the focus from the line
        ComponentUtil.addListener(button, click, event -> {
            if (event.getClickCount() <= 1) {
                removeRecent(query);
                line.focusAtEnd();
            }
        }, DomListenerRegistration::stopPropagation);

        return button;
    }

    private Div favoriteRow(FavoriteItem item) {
        if (item.getFolder() != null) {
            return row(icons.get(JmixFontIcon.FOLDER), name(item.getName()), item.getLocation(),
                    () -> folderOpenHandler.accept(item.getFolder()));
        }

        return cardRow(icons.get(JmixFontIcon.FILE_TEXT), name(item.getName()), item.getLocation(),
                item.getDocument());
    }

    private Div assistantRow(String shown, String question) {
        return row(aiIcon(JmixFontIcon.COMMENTS), name(shown), null, () -> assistantHandler.accept(question));
    }

    // a group without rows is not shown
    private void addGroup(String title, List<? extends Component> content) {
        if (content.isEmpty()) {
            return;
        }

        String headingId = HEADING_ID + groups++;

        Span heading = uiComponents.create(Span.class);
        heading.setId(headingId);
        heading.addClassName("search-palette-heading");
        heading.setText(title);

        Div group = uiComponents.create(Div.class);
        group.addClassName("search-palette-group");
        group.getElement().setAttribute("role", "group");
        group.getElement().setAttribute("aria-labelledby", headingId);
        group.add(heading);
        content.forEach(group::add);
        resultsBox.add(group);
    }

    // a row that goes elsewhere: the palette closes before the destination opens
    private Div row(Component icon, Component name, @Nullable String meta, Runnable action) {
        return choiceRow(icon, name, meta, () -> choose(action));
    }

    private Div cardRow(Component icon, Component name, @Nullable String meta, Document document) {
        return choiceRow(icon, name, meta, () -> openCard(document));
    }

    // the arrows go through the rows in the order they are made, which is their order on the screen
    private Div choiceRow(Component icon, Component name, @Nullable String meta, Runnable choice) {
        Div row = uiComponents.create(Div.class);
        row.setId(ROW_ID + rows.size());
        row.addClassName("search-palette-row");
        row.getElement().setAttribute("role", "option");
        row.getElement().setAttribute("aria-selected", "false");
        row.add(icon, name);

        if (meta != null && !meta.isEmpty()) {
            Span place = uiComponents.create(Span.class);
            place.addClassName("search-palette-meta");
            place.setText(meta);
            row.add(place);
        }

        // the second click of a double click lands on what the first one left under the pointer
        row.addClickListener(click -> {
            if (click.getClickCount() <= 1) {
                choice.run();
            }
        });

        rows.add(new Row(row, choice));

        return row;
    }

    private Span name(String text) {
        Span name = uiComponents.create(Span.class);
        name.addClassName("search-palette-name");
        name.setText(text);

        return name;
    }

    // the icons of the assistant and of the search by meaning wear the accent of AI, as in the menu
    private Component aiIcon(JmixFontIcon icon) {
        Component component = icons.get(icon);
        component.addClassNames("aura-accent-purple", "search-palette-ai-icon");

        return component;
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

    // the line is never hidden, only out of sight: a screen reader hears a status only from a region already on the
    // page. A text the list shows itself is said, not shown twice.
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

        // Tab goes round the chips, Shift+Tab the other way
        private Chip next(boolean back) {
            Chip[] chips = values();

            return chips[Math.floorMod(ordinal() + (back ? -1 : 1), chips.length)];
        }
    }

    private record Row(Div element, Runnable choice) {
    }

    // the folders of the menu tree in its order, the ids of those the user only passes through, and the way up to each
    private record MenuFolders(List<Folder> inTreeOrder, Set<UUID> greyed, FolderIndex index) {
    }
}
