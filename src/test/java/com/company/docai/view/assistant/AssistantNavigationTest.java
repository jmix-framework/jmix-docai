package com.company.docai.view.assistant;

import com.company.docai.DocAiApplication;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.user.User;
import com.company.docai.security.role.UiMinimalRole;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.StubAiConfiguration;
import com.company.docai.test_support.TestPalettes;
import com.company.docai.view.main.MainView;
import com.company.docai.view.repository.RepositoryView;
import com.vaadin.flow.component.internal.PendingJavaScriptInvocation;
import com.vaadin.flow.router.QueryParameters;
import io.jmix.aitoolsflowui.service.AiConversationService;
import io.jmix.core.SaveContext;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.data.PersistenceHints;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.settings.UserSettingsCache;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.security.role.assignment.RoleAssignmentRoleType;
import io.jmix.securitydata.entity.RoleAssignmentEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.node.StringNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assistant as the navigation bean opens it while the AI is on. The models are stubs, and nothing here asks them.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class,
        StubAiConfiguration.class})
@TestPropertySource(properties = "spring.ai.openai.api-key=test-key")
class AssistantNavigationTest {

    private static final String TEST_USER_PREFIX = "assistant-navigation-test-";

    @Autowired
    private AssistantNavigation assistantNavigation;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private AiConversationService conversationService;

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private UserSettingsCache userSettingsCache;

    @AfterEach
    void tearDown() {
        conversationService.loadConversations().forEach(conversationService::remove);
        // leaving the repository saves the column settings of the document list for the user
        userSettingsCache.delete("RepositoryView.documentList");

        // a role assignment is only marked as deleted by default
        unconstrainedDataManager.save(new SaveContext()
                .removing(unconstrainedDataManager.load(RoleAssignmentEntity.class)
                        .query("e.username like ?1", TEST_USER_PREFIX + "%")
                        .list()
                        .toArray())
                .setHint(PersistenceHints.SOFT_DELETION, false));

        unconstrainedDataManager.load(User.class)
                .query("e.username like ?1", TEST_USER_PREFIX + "%")
                .list()
                .forEach(unconstrainedDataManager::remove);
    }

    @Test
    void anEmployeeHasTheAssistant() {
        assertThat(assistantNavigation.isAvailable()).isTrue();
    }

    @Test
    void aUserWhoMayNotOpenTheChatHasNoAssistantThoughTheAiIsOn() {
        // the roles people sign in with let them open the chat; the minimal role only lets them in
        String username = minimalUser();

        assertThat(systemAuthenticator.withUser(username, assistantNavigation::isAvailable)).isFalse();
    }

    @Test
    void aChatOpensAtItsOwnAddressWithoutTheQueryOfThePageItWasOpenedFrom() {
        UUID conversation = conversationService.create().getId();

        // the dashboard of the main view, which a link with a query of its own may open
        assertThat(lastAddressAfter(() -> assistantNavigation.openChat(UiTestUtils.getCurrentView(), conversation),
                "?ref=mail"))
                .isEqualTo("aitls/chat/" + conversation);

        // the repository with a folder chosen, whose discussion window opens its conversation in the full chat
        viewNavigators.view(UiTestUtils.getCurrentView(), RepositoryView.class)
                .withQueryParameters(QueryParameters.of(RepositoryView.FOLDER_PARAMETER, Folder.ROOT_ID.toString()))
                .navigate();

        assertThat(lastAddressAfter(() -> assistantNavigation.openChat(UiTestUtils.getCurrentView(), conversation),
                "repository?folder=" + Folder.ROOT_ID))
                .isEqualTo("aitls/chat/" + conversation);
    }

    // the address the browser shows last after the opening on the page with the given address. While the chat opens
    // the browser still shows that page, and it moves on to the chat only after the response, so it answers every
    // request for its address with the address of the page.
    private String lastAddressAfter(Runnable opening, String pageAddress) {
        TestPalettes.sentScripts();

        opening.run();
        List<PendingJavaScriptInvocation> scripts = new ArrayList<>(TestPalettes.sentScripts());

        scripts.stream()
                .filter(script -> script.getInvocation().getExpression().contains("window.location.href"))
                .forEach(script -> script.complete(StringNode.valueOf("http://localhost/docai/" + pageAddress)));

        scripts.addAll(TestPalettes.sentScripts());

        // the router of the page takes each new address from an event, or the history of the browser takes it
        return scripts.stream()
                .map(PendingJavaScriptInvocation::getInvocation)
                .filter(invocation -> invocation.getExpression().contains("new CustomEvent('vaadin-navigate'")
                        || invocation.getExpression().contains("history.pushState(")
                        || invocation.getExpression().contains("history.replaceState("))
                .map(invocation -> String.valueOf(invocation.getParameters().get(1)))
                .reduce((first, last) -> last)
                .orElseThrow();
    }

    private String minimalUser() {
        User user = unconstrainedDataManager.create(User.class);
        user.setUsername(TEST_USER_PREFIX + System.nanoTime());

        RoleAssignmentEntity assignment = unconstrainedDataManager.create(RoleAssignmentEntity.class);
        assignment.setUsername(user.getUsername());
        assignment.setRoleCode(UiMinimalRole.CODE);
        assignment.setRoleType(RoleAssignmentRoleType.RESOURCE);

        unconstrainedDataManager.save(user, assignment);

        return user.getUsername();
    }
}
