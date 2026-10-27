package com.company.dms.ai.assistant;

import com.company.dms.DMSApplication;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.access.AccessLevel;
import com.company.dms.model.document.Document;
import com.company.dms.model.folder.Folder;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import com.company.dms.test_support.StubAiConfiguration;
import com.company.dms.test_support.StubChatModel;
import com.company.dms.test_support.TestAccess;
import com.company.dms.test_support.TestAccess.Grant;
import com.company.dms.test_support.TestFiles;
import io.jmix.aitools.tool.AiToolStatusPublisher;
import io.jmix.aitools.tool.AiToolStatusUpdate;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tools of the document assistant, called the way the model calls them: in the card, with the person who asks
 * and the feed of the chat in the tool context, on a thread that runs as somebody else; in the chat of the menu, as
 * the person signed in, with the status feed of the AI Tools add-on.
 */
@SpringBootTest(classes = {DMSApplication.class, StubAiConfiguration.class})
@ExtendWith(AuthenticatedAsAdmin.class)
class DocumentAssistantToolsTest {

    private static final String EMPLOYEE = "peter";
    private static final String INVOICE = "Invoice 1187 of 1 November 2026 from Harbour Logistics LLC";

    @Autowired
    private DocumentAssistantTools tools;

    @Autowired
    private StubChatModel model;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    private final List<String> started = new CopyOnWriteArrayList<>();
    private final AtomicInteger finished = new AtomicInteger();

    @BeforeEach
    void setUp() {
        model.reset();

        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("ai-assistant-tools-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);
    }

    @AfterEach
    void deleteSandbox() {
        dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete);
    }

    @Test
    void theTextIsReadForThePersonWhoAsks() {
        Document invoice = upload("invoice.txt", INVOICE.getBytes(StandardCharsets.UTF_8));

        String text = tools.readDocumentText("Reading the invoice", invoice.getId().toString(), asTheEmployee());

        assertThat(text).contains(INVOICE);
    }

    @Test
    void aDocumentHiddenFromThePersonIsRefusedThoughTheThreadRunsAsAnAdministrator() {
        Document invoice = TestAccess.giveOwnAccess(dataManager,
                upload("invoice.txt", INVOICE.getBytes(StandardCharsets.UTF_8)), Grant.user("maria", AccessLevel.VIEW));

        String text = tools.readDocumentText("Reading the invoice", invoice.getId().toString(), asTheEmployee());

        assertThat(text).doesNotContain(INVOICE).contains("not available");
    }

    @Test
    void theStatusOfTheModelShowsWhileTheToolWorks() {
        Document invoice = upload("invoice.txt", INVOICE.getBytes(StandardCharsets.UTF_8));

        tools.readDocumentText("Reading the invoice", invoice.getId().toString(), asTheEmployee());

        assertThat(started).containsExactly("Reading the invoice");
        assertThat(finished).hasValue(1);
    }

    @Test
    void aCallWithoutAStatusShowsNoneAndReadsAllTheSame() {
        Document invoice = upload("invoice.txt", INVOICE.getBytes(StandardCharsets.UTF_8));

        String text = tools.readDocumentText(" ", invoice.getId().toString(), asTheEmployee());

        assertThat(text).contains(INVOICE);
        assertThat(started).isEmpty();
    }

    @Test
    void aProposalOfTheTypeSavesNothing() {
        Document invoice = upload("invoice.txt", INVOICE.getBytes(StandardCharsets.UTF_8));
        model.willReply("""
                {"type": "Invoice",
                 "fields": [{"code": "supplier", "value": "Harbour Logistics LLC"}],
                 "documentDate": "2026-11-01"}""");

        String proposal = tools.proposeTypeAndFields("Classifying the invoice", invoice.getId().toString(),
                asTheEmployee());

        assertThat(proposal).contains("Invoice", "supplier = Harbour Logistics LLC", "2026-11-01");
        assertThat(dataManager.load(Id.of(invoice)).one().getCategory()).isNull();
    }

    @Test
    void aDocumentWithoutTextSaysSo() {
        Document scan = upload("scan.png", png());

        assertThat(tools.readDocumentText("Reading the scan", scan.getId().toString(), asTheEmployee()))
                .contains("no text to read");
        assertThat(model.prompts()).isEmpty();
    }

    @Test
    void anIdThatIsNoDocumentIsRefused() {
        assertThat(tools.readDocumentText("Reading", "not-an-id", asTheEmployee())).contains("not available");
        assertThat(tools.readDocumentText("Reading", UUID.randomUUID().toString(), asTheEmployee()))
                .contains("not available");
    }

    @Test
    void inTheChatOfTheMenuTheToolsReadAsTheSignedInPersonAndShowTheStatusThere() {
        Document invoice = upload("invoice.txt", INVOICE.getBytes(StandardCharsets.UTF_8));
        Document hidden = TestAccess.giveOwnAccess(dataManager,
                upload("hidden.txt", INVOICE.getBytes(StandardCharsets.UTF_8)), Grant.user("maria", AccessLevel.VIEW));
        Consumer<AiToolStatusUpdate> feed = update -> started.add(update.getMessage());
        ToolContext menuChat = new ToolContext(Map.of(AiToolStatusPublisher.STATUS_UPDATE_CALLBACK, feed));

        String text = systemAuthenticator.withUser(EMPLOYEE,
                () -> tools.readDocumentText("Reading the invoice", invoice.getId().toString(), menuChat));

        String refused = systemAuthenticator.withUser(EMPLOYEE,
                () -> tools.readDocumentText("Reading the other invoice", hidden.getId().toString(), menuChat));

        assertThat(text).contains(INVOICE);
        assertThat(refused).doesNotContain(INVOICE).contains("not available");
        assertThat(started).containsExactly("Reading the invoice", "Reading the other invoice");
    }

    private ToolContext asTheEmployee() {
        ToolStatusFeed statuses = status -> {
            started.add(status);
            return finished::incrementAndGet;
        };

        return new ToolContext(Map.of(DocumentAssistantTools.USERNAME, EMPLOYEE,
                DocumentAssistantTools.STATUSES, statuses));
    }

    private Document upload(String fileName, byte[] content) {
        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, fileName, content)))
                .created()
                .getFirst();
    }

    private static byte[] png() {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "png", bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
