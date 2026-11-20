package com.company.docai.view.main;

import com.company.docai.ai.AiSettings;
import com.company.docai.app.ui.component.searchpalette.SearchPaletteOpener;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.user.User;
import com.company.docai.view.assistant.AssistantNavigation;
import com.company.docai.view.dashboard.DashboardFragment;
import com.company.docai.view.document.DocumentCards;
import com.company.docai.view.favorite.FavoritesFragment;
import com.company.docai.view.profile.ProfileReadView;
import com.company.docai.view.repository.FolderTreeFragment;
import com.company.docai.view.repository.RepositoryView;
import com.company.docai.view.search.DocumentSearchView;
import com.company.docai.view.search.SearchNavigation;
import com.company.docai.view.search.SearchPaletteView;
import com.company.docai.view.tour.Tour;
import com.company.docai.view.user.UserRenderers;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
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

/**
 * The main view: the menu with the assistant, the folders and the views, the header with the search field that opens
 * the search palette, and the dashboard in place of a view at the root.
 */
@Route("")
@ViewController(id = "MainView")
@ViewDescriptor(path = "main-view.xml")
public class MainView extends StandardMainView {

    /**
     * The user setting with the mode of the search the person chose last.
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

    @ViewComponent
    private DashboardFragment dashboard;

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
    private AssistantNavigation assistant;

    @Autowired
    private Messages messages;
    @Autowired
    private UiComponents uiComponents;
    @Autowired
    private CurrentUserSubstitution currentUserSubstitution;
    @Autowired
    private UserRenderers userRenderers;
    @Autowired
    private Tour tour;

    private boolean aiSearch;
    @Nullable
    private DialogWindow<SearchPaletteView> searchPalette;

    @Subscribe
    public void onInit(final InitEvent event) {
        initAssistantMenu();
        initFolderTree();
        initSearch();
        initDashboard();
        tour.attach(this);
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        super.afterNavigation(event);

        showSearchOf(event);
        restoreFolderTree(event);
        refreshDashboard();
        tour.afterNavigation(event);
    }

    @Override
    protected String getTitleFromOpenedView() {
        return isDashboardShown()
                ? dashboard.getTitle()
                : super.getTitleFromOpenedView();
    }

    @Subscribe(id = "searchModeButton", subject = "clickListener")
    public void onSearchModeButtonClick(final ClickEvent<JmixButton> event) {
        chooseSearchMode(!aiSearch);
    }

    @Subscribe("searchPaletteOpener")
    public void onSearchPaletteOpenerOpen(final SearchPaletteOpener.OpenEvent event) {
        openSearchPalette(event.getText(), event.getOpening());
    }

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

    @Subscribe("userMenu.tourMenuItem.tourAction")
    public void onUserMenuTourMenuItemTourAction(final ActionPerformedEvent event) {
        tour.restart();
    }

    @Install(to = "userMenu", subject = "buttonRenderer")
    private Component userMenuButtonRenderer(final UserDetails userDetails) {
        if (!(userDetails instanceof User user)) {
            return null;
        }

        String userName = userRenderers.shortName(user, user.getUsername());

        Div content = uiComponents.create(Div.class);
        content.setClassName("user-menu-button-content");

        Avatar avatar = createAvatar(user, userName);

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

        String name = userRenderers.shortName(user, user.getUsername());

        Avatar avatar = createAvatar(user, name);
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
     * The folder tree of the main menu, into which the repository plugs its own handlers while it is shown; on any
     * other view the tree opens the repository on the chosen folder.
     */
    public FolderTreeFragment getFolderTree() {
        return folderTree;
    }

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
        folderTree.addUnderFolders(navigation);

        openFoldersInRepository();
        folderTree.addSelectionListener(folder -> closeOverlayDrawer());
    }

    private void restoreFolderTree(AfterNavigationEvent event) {
        if (event.getActiveChain().stream().noneMatch(RepositoryView.class::isInstance)) {
            openFoldersInRepository();
        }
    }

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
        searchPaletteOpener.setField(searchField);
        searchModeButton.setVisible(aiSettings.isAvailable());

        aiSearch = aiSettings.isAvailable() && userSettingsService.load(SEARCH_MODE_SETTING)
                .map(DocumentSearchView.AI_MODE::equals)
                .orElse(false);

        showSearchMode();
    }

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

    // one palette at a time and none over a strictly modal dialog: the request passes over an inert page, so they are
    // checked here. The main view is the origin of the search and of the tag, so an open search page shows them.
    private void openSearchPalette(@Nullable String text, int opening) {
        if (searchPalette != null || UI.getCurrent().hasModalComponent()) {
            return;
        }

        DialogWindow<SearchPaletteView> window = dialogWindows.view(this, SearchPaletteView.class)
                .build();

        SearchPaletteView palette = window.getView();
        palette.setOrigin(searchField);
        palette.setQuery(text);
        palette.setOpening(opening);
        palette.setAiMode(aiSearch);
        palette.setModeChangeHandler(this::chooseSearchMode);
        palette.setSearchHandler((query, ai) -> searchNavigation.openSearch(this, query, ai));
        palette.setAssistantHandler(assistant::openAssistant);
        palette.setDocumentOpenHandler(this::openPaletteDocument);
        palette.setFolderOpenHandler(folder -> folderTree.loadAndSelect(folder.getId()));
        palette.setTagOpenHandler(tag -> searchNavigation.openTag(this, tag));

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

    private void initDashboard() {
        dashboard.setDocumentOpenHandler(document -> documentCards.openIfAvailable(this, document,
                closeEvent -> afterDashboardCard()));

        dashboard.setFillWithAiHandler(document -> documentCards.fillWithAiIfAvailable(this, document,
                closeEvent -> afterDashboardCard()));

        dashboard.setAssistantHandler(assistant::openAssistant);
    }

    private void refreshDashboard() {
        if (isDashboardShown()) {
            dashboard.refresh();
        }
    }

    private void afterDashboardCard() {
        refreshFavorites();
        refreshDashboard();
    }

    private boolean isDashboardShown() {
        return getContent().getContent() == getInitialLayout();
    }

    private Avatar createAvatar(User user, String name) {
        Avatar avatar = uiComponents.create(Avatar.class);
        userRenderers.fillAvatar(avatar, name, user, user.getUsername());
        avatar.getElement().setAttribute("tabindex", "-1");
        avatar.setClassName("user-menu-avatar");

        return avatar;
    }

    private boolean isSubstituted(User user) {
        UserDetails authenticatedUser = currentUserSubstitution.getAuthenticatedUser();
        return user != null && !authenticatedUser.getUsername().equals(user.getUsername());
    }
}
