package com.company.dms.test_support;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * A model that answers with the replies a test scheduled, one per call, and keeps the prompts it got.
 * A call without a scheduled reply fails, so a test notices a call it did not expect. A stream gives the reply
 * in one piece. A reply can also call a tool, as a real model does.
 */
public class StubChatModel implements ChatModel {

    private final Deque<Supplier<AssistantMessage>> replies = new ConcurrentLinkedDeque<>();
    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

    /**
     * Schedules the content of the next reply.
     */
    public void willReply(String content) {
        willReply(() -> content);
    }

    /**
     * Schedules a reply made when the model is called, so that a test can see what the application shows while
     * the model answers.
     */
    public void willReply(Supplier<String> content) {
        replies.add(() -> new AssistantMessage(content.get()));
    }

    /**
     * Schedules a reply that calls a tool.
     *
     * @param arguments the arguments of the call, as JSON
     */
    public void willCallTool(String tool, String arguments) {
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("call-" + replies.size(), "function", tool,
                arguments);

        replies.add(() -> AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(call))
                .build());
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

    // like a real model, so that a chat client passes its tools on
    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        Supplier<AssistantMessage> reply = replies.poll();

        if (reply == null) {
            throw new IllegalStateException("The stub model was called without a scheduled reply");
        }

        return new ChatResponse(List.of(new Generation(reply.get())));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> Flux.just(call(prompt)));
    }
}
