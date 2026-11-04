package com.company.docai.view.main;

import com.company.docai.DocAiApplication;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestMenus;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.html.Div;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.settings.UserSettingsService;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The width of the main menu, which the user drags with its edge and finds again on the next visit.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class MainViewDrawerWidthTest {

    private static final String DRAWER_WIDTH = "--main-view-drawer-width";
    private static final String LEFT_WIDTH = "22.5";

    @Autowired
    private UserSettingsService userSettingsService;

    // the main view of a test opens before the test, so the width the user left is saved before all of them
    @BeforeAll
    static void rememberAWidth(@Autowired SystemAuthenticator systemAuthenticator,
                               @Autowired UserSettingsService userSettingsService) {
        systemAuthenticator.runWithUser(EmployeeUiTestAuthenticator.USERNAME,
                () -> userSettingsService.save(MainView.DRAWER_WIDTH_SETTING, LEFT_WIDTH));
    }

    @AfterEach
    void leaveTheWidthAsItWas() {
        userSettingsService.save(MainView.DRAWER_WIDTH_SETTING, LEFT_WIDTH);
    }

    @AfterAll
    static void forgetTheWidth(@Autowired SystemAuthenticator systemAuthenticator,
                               @Autowired UserSettingsService userSettingsService) {
        systemAuthenticator.runWithUser(EmployeeUiTestAuthenticator.USERNAME,
                () -> userSettingsService.delete(MainView.DRAWER_WIDTH_SETTING));
    }

    @Test
    void theMenuOpensAsWideAsTheUserLeftIt() {
        assertThat(TestMenus.mainView().getElement().getStyle().get(DRAWER_WIDTH)).isEqualTo("22.5rem");
    }

    @Test
    void aDraggedEdgeKeepsTheMenuWithinItsLimitsAndRemembersItsWidth() {
        Div handle = UiTestUtils.getComponent(TestMenus.mainView(), "drawerHandle");

        ComponentUtil.fireEvent(handle, new MainView.DrawerResizeEvent(handle, true, 40));

        assertThat(TestMenus.mainView().getElement().getStyle().get(DRAWER_WIDTH)).isEqualTo("32.0rem");
        assertThat(userSettingsService.load(MainView.DRAWER_WIDTH_SETTING)).contains("32.0");

        ComponentUtil.fireEvent(handle, new MainView.DrawerResizeEvent(handle, true, 19.1));

        assertThat(TestMenus.mainView().getElement().getStyle().get(DRAWER_WIDTH)).isEqualTo("19.0rem");
        assertThat(userSettingsService.load(MainView.DRAWER_WIDTH_SETTING)).contains("19.0");
    }
}
