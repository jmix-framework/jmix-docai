package com.company.dms.ai.assistant;

import com.company.dms.ai.AiSettings;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.document.Document;
import com.company.dms.model.folder.Folder;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import com.company.dms.test_support.TestFiles;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAI model answers about the lease agreement next to the classification samples, reading it through
 * the tools of the assistant. Runs only with {@code ./gradlew aiTest} and the key in {@code OPENAI_API_KEY}.
 */
@Tag("ai")
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class LiveAssistantTest {

    @Autowired
    private DocumentAssistant documentAssistant;

    @Autowired
    private AiSettings aiSettings;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    private final List<String> statuses = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        assertThat(aiSettings.isAvailable()).isTrue();

        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("live-assistant-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);
    }

    @AfterEach
    void deleteSandbox() {
        dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete);
    }

    @Test
    void theAssistantReadsTheLeaseAndTellsItsTermAndRent() {
        ChatClient chatClient = documentAssistant.chatClient(upload(leaseAgreement()), status -> {
            statuses.add(status);
            return () -> {
            };
        });

        String answer = ask(chatClient, "Until when is the agreement valid, and what is the rent?");

        assertThat(answer).contains("2027").containsAnyOf("185,000", "185 000", "185000");
        assertThat(statuses).isNotEmpty().allSatisfy(status -> assertThat(status).isNotBlank());
    }

    @Test
    void aFollowUpQuestionIsAnsweredFromTheTextReadBefore() {
        ChatClient chatClient = documentAssistant.chatClient(upload(leaseAgreement()),
                status -> () -> {
                });
        ask(chatClient, "Until when is the agreement valid?");

        String answer = ask(chatClient, "And who is the tenant, and how large is the warehouse?");

        assertThat(answer).contains("Harbour Logistics", "450");
    }

    @Test
    void aQuestionAboutThePartLeftUnreadIsAnsweredAsSuch() {
        String lease = "Lease agreement 7 of 1 March 2026 between Harbour Logistics LLC and North Storage LLC.\n"
                + "The tenant keeps the warehouse clean and in good repair.\n".repeat(300)
                + "A late payment of the rent costs a penalty of 0.1 percent of the rent for each day of delay.";
        ChatClient chatClient = documentAssistant.chatClient(upload(lease.getBytes(StandardCharsets.UTF_8)),
                status -> () -> {
                });

        String answer = ask(chatClient, "What penalty does a late payment of the rent cost?");

        assertThat(answer)
                .doesNotContain("0.1")
                .containsAnyOf("first", "beginning", "part of the", "12000", "12,000", "12 000");
    }

    // the chat of the card streams the answer
    private static @Nullable String ask(ChatClient chatClient, String question) {
        return chatClient.prompt()
                .user(question)
                .stream()
                .content()
                .collect(Collectors.joining())
                .block();
    }

    // a neutral name, so that the model reads the text
    private Document upload(byte[] content) {
        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, "scan-" + UUID.randomUUID() + ".txt",
                        content)))
                .created()
                .getFirst();
    }

    private static byte[] leaseAgreement() {
        try (InputStream stream = LiveAssistantTest.class.getResourceAsStream(
                "/com/company/dms/ai/classification/lease-agreement-112.txt")) {
            return Objects.requireNonNull(stream).readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
