package com.company.docai.view.main;

import com.company.docai.ai.AiSettings;
import com.company.docai.app.ui.component.searchpalette.SearchPaletteOpener;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.user.User;
import com.company.docai.view.document.DocumentCards;
import com.company.docai.view.favorite.FavoritesFragment;
import com.company.docai.view.profile.ProfileReadView;
import com.company.docai.view.repository.FolderTreeFragment;
import com.company.docai.view.repository.RepositoryView;
import com.company.docai.view.search.DocumentSearchView;
import com.company.docai.view.search.SearchNavigation;
import com.company.docai.view.search.SearchPaletteView;
import com.google.common.base.Strings;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.avatar.Avatar;
import com.vaadin.flow.component.avatar.AvatarVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Nav;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import io.jmix.core.Messages;
import io.jmix.core.usersubstitution.CurrentUserSubstitution;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.app.main.StandardMainView;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.component.main.JmixListMenu;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.kit.action.ActionPerformedEvent;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.component.main.ListMenu;
import io.jmix.flowui.settings.UserSettingsService;
import io.jmix.flowui.view.DialogWindow;
import io.jmix.flowui.view.Install;
import io.jmix.flowui.view.NavigateCloseAction;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;

@Route("")
@ViewController(id = "MainView")
@ViewDescriptor(path = "main-view.xml")
public class MainView extends StandardMainView {

    /**
     * The user setting with the mode of the search the person chose last: {@link DocumentSearchView#AI_MODE} or
     * the search by words.
     */
    public static final String SEARCH_MODE_SETTING = "docai.searchMode";

    private static final String ASSISTANT_CHAT_ITEM = "assistantChat";

    private static final String WORDS_MODE = "words";

    @ViewComponent
    private JmixListMenu menu;
    @ViewComponent
    private JmixListMenu assistantMenu;
    @ViewComponent
    private Nav assistantNavigation;
    @ViewComponent
    private FolderTreeFragment folderTree;
    @ViewComponent
    private Nav navigation;

    @ViewComponent
    private TypedTextField<String> searchField;
    @ViewComponent
    private JmixButton searchModeButton;
    @ViewComponent
    private SearchPaletteOpener searchPaletteOpener;

    @Autowired
    private AiSettings aiSettings;
    @Autowired
    private ViewNavigators viewNavigators;
    @Autowired
    private DocumentCards documentCards;

    @Autowired
    private UserSettingsService userSettingsService;
    @Autowired
    private DialogWindows dialogWindows;
    @Autowired
    private SearchNavigation searchNavigation;

    @Autowired
    private Messages messages;
    @Autowired
    private UiComponents uiComponents;
    @Autowired
    private CurrentUserSubstitution currentUserSubstitution;

    private boolean aiSearch;
    @Nullable
    private DialogWindow<SearchPaletteView> searchPalette;

    @Subscribe
    public void onInit(final InitEvent event) {
        initAssistantMenu();
        initFolderTree();
        initSearch();
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        super.afterNavigation(event);

        showSearchOf(event);
        restoreFolderTree(event);
    }

    // the open results stay until the palette runs the query in the chosen mode
    @Subscribe(id = "searchModeButton", subject = "clickListener")
    public void onSearchModeButtonClick(final ClickEvent<JmixButton> event) {
        chooseSearchMode(!aiSearch);
    }

    @Subscribe("searchPaletteOpener")
    public void onSearchPaletteOpenerOpen(final SearchPaletteOpener.OpenEvent event) {
        openSearchPalette(event.getText());
    }

    // the browser has given the field its text back and dropped the keys typed on the way: only the palette is left
    @Subscribe("searchPaletteOpener")
    public void onSearchPaletteOpenerCancel(final SearchPaletteOpener.CancelEvent event) {
        if (searchPalette != null) {
            searchPalette.close();
        }
    }

    @Subscribe("userMenu.profileMenuItem.profileAction")
    public void onUserMenuProfileMenuItemProfileAction(final ActionPerformedEvent event) {
        dialogWindows.read(this, User.class)
                .withViewClass(ProfileReadView.class)
                .readEntity((User) currentUserSubstitution.getEffectiveUser())
                .open();
    }

    @Install(to = "userMenu", subject = "buttonRenderer")
    private Component userMenuButtonRenderer(final UserDetails userDetails) {
        if (!(userDetails instanceof User user)) {
            return null;
        }

        String userName = generateUserName(user);

        Div content = uiComponents.create(Div.class);
        content.setClassName("user-menu-button-content");

        Avatar avatar = createAvatar(userName);

        Span name = uiComponents.create(Span.class);
        name.setText(userName);
        name.setClassName("user-menu-text");

        content.add(avatar, name);

        if (isSubstituted(user)) {
            Span subtext = uiComponents.create(Span.class);
            subtext.setText(messages.getMessage("userMenu.substituted"));
            subtext.setClassName("user-menu-subtext");

            content.add(subtext);
        }

        return content;
    }

    @Install(to = "userMenu", subject = "headerRenderer")
    private Component userMenuHeaderRenderer(final UserDetails userDetails) {
        if (!(userDetails instanceof User user)) {
            return null;
        }

        Div content = uiComponents.create(Div.class);
        content.setClassName("user-menu-header-content");

        String name = generateUserName(user);

        Avatar avatar = createAvatar(name);
        avatar.addThemeVariants(AvatarVariant.LARGE);

        Span text = uiComponents.create(Span.class);
        text.setText(name);
        text.setClassName("user-menu-text");

        content.add(avatar, text);

        if (name.equals(user.getUsername())) {
            text.addClassName("user-menu-text-subtext");
        } else {
            Span subtext = uiComponents.create(Span.class);
            subtext.setText(user.getUsername());
            subtext.setClassName("user-menu-subtext");

            content.add(subtext);
        }

        return content;
    }

    /**
     * The folder tree of the main menu. The repository plugs its own handlers into it while it is shown; on any
     * other view the tree opens the repository on the chosen folder.
     */
    public FolderTreeFragment getFolderTree() {
        return folderTree;
    }

    // the assistant stands above the folders, apart from the views under them; the menu file cannot depend on the key,
    // and an item hidden after the menu is drawn stays on the page
    private void initAssistantMenu() {
        ListMenu.MenuItem assistantChat = menu.getMenuItem(ASSISTANT_CHAT_ITEM);

        if (assistantChat != null) {
            menu.removeMenuItem(assistantChat);

            if (aiSettings.isAvailable()) {
                assistantMenu.addMenuItem(assistantChat);
            }
        }

        assistantNavigation.setVisible(!assistantMenu.getMenuItems().isEmpty());
    }

    private void initFolderTree() {
        // the views scroll with the folders, under the brand, the assistant and the folder field that stay in sight
        folderTree.addUnderFolders(navigation);

        openFoldersInRepository();
        // over a narrow window the menu covers the view, so a chosen folder closes it, as a chosen view does
        folderTree.addSelectionListener(folder -> closeOverlayDrawer());
    }

    // the repository plugs its own handlers into the tree while it is shown
    private void restoreFolderTree(AfterNavigationEvent event) {
        if (event.getActiveChain().stream().noneMatch(RepositoryView.class::isInstance)) {
            openFoldersInRepository();
        }
    }

    // outside the repository no folder is the current one, a chosen folder opens there, and a document of the
    // favorites opens over the view, as from the search
    private void openFoldersInRepository() {
        FavoritesFragment favorites = folderTree.getFavorites();
        folderTree.setSelectionHandler(this::openRepository);
        folderTree.setDocumentsDropHandler((documents, target) -> {
        });

        favorites.setFolderOpenHandler(this::openRepository);
        favorites.setDocumentOpenHandler(this::openCard);
        favorites.setChangeHandler(folderTree::refreshFavorites);
        folderTree.clearSelection();
    }

    private void openRepository(Folder folder) {
        viewNavigators.view(this, RepositoryView.class)
                .withQueryParameters(QueryParameters.of(RepositoryView.FOLDER_PARAMETER, folder.getId().toString()))
                .navigate();
    }

    private void openCard(Document document) {
        documentCards.open(this, document, closeEvent -> refreshFavorites());
    }

    // the card may mark the document with its star or take it away
    private void refreshFavorites() {
        folderTree.getFavorites().reload();
        folderTree.refreshFavorites();
    }

    private void closeOverlayDrawer() {
        if (getContent().isOverlay()) {
            getContent().setDrawerOpened(false);
        }
    }

    private void initSearch() {
        // the field searches nothing itself: the browser decides what in it opens the palette
        searchPaletteOpener.setField(searchField);
        searchModeButton.setVisible(aiSettings.isAvailable());

        aiSearch = aiSettings.isAvailable() && userSettingsService.load(SEARCH_MODE_SETTING)
                .map(DocumentSearchView.AI_MODE::equals)
                .orElse(false);

        showSearchMode();
    }

    // the field shows the words and the mode of the open search and is empty elsewhere
    private void showSearchOf(AfterNavigationEvent event) {
        boolean searchOpen = event.getActiveChain().stream().anyMatch(DocumentSearchView.class::isInstance);
        QueryParameters parameters = event.getLocation().getQueryParameters();
        searchField.setValue(searchOpen ? DocumentSearchView.queryOf(parameters) : "");

        // the page of a tag searches neither way, so the wand keeps the mode the person chose
        boolean tagPage = parameters.getSingleParameter(DocumentSearchView.TAG_PARAMETER)
                .filter(tag -> !tag.isBlank())
                .isPresent();

        if (searchOpen && !tagPage && aiSettings.isAvailable() && DocumentSearchView.isAiMode(parameters) != aiSearch) {
            chooseSearchMode(!aiSearch);
        }
    }

    // one palette at a time: a second click or shortcut while it is open does nothing. The text is the one the browser
    // shows, which the field sends the server only when the person leaves it. The main view is the origin of the
    // search and of the tag, so an open search page shows them.
    private void openSearchPalette(@Nullable String text) {
        if (searchPalette != null) {
            return;
        }

        DialogWindow<SearchPaletteView> window = dialogWindows.view(this, SearchPaletteView.class)
                .build();

        SearchPaletteView palette = window.getView();
        palette.setOrigin(searchField);
        palette.setQuery(text);
        palette.setAiMode(aiSearch);
        palette.setModeChangeHandler(this::chooseSearchMode);
        palette.setSearchHandler((query, ai) -> searchNavigation.openSearch(this, query, ai));
        palette.setAssistantHandler(searchNavigation::openAssistant);
        palette.setDocumentOpenHandler(this::openPaletteDocument);
        palette.setFolderOpenHandler(folder -> folderTree.loadAndSelect(folder.getId()));
        palette.setTagOpenHandler(tag -> searchNavigation.openTag(this, tag));

        // a Vaadin dialog has no style of its own: the palette looks as its class says
        window.addClassName("search-palette");

        window.addAfterCloseListener(closeEvent -> {
            searchPalette = null;

            // a navigation the palette did not start, such as Back, closes its view and leaves the dialog open
            if (closeEvent.getCloseAction() instanceof NavigateCloseAction) {
                window.close();
            }
        });

        searchPalette = window;
        window.open();
    }

    // in the repository the list opens the card, so that a save refreshes its table; elsewhere the favorites are reread
    // after the card. The card opens over the palette, which takes the focus back.
    private void openPaletteDocument(Document document) {
        if (UiComponentUtils.getCurrentView() instanceof RepositoryView repository) {
            repository.openCard(document);
            return;
        }

        documentCards.openIfAvailable(this, document, closeEvent -> refreshFavorites());
    }

    private void chooseSearchMode(boolean ai) {
        aiSearch = ai;
        userSettingsService.save(SEARCH_MODE_SETTING, ai ? DocumentSearchView.AI_MODE : WORDS_MODE);
        showSearchMode();
    }

    private void showSearchMode() {
        searchModeButton.setTooltipText(messages.getMessage(getClass(),
                aiSearch ? "searchModeButton.on" : "searchModeButton.off"));

        searchModeButton.getElement().setAttribute("aria-pressed", String.valueOf(aiSearch));
        searchField.setPlaceholder(messages.getMessage(getClass(),
                aiSearch ? "searchField.aiPlaceholder" : "searchField.placeholder"));
    }

    private Avatar createAvatar(String fullName) {
        Avatar avatar = uiComponents.create(Avatar.class);
        avatar.setName(fullName);
        avatar.getElement().setAttribute("tabindex", "-1");
        avatar.setClassName("user-menu-avatar");

        return avatar;
    }

    private String generateUserName(User user) {
        String userName = String.format("%s %s",
                        Strings.nullToEmpty(user.getFirstName()),
                        Strings.nullToEmpty(user.getLastName()))
                .trim();

        return userName.isEmpty() ? user.getUsername() : userName;
    }

    private boolean isSubstituted(User user) {
        UserDetails authenticatedUser = currentUserSubstitution.getAuthenticatedUser();
        return user != null && !authenticatedUser.getUsername().equals(user.getUsername());
    }
}
