package com.company.dms.view.main;

import com.company.dms.DMSApplication;
import com.company.dms.app.ui.action.UserMenuLanguageSwitchAction;
import com.company.dms.test_support.EmployeeUiTestAuthenticator;
import com.company.dms.view.profile.ProfileReadView;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinSession;
import io.jmix.core.security.ClientDetails;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.component.usermenu.UserMenu;
import io.jmix.flowui.kit.component.usermenu.ActionUserMenuItem;
import io.jmix.flowui.kit.component.usermenu.UserMenuItem;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.security.model.SecurityScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.Locale;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DMSApplication.class, FlowuiTestAssistConfiguration.class})
class MainViewUserMenuTest {

    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");

    @Autowired
    private CurrentAuthentication currentAuthentication;

    @Test
    void theProfileItemOpensTheProfileOfTheCurrentUser() {
        ActionUserMenuItem item = (ActionUserMenuItem) userMenu().getItem("profileMenuItem");

        item.getAction().actionPerform(userMenu());

        ProfileReadView view = UiTestUtils.getLastOpenedViewDialog();
        TypedTextField<String> usernameField = UiTestUtils.getComponent(view, "usernameField");
        assertThat(usernameField.getValue()).isEqualTo("peter");
    }

    @Test
    void theLanguageItemTakesItsTextFromTheBundle() {
        ActionUserMenuItem item = (ActionUserMenuItem) userMenu().getItem("languageMenuItem");

        assertThat(item.getAction().getText()).isEqualTo("Language");
    }

    @Test
    void theLanguageItemOffersEveryLanguageAndMarksTheCurrentOne() {
        UserMenuItem.HasSubMenu item = (UserMenuItem.HasSubMenu) userMenu().getItem("languageMenuItem");

        assertThat(item.getSubMenu().getItems())
                .extracting(UserMenuItem::getId, UserMenuItem::isChecked)
                .containsExactly(
                        tuple("userMenu_languageSwitch_enUserMenuItem", true),
                        tuple("userMenu_languageSwitch_ruUserMenuItem", false));
    }

    @Test
    void switchingTheLanguageChangesTheSessionAndAuthenticationLocale() {
        languageAction().switchTo(RUSSIAN);

        assertThat(currentAuthentication.getLocale()).isEqualTo(RUSSIAN);
        assertThat(VaadinSession.getCurrent().getLocale()).isEqualTo(RUSSIAN);
        assertThat(pageReloadRequested()).isTrue();
    }

    @Test
    void switchingTheLanguageKeepsTheClientDetailsOfTheLogin() {
        AbstractAuthenticationToken token = (AbstractAuthenticationToken) currentAuthentication.getAuthentication();
        TimeZone timeZone = TimeZone.getTimeZone("Asia/Tokyo");
        token.setDetails(ClientDetails.builder()
                .locale(Locale.ENGLISH)
                .timeZone(timeZone)
                .scope(SecurityScope.UI)
                .sessionId("session-1")
                .build());

        languageAction().switchTo(RUSSIAN);

        assertThat(currentAuthentication.getAuthentication()).isSameAs(token);
        ClientDetails details = (ClientDetails) token.getDetails();
        assertThat(details.getLocale()).isEqualTo(RUSSIAN);
        assertThat(details.getTimeZone()).isEqualTo(timeZone);
        assertThat(details.getScope()).isEqualTo(SecurityScope.UI);
        assertThat(details.getSessionId()).isEqualTo("session-1");
    }

    @Test
    void choosingTheCurrentLanguageKeepsItCheckedWithoutReload() {
        UserMenuItem.HasSubMenu languageItem = (UserMenuItem.HasSubMenu) userMenu().getItem("languageMenuItem");
        UserMenuItem english = languageItem.getSubMenu().getItem("userMenu_languageSwitch_enUserMenuItem");
        english.setChecked(false); // a click on a checked item un-checks it

        languageAction().switchTo(Locale.ENGLISH);

        assertThat(english.isChecked()).isTrue();
        assertThat(pageReloadRequested()).isFalse();
    }

    private UserMenu userMenu() {
        return UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "userMenu");
    }

    private UserMenuLanguageSwitchAction languageAction() {
        ActionUserMenuItem item = (ActionUserMenuItem) userMenu().getItem("languageMenuItem");
        return (UserMenuLanguageSwitchAction) item.getAction();
    }

    private boolean pageReloadRequested() {
        return UI.getCurrent().getInternals().containsPendingJavascript("window.location.reload()");
    }
}
