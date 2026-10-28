package com.company.dms.view.main;

import com.company.dms.ai.AiSettings;
import com.company.dms.model.user.User;
import com.company.dms.view.profile.ProfileReadView;
import com.company.dms.view.search.DocumentSearchView;
import com.google.common.base.Strings;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.avatar.Avatar;
import com.vaadin.flow.component.avatar.AvatarVariant;
import com.vaadin.flow.component.html.Div;
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

@Route("")
@ViewController(id = "MainView")
@ViewDescriptor(path = "main-view.xml")
public class MainView extends StandardMainView {

    /**
     * The user setting with the mode of the search the person chose last: {@link DocumentSearchView#AI_MODE} or
     * the search by words.
     */
    public static final String SEARCH_MODE_SETTING = "dms.searchMode";

    private static final String WORDS_MODE = "words";

    @ViewComponent
    private TypedTextField<String> searchField;
    @ViewComponent
    private JmixButton searchModeButton;
    @ViewComponent
    private JmixListMenu menu;

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
    private boolean searchOpen;

    @Subscribe
    public void onInit(final InitEvent event) {
        searchField.addKeyPressListener(Key.ENTER, keyPressEvent -> openSearch());

        // the menu file cannot depend on the key, and an item hidden after the menu is drawn stays on the page
        ListMenu.MenuItem assistantChat = menu.getMenuItem("assistantChat");

        if (assistantChat != null && !aiSettings.isAvailable()
                && menu.getMenuItem("application") instanceof ListMenu.MenuBarItem application) {
            application.removeChildItem(assistantChat);
        }

        searchModeButton.setVisible(aiSettings.isAvailable());

        aiSearch = aiSettings.isAvailable() && userSettingsService.load(SEARCH_MODE_SETTING)
                .map(DocumentSearchView.AI_MODE::equals)
                .orElse(false);

        showSearchMode();
    }

    @Subscribe(id = "searchModeButton", subject = "clickListener")
    public void onSearchModeButtonClick(final ClickEvent<JmixButton> event) {
        chooseSearchMode(!aiSearch);

        // the open search runs again at once in the other mode
        if (searchOpen) {
            openSearch();
        }
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        super.afterNavigation(event);

        // the field shows the words and the mode of the open search and is empty elsewhere
        searchOpen = event.getActiveChain().stream().anyMatch(DocumentSearchView.class::isInstance);
        QueryParameters parameters = event.getLocation().getQueryParameters();
        searchField.setValue(searchOpen ? DocumentSearchView.queryOf(parameters) : "");

        if (searchOpen && aiSettings.isAvailable() && DocumentSearchView.isAiMode(parameters) != aiSearch) {
            chooseSearchMode(!aiSearch);
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
}
