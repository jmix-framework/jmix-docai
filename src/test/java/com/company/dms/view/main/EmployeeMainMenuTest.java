package com.company.dms.view.main;

import com.company.dms.DMSApplication;
import com.company.dms.test_support.EmployeeUiTestAuthenticator;
import io.jmix.flowui.component.main.JmixListMenu;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The main menu of an employee: only the administrator opens the view of document types.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DMSApplication.class, FlowuiTestAssistConfiguration.class})
class EmployeeMainMenuTest {

    @Test
    void anEmployeeHasNoDocumentTypesInTheMenu() {
        JmixListMenu menu = UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "menu");

        assertThat(menu.getMenuItem("RepositoryView")).isNotNull();
        assertThat(menu.getMenuItem("documentTypes")).isNull();
    }
}
