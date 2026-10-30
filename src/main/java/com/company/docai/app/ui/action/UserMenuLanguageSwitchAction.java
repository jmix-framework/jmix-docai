package com.company.docai.app.ui.action;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinSession;
import io.jmix.core.CoreProperties;
import io.jmix.core.MessageTools;
import io.jmix.core.Messages;
import io.jmix.core.security.ClientDetails;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.action.ActionType;
import io.jmix.flowui.action.usermenu.UserMenuAction;
import io.jmix.flowui.component.usermenu.UserMenu;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.component.usermenu.UserMenuItem;
import io.jmix.flowui.kit.component.usermenu.UserMenuItem.HasSubMenu;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewValidation;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@ActionType(UserMenuLanguageSwitchAction.ID)
public class UserMenuLanguageSwitchAction extends UserMenuAction<UserMenuLanguageSwitchAction, UserMenu> {

    public static final String ID = "userMenu_languageSwitch";

    private CoreProperties coreProperties;
    private CurrentAuthentication currentAuthentication;
    private MessageTools messageTools;
    private ViewValidation viewValidation;

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

    public void switchTo(Locale locale) {
        // a click toggles the check mark, the language in use stays checked until the page reloads
        updateCheckedItems();

        if (locale.equals(currentAuthentication.getLocale())) {
            return;
        }

        // reloading the page would lose the changes of an open editor
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

    private void updateCheckedItems() {
        Locale currentLocale = currentAuthentication.getLocale();
        localeItems.forEach((locale, item) -> item.setChecked(locale.equals(currentLocale)));
    }

    @Override
    public void execute() {
        // do nothing, the items of the submenu switch the language
    }
}
