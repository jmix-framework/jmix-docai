package com.company.dms.view.main;

import com.company.dms.DMSApplication;
import com.company.dms.test_support.EmployeeUiTestAuthenticator;
import com.company.dms.test_support.StubChatModelConfiguration;
import io.jmix.aitoolsflowui.service.AiChatService;
import io.jmix.aitoolsflowui.view.chathub.AiChatHubView;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.main.JmixListMenu;
import io.jmix.flowui.kit.component.main.ListMenu;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.view.View;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assistant in the main menu of an employee when the AI is on: the item shows and opens the chat.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DMSApplication.class, FlowuiTestAssistConfiguration.class,
        StubChatModelConfiguration.class})
@TestPropertySource(properties = "spring.ai.openai.api-key=test-key")
class AssistantChatMenuTest {

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private AiChatService chatService;

    @Test
    void anEmployeeOpensTheAssistantFromTheMenu() {
        JmixListMenu menu = UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "menu");
        ListMenu.MenuItem assistantChat = menu.getMenuItem("assistantChat");

        viewNavigators.view(UiTestUtils.getCurrentView(), AiChatHubView.class).navigate();
        View<?> opened = UiTestUtils.getCurrentView();

        assertThat(assistantChat).isNotNull();
        assertThat(chatService.isAvailable()).isTrue();
        assertThat(opened).isInstanceOf(AiChatHubView.class);
    }
}
