package com.company.docai.view.assistant;

import com.company.docai.view.tour.Tour;
import com.company.docai.view.tour.TourChapter;
import io.jmix.aitools.tool.AiToolStatusUpdate;
import io.jmix.aitoolsflowui.model.AiChatMessage;
import io.jmix.aitoolsflowui.model.AiConversation;
import io.jmix.aitoolsflowui.view.chat.support.AssistantResponseTaskCoordinator;
import io.jmix.flowui.view.View;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Runs the menu assistant's answers as AI Tools does and tells the tour that one arrived. The add-on has no event
 * for an answer, so its coordinator is replaced; recheck it with every Jmix update.
 */
@Primary
@Component
public class AssistantResponseCoordinator extends AssistantResponseTaskCoordinator {

    private final Tour tour;

    public AssistantResponseCoordinator(Tour tour) {
        this.tour = tour;
    }

    @Override
    public void run(View<?> owner, AiConversation conversation, AiChatMessage savedUserMessage,
                    Consumer<AiToolStatusUpdate> progressHandler, Consumer<AiChatMessage> doneHandler,
                    Runnable failureHandler) {
        super.run(owner, conversation, savedUserMessage, progressHandler, answer -> {
            doneHandler.accept(answer);

            // the add-on passes null when it finds no saved answer
            if (answer != null) {
                tour.done(TourChapter.AI_ASSISTANT);
            }
        }, failureHandler);
    }
}
