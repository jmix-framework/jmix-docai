package com.company.docai.view.search;

import com.company.docai.ai.AiSettings;
import com.google.common.base.Strings;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.QueryParameters;
import io.jmix.aitoolsflowui.model.AiConversation;
import io.jmix.aitoolsflowui.service.AiConversationService;
import io.jmix.aitoolsflowui.view.chat.AiChatView;
import io.jmix.aitoolsflowui.view.chathub.AiChatHubFragment;
import io.jmix.core.Messages;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.app.main.StandardMainView;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.view.View;
import io.jmix.flowui.view.navigation.RouteSupport;
import io.jmix.flowui.view.navigation.ViewClassNavigator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Opens what a search leads to: the search page by words or by meaning, the page of the documents with a tag, and the
 * chat of the assistant with a question. The header of the main view, its search palette and the tag badges go
 * through it, each from the view it names as the origin of the navigation.
 */
@Component
public class SearchNavigation {

    private static final Logger log = LoggerFactory.getLogger(SearchNavigation.class);

    private final ViewNavigators viewNavigators;
    private final RecentQueries recentQueries;
    private final AiSettings aiSettings;
    private final AiConversationService conversationService;
    private final RouteSupport routeSupport;
    private final Notifications notifications;
    private final Messages messages;

    public SearchNavigation(ViewNavigators viewNavigators, RecentQueries recentQueries, AiSettings aiSettings,
                            AiConversationService conversationService, RouteSupport routeSupport,
                            Notifications notifications, Messages messages) {
        this.viewNavigators = viewNavigators;
        this.recentQueries = recentQueries;
        this.aiSettings = aiSettings;
        this.conversationService = conversationService;
        this.routeSupport = routeSupport;
        this.notifications = notifications;
        this.messages = messages;
    }

    /**
     * Opens the search page with the text without the spaces around it; an empty text opens nothing. The search goes
     * by meaning only while the AI is on, and it joins the recent searches in the mode it opens in. From the main view
     * a search page that is open shows the new search; from a search page a new one opens.
     */
    public void openSearch(View<?> origin, @Nullable String text, boolean ai) {
        String query = Strings.nullToEmpty(text).strip();

        if (query.isEmpty()) {
            return;
        }

        boolean aiQuery = ai && aiSettings.isAvailable();
        recentQueries.add(query, aiQuery);

        QueryParameters parameters = QueryParameters.of(DocumentSearchView.QUERY_PARAMETER, query);

        if (aiQuery) {
            parameters = parameters.merging(DocumentSearchView.MODE_PARAMETER, DocumentSearchView.AI_MODE);
        }

        viewNavigators.view(origin, DocumentSearchView.class)
                .withQueryParameters(parameters)
                .navigate();
    }

    /**
     * Opens the page of the documents with the tag. From the main view a search page that is open shows the tag; from
     * a search page a new one opens.
     */
    public void openTag(View<?> origin, String name) {
        viewNavigators.view(origin, DocumentSearchView.class)
                .withQueryParameters(QueryParameters.of(DocumentSearchView.TAG_PARAMETER, name))
                .navigate();
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

    // a workaround for AiChatView.syncUrl of AI Tools 3.1.0, which copies the query the browser shows into the address
    // of a new chat while the browser still shows the page the question came from. The browser answers in turn, so this
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
