package com.company.docai.ai.assistant;

/**
 * The feed in which an assistant tool tells the person what it does.
 */
public interface ToolStatusFeed {

    /**
     * Shows a status and returns what marks it done.
     */
    Runnable start(String status);
}
