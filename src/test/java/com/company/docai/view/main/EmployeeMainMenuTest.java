package com.company.docai.view.main;

import com.company.docai.DocAiApplication;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
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
 * The main menu of an employee: only the administrator opens the views of document types and tags, and while the AI
 * is off the assistant is not there, and its chat, reached by its address, takes no questions.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class EmployeeMainMenuTest {

    @Autowired
    private AiChatService chatService;

    @Test
    void anEmployeeHasTheRepositoryAndTheTrashButNeitherDocumentTypesNorTagsInTheMenu() {
        JmixListMenu menu = UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "menu");

        assertThat(menu.getMenuItem("RepositoryView")).isNotNull();
        assertThat(menu.getMenuItem("TrashView")).isNotNull();
        assertThat(menu.getMenuItem("documentTypes")).isNull();
        assertThat(menu.getMenuItem("Tag.list")).isNull();
    }

    @Test
    void withoutTheKeyTheAssistantIsNotInTheMenuAndItsChatTakesNoQuestions() {
        JmixListMenu menu = UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "menu");

        assertThat(menu.getMenuItem("assistantChat")).isNull();
        assertThat(chatService.isAvailable()).isFalse();
    }
}
