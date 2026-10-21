package com.company.dms.view.main;

import com.company.dms.DMSApplication;
import com.company.dms.app.ui.action.UserMenuLanguageSwitchAction;
import com.company.dms.test_support.EmployeeUiTestAuthenticator;
import com.company.dms.view.profile.ProfileReadView;
import com.vaadin.flow.server.VaadinSession;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.component.usermenu.UserMenu;
import io.jmix.flowui.kit.component.usermenu.ActionUserMenuItem;
import io.jmix.flowui.kit.component.usermenu.UserMenuItem;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Locale;

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
        ActionUserMenuItem item = (ActionUserMenuItem) userMenu().getItem("languageMenuItem");

        ((UserMenuLanguageSwitchAction) item.getAction()).switchTo(RUSSIAN);

        assertThat(currentAuthentication.getLocale()).isEqualTo(RUSSIAN);
        assertThat(VaadinSession.getCurrent().getLocale()).isEqualTo(RUSSIAN);
    }

    private UserMenu userMenu() {
        return UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "userMenu");
    }
}
