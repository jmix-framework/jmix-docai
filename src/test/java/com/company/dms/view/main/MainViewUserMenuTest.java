package com.company.dms.view.main;

import com.company.dms.DMSApplication;
import com.company.dms.test_support.EmployeeUiTestAuthenticator;
import com.company.dms.view.profile.ProfileReadView;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.component.usermenu.UserMenu;
import io.jmix.flowui.kit.component.usermenu.ActionUserMenuItem;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DMSApplication.class, FlowuiTestAssistConfiguration.class})
class MainViewUserMenuTest {

    @Test
    void theProfileItemOpensTheProfileOfTheCurrentUser() {
        ActionUserMenuItem item = (ActionUserMenuItem) userMenu().getItem("profileMenuItem");

        item.getAction().actionPerform(userMenu());

        ProfileReadView view = UiTestUtils.getLastOpenedViewDialog();
        TypedTextField<String> usernameField = UiTestUtils.getComponent(view, "usernameField");
        assertThat(usernameField.getValue()).isEqualTo("peter");
    }

    private UserMenu userMenu() {
        return UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "userMenu");
    }
}
