package com.company.docai.app.service.dashboard;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An own chat with the AI assistant.
 *
 * @param id         the id of the conversation
 * @param title      the title of the conversation
 * @param time       when its last message came
 * @param discussion whether it discusses the documents chosen with Discuss with AI
 */
public record RecentChat(UUID id, String title, OffsetDateTime time, boolean discussion) {
}
