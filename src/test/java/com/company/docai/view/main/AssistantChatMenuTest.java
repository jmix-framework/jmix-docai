package com.company.docai.view.main;

import com.company.docai.DocAiApplication;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.StubAiConfiguration;
import com.company.docai.test_support.TestMenus;
import com.vaadin.flow.dom.Element;
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
 * The assistant in the main menu of an employee when the AI is on: the item stands above the folders and opens
 * the chat.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class,
        StubAiConfiguration.class})
@TestPropertySource(properties = "spring.ai.openai.api-key=test-key")
class AssistantChatMenuTest {

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private AiChatService chatService;

    @Test
    void anEmployeeOpensTheAssistantFromTheMenuAboveTheFolders() {
        JmixListMenu menu = TestMenus.listMenu("menu");
        JmixListMenu assistantMenu = UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "assistantMenu");
        ListMenu.MenuItem assistantChat = assistantMenu.getMenuItem("assistantChat");

        // the item wears the accent of the AI and is no longer among the views under the folders
        assertThat(assistantChat).isNotNull();
        assertThat(assistantChat.getClassNames()).contains("aura-accent-purple");
        assertThat(assistantMenu.getMenuItems()).containsExactly(assistantChat);
        assertThat(menu.getMenuItem("assistantChat")).isNull();

        viewNavigators.view(UiTestUtils.getCurrentView(), AiChatHubView.class).navigate();
        View<?> opened = UiTestUtils.getCurrentView();

        assertThat(chatService.isAvailable()).isTrue();
        assertThat(opened).isInstanceOf(AiChatHubView.class);
    }

    @Test
    void theAssistantAndTheViewsEachStandInTheirOwnLabelledNavigationLandmark() {
        viewNavigators.view(UiTestUtils.getCurrentView(), MainView.class).navigate();

        String assistantLabel = landmarkLabel(TestMenus.listMenu("assistantMenu"));
        String viewsLabel = landmarkLabel(TestMenus.listMenu("menu"));

        assertThat(assistantLabel).isNotBlank();
        assertThat(viewsLabel).isNotBlank().isNotEqualTo(assistantLabel);
    }

    private static String landmarkLabel(JmixListMenu menu) {
        Element element = menu.getElement();

        while (element != null && !"nav".equals(element.getTag())) {
            element = element.getParent();
        }

        assertThat(element).as("the nav element around the menu").isNotNull();

        return element.getAttribute("aria-label");
    }
}
