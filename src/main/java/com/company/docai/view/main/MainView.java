package com.company.docai.view.main;

import com.company.docai.ai.AiSettings;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.user.User;
import com.company.docai.view.document.DocumentDetailView;
import com.company.docai.view.favorite.FavoritesFragment;
import com.company.docai.view.profile.ProfileReadView;
import com.company.docai.view.repository.FolderTreeFragment;
import com.company.docai.view.repository.RepositoryView;
import com.company.docai.view.search.DocumentSearchView;
import com.google.common.base.Strings;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.DomEvent;
import com.vaadin.flow.component.EventData;
import com.vaadin.flow.component.Key;
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
import io.jmix.flowui.component.main.JmixListMenu;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.kit.action.ActionPerformedEvent;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.component.main.ListMenu;
import io.jmix.flowui.settings.UserSettingsService;
import io.jmix.flowui.view.Install;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Optional;

@Route("")
@ViewController(id = "MainView")
@ViewDescriptor(path = "main-view.xml")
public class MainView extends StandardMainView {

    /**
     * The user setting with the mode of the search the person chose last: {@link DocumentSearchView#AI_MODE} or
     * the search by words.
     */
    public static final String SEARCH_MODE_SETTING = "docai.searchMode";

    /**
     * The user setting with the width of the main menu in rem, which the user drags with its edge.
     */
    public static final String DRAWER_WIDTH_SETTING = "docai.drawerWidth";

    private static final String WORDS_MODE = "words";
    private static final String ASSISTANT_CHAT_ITEM = "assistantChat";
    private static final String DRAWER_WIDTH = "--main-view-drawer-width";
    private static final double MIN_DRAWER_WIDTH = 16;
    private static final double MAX_DRAWER_WIDTH = 32;

    @ViewComponent
    private TypedTextField<String> searchField;
    @ViewComponent
    private JmixButton searchModeButton;
    @ViewComponent
    private JmixListMenu menu;
    @ViewComponent
    private JmixListMenu assistantMenu;
    @ViewComponent
    private FolderTreeFragment folderTree;
    @ViewComponent
    private Nav navigation;
    @ViewComponent
    private Div drawerHandle;

    @Autowired
    private Messages messages;
    @Autowired
    private UiComponents uiComponents;
    @Autowired
    private CurrentUserSubstitution currentUserSubstitution;
    @Autowired
    private DialogWindows dialogWindows;
    @Autowired
    private ViewNavigators viewNavigators;
    @Autowired
    private AiSettings aiSettings;
    @Autowired
    private UserSettingsService userSettingsService;

    private boolean aiSearch;

    @Subscribe
    public void onInit(final InitEvent event) {
        searchField.addKeyPressListener(Key.ENTER, keyPressEvent -> openSearch());

        // the assistant stands above the folders, apart from the views under them; the menu file cannot depend on
        // the key, and an item hidden after the menu is drawn stays on the page
        ListMenu.MenuItem assistantChat = menu.getMenuItem(ASSISTANT_CHAT_ITEM);

        if (assistantChat != null) {
            menu.removeMenuItem(assistantChat);

            if (aiSettings.isAvailable()) {
                assistantMenu.addMenuItem(assistantChat);
            }
        }

        // the views scroll with the folders, under the brand, the assistant and the folder field that stay in sight
        folderTree.addUnderFolders(navigation);

        openFoldersInRepository();
        // over a narrow window the menu covers the view, so a chosen folder closes it, as a chosen view does
        folderTree.addSelectionListener(folder -> closeOverlayDrawer());

        searchModeButton.setVisible(aiSettings.isAvailable());

        aiSearch = aiSettings.isAvailable() && userSettingsService.load(SEARCH_MODE_SETTING)
                .map(DocumentSearchView.AI_MODE::equals)
                .orElse(false);

        showSearchMode();
        addAttachListener(attachEvent -> guardFileDrops());

        // the edge of the menu takes a drag for long names, and the menu opens as wide as the user left it
        userSettingsService.load(DRAWER_WIDTH_SETTING)
                .flatMap(MainView::parseDrawerWidth)
                .ifPresent(this::showDrawerWidth);

        drawerHandle.getElement()
                .setAttribute("role", "separator")
                .setAttribute("aria-orientation", "vertical")
                .setAttribute("tabindex", "0")
                .setAttribute("aria-label", messages.getMessage(getClass(), "drawerHandle.ariaLabel"));

        ComponentUtil.addListener(drawerHandle, DrawerResizeEvent.class, resize -> resizeDrawer(resize.getWidth()));
        addAttachListener(attachEvent -> handleDrawerDrags());
    }

    // A file dragged from the computer and dropped where nothing takes it would open in the tab and leave the
    // application, and a drop target for documents would light up for it and do nothing. Files from the computer go
    // only to the uploads that take them now: elsewhere, the chat's upload with attachments off included, the page
    // stops them before anything sees them, and the cursor shows they can't be dropped there.
    private void guardFileDrops() {
        getElement().executeJs("""
                if (window.docaiFileDropGuard) {
                    return;
                }
                window.docaiFileDropGuard = true;
                const uploads = ['vaadin-upload', 'vaadin-upload-drop-zone'];
                const takesFiles = node => uploads.includes(node.localName) && !node.disabled && !node.nodrop;
                for (const type of ['dragenter', 'dragover', 'drop']) {
                    window.addEventListener(type, event => {
                        if (event.dataTransfer && Array.from(event.dataTransfer.types).includes('Files')
                                && !event.composedPath().some(takesFiles)) {
                            event.stopImmediatePropagation();
                            event.preventDefault();
                            event.dataTransfer.dropEffect = 'none';
                        }
                    }, true);
                }""");
    }

    // the open results stay until Enter runs the query in the chosen mode
    @Subscribe(id = "searchModeButton", subject = "clickListener")
    public void onSearchModeButtonClick(final ClickEvent<JmixButton> event) {
        chooseSearchMode(!aiSearch);
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        super.afterNavigation(event);

        // the field shows the words and the mode of the open search and is empty elsewhere
        boolean searchOpen = event.getActiveChain().stream().anyMatch(DocumentSearchView.class::isInstance);
        QueryParameters parameters = event.getLocation().getQueryParameters();
        searchField.setValue(searchOpen ? DocumentSearchView.queryOf(parameters) : "");

        if (searchOpen && aiSettings.isAvailable() && DocumentSearchView.isAiMode(parameters) != aiSearch) {
            chooseSearchMode(!aiSearch);
        }

        // the repository plugs its own handlers into the tree while it is shown
        if (event.getActiveChain().stream().noneMatch(RepositoryView.class::isInstance)) {
            openFoldersInRepository();
        }
    }

    /**
     * The folder tree of the main menu. The repository plugs its own handlers into it while it is shown; on any
     * other view the tree opens the repository on the chosen folder.
     */
    public FolderTreeFragment getFolderTree() {
        return folderTree;
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

    // the card may mark the document with its star or take it away
    private void openCard(Document document) {
        dialogWindows.detail(this, Document.class)
                .withViewClass(DocumentDetailView.class)
                .editEntity(document)
                .withAfterCloseListener(closeEvent -> {
                    folderTree.getFavorites().reload();
                    folderTree.refreshFavorites();
                })
                .open();
    }

    private void closeOverlayDrawer() {
        if (getContent().isOverlay()) {
            getContent().setDrawerOpened(false);
        }
    }

    // A drag of the edge widens the menu in the browser at once, in steps of a quarter of a rem, and reports the width
    // at its end; the arrows of the keyboard move the edge by a rem
    private void handleDrawerDrags() {
        getElement().executeJs("""
                const layout = this;
                const [handle, min, max] = [$0, $1, $2];

                if (handle.docaiDrawerResize) {
                    return;
                }

                handle.docaiDrawerResize = true;
                const show = width => {
                    const shown = Math.min(max, Math.max(min, Math.round(width * 4) / 4));
                    layout.style.setProperty('--main-view-drawer-width', shown + 'rem');
                    return shown;
                };
                const report = width => handle.dispatchEvent(new CustomEvent('drawer-resize', {detail: width}));

                handle.addEventListener('pointerdown', down => {
                    if (down.button !== 0) {
                        return;
                    }

                    down.preventDefault();
                    handle.setPointerCapture(down.pointerId);
                    layout.toggleAttribute('drawer-resizing', true);
                    const left = layout.getBoundingClientRect().left;
                    const rem = parseFloat(getComputedStyle(document.documentElement).fontSize);
                    let width;
                    const move = event => width = show((event.clientX - left) / rem);
                    const up = () => {
                        handle.removeEventListener('pointermove', move);
                        handle.removeEventListener('pointerup', up);
                        handle.removeEventListener('pointercancel', up);
                        layout.toggleAttribute('drawer-resizing', false);

                        if (width !== undefined) {
                            report(width);
                        }
                    };

                    handle.addEventListener('pointermove', move);
                    handle.addEventListener('pointerup', up);
                    handle.addEventListener('pointercancel', up);
                });

                handle.addEventListener('keydown', event => {
                    const step = {ArrowLeft: -1, ArrowRight: 1}[event.key];

                    if (step) {
                        event.preventDefault();
                        const current = parseFloat(getComputedStyle(layout).getPropertyValue(
                                '--vaadin-app-layout-drawer-width'));
                        report(show(current + step));
                    }
                });
                """, drawerHandle.getElement(), MIN_DRAWER_WIDTH, MAX_DRAWER_WIDTH);
    }

    // the width comes from the browser, so it is kept within the limits and in steps of a quarter of a rem here too
    private void resizeDrawer(double width) {
        if (!Double.isFinite(width)) {
            return;
        }

        double shown = Math.round(Math.clamp(width, MIN_DRAWER_WIDTH, MAX_DRAWER_WIDTH) * 4) / 4.0;
        showDrawerWidth(shown);
        userSettingsService.save(DRAWER_WIDTH_SETTING, String.valueOf(shown));
    }

    private void showDrawerWidth(double width) {
        getContent().getStyle().set(DRAWER_WIDTH, width + "rem");
    }

    private static Optional<Double> parseDrawerWidth(String setting) {
        try {
            return Optional.of(Double.parseDouble(setting))
                    .filter(Double::isFinite)
                    .map(width -> Math.clamp(width, MIN_DRAWER_WIDTH, MAX_DRAWER_WIDTH));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private void openSearch() {
        String query = Strings.nullToEmpty(searchField.getValue()).strip();

        if (query.isEmpty()) {
            return;
        }

        QueryParameters parameters = QueryParameters.of(DocumentSearchView.QUERY_PARAMETER, query);

        if (aiSearch) {
            parameters = parameters.merging(DocumentSearchView.MODE_PARAMETER, DocumentSearchView.AI_MODE);
        }

        viewNavigators.view(this, DocumentSearchView.class)
                .withQueryParameters(parameters)
                .navigate();
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

    /**
     * The edge of the main menu dragged to a new width, as the browser reports it at the end of the drag.
     */
    @DomEvent("drawer-resize")
    public static class DrawerResizeEvent extends ComponentEvent<Div> {

        private final double width;

        /**
         * Creates the event of the edge of the menu with the width it was dragged to, in rem.
         */
        public DrawerResizeEvent(Div source, boolean fromClient, @EventData("event.detail") double width) {
            super(source, fromClient);
            this.width = width;
        }

        /**
         * Returns the width the edge was dragged to, in rem.
         */
        public double getWidth() {
            return width;
        }
    }
}
