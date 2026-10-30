package com.company.docai.view.document;

import com.company.docai.ai.assistant.DocumentAssistant;
import com.company.docai.model.document.Document;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.ai.provider.SpringAILLMProvider;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import io.jmix.aichat.component.chat.AiChat;
import io.jmix.aichat.component.chat.GenerationFailedEvent;
import io.jmix.aichat.data.SimpleAiChatMessage;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.fragment.Fragment;
import io.jmix.flowui.fragment.FragmentDescriptor;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;

/**
 * The assistant tab of the document card: a chat with the AI about the document. The conversation lives while
 * the card is open and is not stored.
 */
@FragmentDescriptor("document-assistant-fragment.xml")
public class DocumentAssistantFragment extends Fragment<VerticalLayout> {

    private static final Logger log = LoggerFactory.getLogger(DocumentAssistantFragment.class);

    @ViewComponent
    private AiChat<SimpleAiChatMessage> chat;
    @ViewComponent
    private MessageBundle messageBundle;

    @Autowired
    private DocumentAssistant documentAssistant;
    @Autowired
    private Notifications notifications;

    /**
     * Starts the conversation about the document.
     */
    public void show(Document document) {
        SpringAILLMProvider provider = new SpringAILLMProvider(documentAssistant.chatClient(document,
                status -> chat.getThinkingStatusPublisher().start(status)::complete));

        // the chat drops an answer without text silently, and the person would wait for nothing
        chat.setLlmProvider(request -> provider.stream(request)
                .switchIfEmpty(Flux.error(new IllegalStateException("The model gave an empty answer"))));
    }

    @Subscribe("summaryButton")
    public void onSummaryButtonClick(final ClickEvent<JmixButton> event) {
        chat.prompt(messageBundle.getMessage("summaryButton.prompt"));
    }

    @Subscribe("datesAndAmountsButton")
    public void onDatesAndAmountsButtonClick(final ClickEvent<JmixButton> event) {
        chat.prompt(messageBundle.getMessage("datesAndAmountsButton.prompt"));
    }

    @Subscribe("chat")
    public void onChatGenerationFailed(final GenerationFailedEvent<?> event) {
        log.warn("The assistant did not answer: {}", event.getError().toString());

        notifications.create(messageBundle.getMessage("assistantFailed"))
                .withType(Notifications.Type.ERROR)
                .show();
    }
}
