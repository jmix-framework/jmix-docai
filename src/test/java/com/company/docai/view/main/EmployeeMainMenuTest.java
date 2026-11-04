package com.company.docai.view.main;

import com.company.docai.DocAiApplication;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestMenus;
import com.vaadin.flow.dom.Element;
import io.jmix.aitoolsflowui.service.AiChatService;
import io.jmix.flowui.component.main.JmixListMenu;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The main menu of an employee: the folders lead to the repository, the trash stands under them, and only the
 * administrator has the administration; while the AI is off the assistant is not there, and its chat, reached by its
 * address, takes no questions.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class EmployeeMainMenuTest {

    @Autowired
    private AiChatService chatService;

    @Test
    void anEmployeeHasTheTrashButNoAdministrationInTheMenu() {
        JmixListMenu menu = TestMenus.listMenu("menu");

        assertThat(menu.getMenuItem("TrashView")).isNotNull();
        assertThat(menu.getMenuItem("administration")).isNull();
        assertThat(menu.getMenuItem("documentTypes")).isNull();
        assertThat(menu.getMenuItem("Tag.list")).isNull();
        assertThat(menu.getMenuItem("DocumentStatus.list")).isNull();
    }

    @Test
    void withoutTheKeyTheAssistantIsNotInTheMenuAndItsChatTakesNoQuestions() {
        JmixListMenu menu = TestMenus.listMenu("menu");
        JmixListMenu assistantMenu = UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "assistantMenu");

        assertThat(menu.getMenuItem("assistantChat")).isNull();
        assertThat(assistantMenu.getMenuItems()).isEmpty();
        assertThat(chatService.isAvailable()).isFalse();
    }

    @Test
    void withoutTheAssistantNoEmptyNavigationLandmarkStaysOnThePage() {
        JmixListMenu assistantMenu = TestMenus.listMenu("assistantMenu");
        Element element = assistantMenu.getElement();

        while (element != null && !"nav".equals(element.getTag())) {
            element = element.getParent();
        }

        assertThat(element).as("the nav element around the assistant menu").isNotNull();
        assertThat(element.isVisible()).isFalse();
    }
}
