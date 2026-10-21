package com.company.dms.view.main;

import com.company.dms.DMSApplication;
import com.company.dms.app.ui.action.UserMenuLanguageSwitchAction;
import com.company.dms.model.user.User;
import com.company.dms.view.user.UserDetailView;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.component.usermenu.UserMenu;
import io.jmix.flowui.kit.component.usermenu.ActionUserMenuItem;
import io.jmix.flowui.kit.component.usermenu.UserMenuItem;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.dialog.DialogInfo;
import io.jmix.flowui.view.View;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@UiTest
@SpringBootTest(classes = {DMSApplication.class, FlowuiTestAssistConfiguration.class})
class LanguageSwitchUnsavedChangesTest {

    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");

    @Autowired
    private CurrentAuthentication currentAuthentication;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private Messages messages;

    @Autowired
    private ViewNavigators viewNavigators;

    @BeforeEach
    void changePeterWithoutSaving() {
        viewNavigators.detailView(UiTestUtils.getCurrentView(), User.class)
                .editEntity(loadPeter())
                .navigate();

        firstNameField().setValue("Changed");
    }

    @Test
    void switchingAsksBeforeDiscardingTheChanges() {
        languageAction().switchTo(RUSSIAN);

        DialogInfo dialog = UiTestUtils.getLastOpenedDialog();
        assertThat(dialog).isNotNull();
        assertThat(dialog.getDialog().getHeaderTitle())
                .isEqualTo(messages.getMessage("dialogs.closeUnsaved.title"));
        assertThat(currentAuthentication.getLocale()).isEqualTo(Locale.ENGLISH);
        assertThat(pageReloadRequested()).isFalse();
    }

    @Test
    void confirmingDiscardsTheChangesAndSwitchesTheLanguage() {
        languageAction().switchTo(RUSSIAN);

        dialogButton(DialogAction.Type.YES).click();

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isNotInstanceOf(UserDetailView.class);
        assertThat(currentAuthentication.getLocale()).isEqualTo(RUSSIAN);
        assertThat(pageReloadRequested()).isTrue();
        assertThat(loadPeter().getFirstName()).isEqualTo("Peter");
    }

    @Test
    void cancellingKeepsTheChangesAndTheLanguage() {
        UserMenuItem russian = languageItem("ru");
        russian.setChecked(true); // a click on an item toggles its check mark

        languageAction().switchTo(RUSSIAN);
        dialogButton(DialogAction.Type.NO).click();

        assertThat(firstNameField().getValue()).isEqualTo("Changed");
        assertThat(currentAuthentication.getLocale()).isEqualTo(Locale.ENGLISH);
        assertThat(pageReloadRequested()).isFalse();
        assertThat(languageItem("en").isChecked()).isTrue();
        assertThat(russian.isChecked()).isFalse();
    }

    private User loadPeter() {
        return dataManager.load(User.class)
                .query("e.username = ?1", "peter")
                .one();
    }

    private TypedTextField<String> firstNameField() {
        return UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "firstNameField");
    }

    private UserMenu userMenu() {
        MainView mainView = UiTestUtils.getCurrentView().findAncestor(MainView.class);
        return UiTestUtils.getComponent(mainView, "userMenu");
    }

    private UserMenuLanguageSwitchAction languageAction() {
        ActionUserMenuItem item = (ActionUserMenuItem) userMenu().getItem("languageMenuItem");
        return (UserMenuLanguageSwitchAction) item.getAction();
    }

    private UserMenuItem languageItem(String languageTag) {
        UserMenuItem.HasSubMenu item = (UserMenuItem.HasSubMenu) userMenu().getItem("languageMenuItem");
        return item.getSubMenu().getItem("userMenu_languageSwitch_%sUserMenuItem".formatted(languageTag));
    }

    private Button dialogButton(DialogAction.Type type) {
        DialogInfo dialog = UiTestUtils.getLastOpenedDialog();
        assertThat(dialog).isNotNull();
        return dialog.getButtons().stream()
                .filter(button -> button.getId().equals(Optional.of(type.getId())))
                .findFirst()
                .orElseThrow();
    }

    private boolean pageReloadRequested() {
        return UI.getCurrent().getInternals().containsPendingJavascript("window.location.reload()");
    }
}
