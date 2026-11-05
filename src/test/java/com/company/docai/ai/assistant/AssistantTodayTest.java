package com.company.docai.ai.assistant;

import com.company.docai.DocAiApplication;
import com.company.docai.model.document.Document;
import com.company.docai.model.user.User;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import com.company.docai.test_support.StubAiConfiguration;
import com.company.docai.test_support.StubChatModel;
import io.jmix.core.DataManager;
import io.jmix.core.TimeSource;
import io.jmix.core.security.CurrentAuthentication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

/**
 * The assistants know today's date in the time zone of the person who asks.
 */
@SpringBootTest(classes = {DocAiApplication.class, StubAiConfiguration.class})
@ExtendWith(AuthenticatedAsAdmin.class)
class AssistantTodayTest {

    private static final String RELATIVE_DATES_RULE =
            "Count relative dates, such as this month, soon or next week, from this date.";

    @Autowired
    private AssistantChatPromptProvider promptProvider;

    @Autowired
    private DocumentAssistant documentAssistant;

    @Autowired
    private StubChatModel model;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private CurrentAuthentication currentAuthentication;

    @MockitoSpyBean
    private TimeSource timeSource;

    @BeforeEach
    void setUp() {
        model.reset();
        // 22:30 in UTC on Thursday: already Friday in Dubai, still Thursday in Los Angeles
        doReturn(ZonedDateTime.of(2026, 11, 5, 22, 30, 0, 0, ZoneOffset.UTC)).when(timeSource).now();
    }

    @Test
    void theMenuPromptHasTodayInThePersonsTimeZone() throws IOException {
        setTimeZone("America/Los_Angeles");

        assertThat(menuPrompt()).contains("Today is 2026-11-05 (Thursday), time zone America/Los_Angeles.",
                RELATIVE_DATES_RULE);
    }

    @Test
    void aPersonInAnotherTimeZoneSeesThatZonesDateNearMidnight() throws IOException {
        setTimeZone("Asia/Dubai");

        assertThat(menuPrompt()).contains("Today is 2026-11-06 (Friday), time zone Asia/Dubai.");
    }

    @Test
    void theCardPromptHasTheSameLine() {
        setTimeZone("Asia/Dubai");
        Document document = dataManager.create(Document.class);
        document.setName("lease-112.txt");
        model.willReply("ok");

        documentAssistant.chatClient(document, status -> () -> {
                })
                .prompt()
                .user("When does it expire?")
                .call()
                .content();

        assertThat(model.prompts().getFirst().getSystemMessage().getText())
                .contains("Today is 2026-11-06 (Friday), time zone Asia/Dubai.", RELATIVE_DATES_RULE);
    }

    private String menuPrompt() throws IOException {
        return promptProvider.getResource().getContentAsString(StandardCharsets.UTF_8);
    }

    private void setTimeZone(String zoneId) {
        ((User) currentAuthentication.getUser()).setTimeZoneId(zoneId);
        assertThat(currentAuthentication.getTimeZone().getID()).isEqualTo(zoneId);
    }
}
