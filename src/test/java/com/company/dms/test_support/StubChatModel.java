package com.company.dms.test_support;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A model that answers with the replies a test scheduled, one per call, and keeps the prompts it got.
 * A call without a scheduled reply fails, so a test notices a call it did not expect.
 */
public class StubChatModel implements ChatModel {

    private final Deque<String> replies = new ConcurrentLinkedDeque<>();
    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

    /**
     * Schedules the content of the next reply.
     */
    public void willReply(String content) {
        replies.add(content);
    }

    /**
     * Returns the prompts the model got, in the order of the calls.
     */
    public List<Prompt> prompts() {
        return prompts;
    }

    /**
     * Forgets the scheduled replies and the prompts.
     */
    public void reset() {
        replies.clear();
        prompts.clear();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        String reply = replies.poll();

        if (reply == null) {
            throw new IllegalStateException("The stub model was called without a scheduled reply");
        }

        return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
    }
}
