package com.company.docai.ai.assistant;

/**
 * The feed in which an assistant tool tells the person what it does: the chat that called the tool shows each
 * status until the tool finishes.
 */
public interface ToolStatusFeed {

    /**
     * Shows a status and returns what marks it done.
     *
     * @param status what the tool does, in the words of the model
     */
    Runnable start(String status);
}
