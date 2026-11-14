package com.company.docai.view.assistant;

import com.company.docai.ai.AiSettings;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.Location;
import io.jmix.aitoolsflowui.model.AiConversation;
import io.jmix.aitoolsflowui.service.AiConversationService;
import io.jmix.aitoolsflowui.view.chat.AiChatView;
import io.jmix.aitoolsflowui.view.chathub.AiChatHubFragment;
import io.jmix.aitoolsflowui.view.chathub.AiChatHubView;
import io.jmix.core.AccessManager;
import io.jmix.core.Messages;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.accesscontext.UiShowViewContext;
import io.jmix.flowui.app.main.StandardMainView;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.view.View;
import io.jmix.flowui.view.navigation.RouteSupport;
import io.jmix.flowui.view.navigation.ViewClassNavigator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Opens the assistant of the AI Tools add-on: its chat on a new conversation with a question, its chat on a
 * conversation that has begun, and the hub with the chats of the user; and tells whether the user has the assistant
 * at all. The search palette, the dashboard and the document list of the repository with its discussion window go
 * through it.
 */
@Component
public class AssistantNavigation {

    private static final Logger log = LoggerFactory.getLogger(AssistantNavigation.class);

    private static final String ASSISTANT_CHAT_VIEW = "aitls_AiChatView";

    private final ViewNavigators viewNavigators;
    private final AiSettings aiSettings;
    private final AccessManager accessManager;
    private final AiConversationService conversationService;
    private final RouteSupport routeSupport;
    private final Notifications notifications;
    private final Messages messages;

    public AssistantNavigation(ViewNavigators viewNavigators, AiSettings aiSettings, AccessManager accessManager,
                               AiConversationService conversationService, RouteSupport routeSupport,
                               Notifications notifications, Messages messages) {
        this.viewNavigators = viewNavigators;
        this.aiSettings = aiSettings;
        this.accessManager = accessManager;
        this.conversationService = conversationService;
        this.routeSupport = routeSupport;
        this.notifications = notifications;
        this.messages = messages;
    }

    /**
     * Returns whether the user has the assistant: the AI is on and the user may open the chat of the assistant.
     */
    public boolean isAvailable() {
        UiShowViewContext assistantChat = new UiShowViewContext(ASSISTANT_CHAT_VIEW);
        accessManager.applyRegisteredConstraints(assistantChat);

        return aiSettings.isAvailable() && assistantChat.isPermitted();
    }

    /**
     * Opens the chat of the assistant on a new conversation, which answers the question at once, as when a
     * conversation starts in the hub. The view open now is the origin: from an open chat Jmix then makes a new one,
     * which sends its first message once. A conversation that cannot be created gives an error notice instead.
     */
    public void openAssistant(String question) {
        AiConversation conversation;

        try {
            conversation = conversationService.create();
        } catch (RuntimeException e) {
            log.error("The conversation with the assistant was not created", e);

            notifications.create(messages.getMessage(AiChatHubFragment.class,
                            "aiChatHubFragment.errorProcessingMessage"))
                    .withType(Notifications.Type.ERROR)
                    .show();

            return;
        }

        View<?> origin = UiComponentUtils.getCurrentView();

        ViewClassNavigator<AiChatView> navigator = viewNavigators.view(origin, AiChatView.class)
                .withRouteParameters(routeSupport.createRouteParameters(AiChatView.ROUTE_PARAM_ID,
                        conversation.getId()));

        // the bare main view never detaches, as it stays the layout of the chat, so it sends the question itself
        if (origin instanceof StandardMainView) {
            navigator.navigate();

            if (UiComponentUtils.getCurrentView() instanceof AiChatView chat) {
                chat.sendInitialPrompt(question);
                showChatAddress();
            }

            return;
        }

        // the handler runs when the origin detaches; a chat that is still answering lets it go only after Leave. After
        // Stay the handler stays on that chat and later runs with the chat itself, which it leaves alone. The new
        // conversation then stays empty, as when the start of a conversation in the hub is cancelled.
        navigator.withAfterNavigationHandler(navigation -> {
                    if (navigation.getView() != origin) {
                        navigation.getView().sendInitialPrompt(question);
                        showChatAddress();
                    }
                })
                .navigate();
    }

    /**
     * Opens the chat of the assistant on the conversation, where it stopped.
     */
    public void openChat(View<?> origin, UUID conversationId) {
        viewNavigators.view(origin, AiChatView.class)
                .withRouteParameters(routeSupport.createRouteParameters(AiChatView.ROUTE_PARAM_ID, conversationId))
                .navigate();

        // an origin that holds the person on the way out stays shown, with its own address
        if (UiComponentUtils.getCurrentView() instanceof AiChatView) {
            showChatAddress();
        }
    }

    /**
     * Opens the hub of the assistant with all the chats of the user.
     */
    public void openChats(View<?> origin) {
        viewNavigators.view(origin, AiChatHubView.class)
                .navigate();
    }

    // a workaround for AiChatView.syncUrl of AI Tools 3.1.0, which copies the query the browser shows into the address
    // of a chat while the browser still shows the page the chat was opened from. The browser answers in turn, so this
    // request comes back after the one of the chat and puts back the address the router gave the chat, unless the
    // person has left the chat meanwhile. The method can go once the add-on takes the query from the server's location.
    private void showChatAddress() {
        UI ui = UI.getCurrent();
        String chat = routeSupport.getActiveViewLocation(ui).getPathWithQueryParameters();

        routeSupport.fetchCurrentLocation(ui, ignored -> {
            Location shown = routeSupport.getActiveViewLocation(ui);

            if (chat.equals(shown.getPathWithQueryParameters())) {
                routeSupport.setLocation(ui, shown);
            }
        });
    }
}
