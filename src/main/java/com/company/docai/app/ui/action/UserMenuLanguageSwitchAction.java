package com.company.docai.app.ui.action;

import com.company.docai.stand.CopyNotMadeException;
import com.company.docai.stand.StandLogin;
import com.company.docai.stand.StandScope;
import com.company.docai.stand.TemplateNotReadyException;
import com.company.docai.view.login.LoginView;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.RouteConfiguration;
import com.vaadin.flow.server.VaadinSession;
import io.jmix.core.CoreProperties;
import io.jmix.core.MessageTools;
import io.jmix.core.Messages;
import io.jmix.core.security.AccessDeniedException;
import io.jmix.core.security.ClientDetails;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.UiProperties;
import io.jmix.flowui.action.ActionType;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.action.usermenu.UserMenuAction;
import io.jmix.flowui.component.usermenu.UserMenu;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.action.ActionVariant;
import io.jmix.flowui.kit.component.usermenu.UserMenuItem;
import io.jmix.flowui.kit.component.usermenu.UserMenuItem.HasSubMenu;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.View;
import io.jmix.flowui.view.ViewRegistry;
import io.jmix.flowui.view.ViewValidation;
import jakarta.servlet.ServletContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.log.LogFormatUtils;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The Language item of the user menu, which on the stand moves the visitor to a fresh copy in the language.
 */
@ActionType(UserMenuLanguageSwitchAction.ID)
public class UserMenuLanguageSwitchAction extends UserMenuAction<UserMenuLanguageSwitchAction, UserMenu> {

    public static final String ID = "userMenu_languageSwitch";

    private static final Logger log = LoggerFactory.getLogger(UserMenuLanguageSwitchAction.class);

    private CoreProperties coreProperties;
    private CurrentAuthentication currentAuthentication;
    private MessageTools messageTools;
    private ViewValidation viewValidation;
    private Messages messages;
    private Dialogs dialogs;
    private Notifications notifications;
    private UiProperties uiProperties;
    private ViewRegistry viewRegistry;
    private ServletContext servletContext;

    private ObjectProvider<StandLogin> standLogin;

    private HasSubMenu.SubMenu subMenu;
    private final Map<Locale, UserMenuItem> localeItems = new HashMap<>();

    public UserMenuLanguageSwitchAction() {
        this(ID);
    }

    public UserMenuLanguageSwitchAction(String id) {
        super(id);
    }

    @Autowired
    public void setMessages(Messages messages) {
        this.messages = messages;
        text = messages.getMessage(getClass(), "actions.userMenu.LanguageSwitch");
    }

    @Autowired
    public void setIcons(Icons icons) {
        icon = icons.get(JmixFontIcon.GLOBE);
    }

    @Autowired
    public void setCoreProperties(CoreProperties coreProperties) {
        this.coreProperties = coreProperties;
    }

    @Autowired
    public void setCurrentAuthentication(CurrentAuthentication currentAuthentication) {
        this.currentAuthentication = currentAuthentication;
    }

    @Autowired
    public void setMessageTools(MessageTools messageTools) {
        this.messageTools = messageTools;
    }

    @Autowired
    public void setViewValidation(ViewValidation viewValidation) {
        this.viewValidation = viewValidation;
    }

    @Autowired
    public void setDialogs(Dialogs dialogs) {
        this.dialogs = dialogs;
    }

    @Autowired
    public void setNotifications(Notifications notifications) {
        this.notifications = notifications;
    }

    @Autowired
    public void setUiProperties(UiProperties uiProperties) {
        this.uiProperties = uiProperties;
    }

    @Autowired
    public void setViewRegistry(ViewRegistry viewRegistry) {
        this.viewRegistry = viewRegistry;
    }

    @Autowired
    public void setServletContext(ServletContext servletContext) {
        this.servletContext = servletContext;
    }

    @Autowired
    public void setStandLogin(ObjectProvider<StandLogin> standLogin) {
        this.standLogin = standLogin;
    }

    @Override
    protected void setMenuItemInternal(@Nullable UserMenuItem menuItem) {
        super.setMenuItemInternal(menuItem);

        if (subMenu != null) {
            subMenu.removeAll();
            subMenu = null;
            localeItems.clear();
        }

        if (menuItem == null) {
            return;
        }

        if (!(menuItem instanceof HasSubMenu hasSubMenu)) {
            throw new IllegalStateException("%s does not implement %s"
                    .formatted(menuItem, HasSubMenu.class.getSimpleName()));
        }

        subMenu = hasSubMenu.getSubMenu();
        for (Locale locale : coreProperties.getAvailableLocales()) {
            UserMenuItem item = subMenu.addTextItem("%s_%sUserMenuItem".formatted(ID, locale.toLanguageTag()),
                    messageTools.getLocaleDisplayName(locale),
                    event -> switchTo(locale));
            item.setCheckable(true);
            localeItems.put(locale, item);
        }

        updateCheckedItems();
    }

    /**
     * Switches to the language, as the item of the language does; the language in use changes nothing.
     */
    public void switchTo(Locale locale) {
        // a click toggles the check mark, the language in use stays checked until the page reloads
        updateCheckedItems();

        if (locale.equals(currentAuthentication.getLocale())) {
            return;
        }

        // on the stand the visitor's data are in the language, so another one takes a fresh copy of them
        StandLogin stand = standLogin.getIfAvailable();

        if (stand != null) {
            confirmNewCopy(stand, locale);
            return;
        }

        if (UI.getCurrent().getCurrentView() instanceof StandardDetailView<?> detailView
                && detailView.hasUnsavedChanges()) {
            viewValidation.showUnsavedChangesDialog(detailView)
                    .onDiscard(() -> detailView.closeWithDiscard()
                            .then(() -> applyLocale(locale)));
            return;
        }

        applyLocale(locale);
    }

    private void applyLocale(Locale locale) {
        VaadinSession.getCurrent().setLocale(locale);

        Authentication authentication = currentAuthentication.getAuthentication();
        if (authentication instanceof AbstractAuthenticationToken token) {
            ClientDetails details = token.getDetails() instanceof ClientDetails clientDetails
                    ? ClientDetails.builder().of(clientDetails).locale(locale).build()
                    : ClientDetails.builder().locale(locale).build();
            token.setDetails(details);
        }

        UI.getCurrent().getPage().reload();
    }

    private void confirmNewCopy(StandLogin stand, Locale locale) {
        if (!stand.isReady(locale)) {
            showNotice(messages.getMessage(LoginView.class, "standGettingReady"), Notifications.Type.WARNING);
            return;
        }

        dialogs.createOptionDialog()
                .withHeader(messages.getMessage(getClass(), "languageSwitchDialog.header"))
                .withText(messages.getMessage(getClass(), "languageSwitchDialog.text"))
                .withActions(
                        new DialogAction(DialogAction.Type.OK)
                                .withHandler(event -> switchCopy(stand, locale)),
                        new DialogAction(DialogAction.Type.CANCEL)
                                .withVariant(ActionVariant.PRIMARY))
                .open();
    }

    private void switchCopy(StandLogin stand, Locale locale) {
        try {
            stand.switchLanguage(locale);
        } catch (TemplateNotReadyException e) {
            showNotice(messages.getMessage(LoginView.class, "standGettingReady"), Notifications.Type.WARNING);
            return;
        } catch (CopyNotMadeException e) {
            // what failed names paths and indexes of the server: the log keeps it, and the visitor does not need it
            log.error("A copy of the demo data is not made at a change of the language: {}", StandScope.withTags(e));
            showNotice(messages.getMessage(getClass(), "languageSwitchFailed"), Notifications.Type.ERROR);
            return;
        } catch (AuthenticationException | AccessDeniedException e) {
            // a username a visitor chose may hold line breaks, which would forge lines of the log
            String username = LogFormatUtils.formatValue(currentAuthentication.getUser().getUsername(), -1, true);
            log.warn("The language is not changed for user {}: {}", username, e.toString());
            showNotice(messages.getMessage(getClass(), "languageSwitchRefused"), Notifications.Type.ERROR);
            return;
        }

        openMainViewInEveryTab();
    }

    private void openMainViewInEveryTab() {
        String location = mainViewLocation();
        UI current = UI.getCurrent();
        current.getPage().setLocation(location);

        for (UI tab : current.getSession().getUIs()) {
            if (tab != current && !tab.isClosing()) {
                tab.access(() -> tab.getPage().setLocation(location));
            }
        }
    }

    private void showNotice(String text, Notifications.Type type) {
        notifications.create(text)
                .withType(type)
                .show();
    }

    private String mainViewLocation() {
        Class<? extends View<?>> mainView = viewRegistry.getViewInfo(uiProperties.getMainViewId())
                .getControllerClass();

        String route = RouteConfiguration.forSessionScope()
                .getUrl(mainView);

        return servletContext.getContextPath() + "/" + route;
    }

    private void updateCheckedItems() {
        Locale currentLocale = currentAuthentication.getLocale();
        localeItems.forEach((locale, item) -> item.setChecked(locale.equals(currentLocale)));
    }

    @Override
    public void execute() {
        // do nothing
    }
}
