package com.company.dms.view.document;

import com.company.dms.DMSApplication;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.access.AccessLevel;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentRevision;
import com.company.dms.model.document.DocumentStatus;
import com.company.dms.model.folder.Folder;
import com.company.dms.test_support.EmployeeUiTestAuthenticator;
import com.company.dms.test_support.StubAiConfiguration;
import com.company.dms.test_support.StubChatModel;
import com.company.dms.test_support.TestAccess;
import com.company.dms.test_support.TestAccess.Grant;
import com.company.dms.test_support.TestFiles;
import com.company.dms.test_support.TestUploads;
import com.company.dms.view.main.MainView;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.server.VaadinSession;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.dynattr.model.Category;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.tabsheet.JmixTabSheet;
import io.jmix.flowui.kit.action.Action;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.dialog.DialogInfo;
import io.jmix.flowui.testassist.notification.NotificationInfo;
import io.jmix.flowui.testassist.notification.OpenedNotifications;
import io.jmix.flowui.upload.TemporaryStorage;
import io.jmix.flowui.view.DialogWindow;
import io.jmix.flowui.view.ViewControllerUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The "Fill with AI" button of the document card, used by an employee, with the model replaced by a stub.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DMSApplication.class, FlowuiTestAssistConfiguration.class,
        StubAiConfiguration.class})
@TestPropertySource(properties = "spring.ai.openai.api-key=test-key")
class FillWithAiActionTest {

    private static final String ADMIN = "admin";
    private static final String EMPLOYEE = "peter";
    private static final String INVOICE = "Invoice 1187 of 1 November 2026 from Harbour Logistics LLC";
    private static final String INVOICE_PROPOSAL = """
            {"type": "Invoice",
             "fields": [{"code": "supplier", "value": "Harbour Logistics LLC"},
                        {"code": "invoiceNumber", "value": "1187"}],
             "documentDate": "2026-11-01"}""";

    @Autowired
    private DialogWindows dialogWindows;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private TemporaryStorage temporaryStorage;

    @Autowired
    private OpenedNotifications openedNotifications;

    @Autowired
    private Messages messages;

    @Autowired
    private StubChatModel model;

    @Autowired
    private OpenAiCommonProperties openAiCommonProperties;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void setUp() {
        model.reset();
        sandbox = systemAuthenticator.withUser(ADMIN, () -> {
            Folder folder = dataManager.create(Folder.class);
            folder.setParent(folderService.getRoot());
            folder.setName("fill-with-ai-test-" + UUID.randomUUID());
            return folderService.save(folder);
        });
    }

    @AfterEach
    void deleteSandbox() {
        systemAuthenticator.runWithUser(ADMIN, () -> dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete));
    }

    @Test
    void anEmployeeWhoMayEditTheDocumentCanFillItWithAi() {
        JmixButton button = button(openCard(upload("invoice.txt", INVOICE.getBytes(StandardCharsets.UTF_8))));

        assertThat(button.isVisible()).isTrue();
        assertThat(button.isEnabled()).isTrue();
    }

    @Test
    void anEmployeeWhoOnlyViewsTheDocumentDoesNotSeeTheButton() {
        Document document = upload("invoice.txt", INVOICE.getBytes(StandardCharsets.UTF_8));
        systemAuthenticator.runWithUser(ADMIN, () ->
                TestAccess.giveOwnAccess(dataManager, document, Grant.user(EMPLOYEE, AccessLevel.VIEW)));

        assertThat(button(openCard(document)).isVisible()).isFalse();
    }

    @Test
    void anArchivedDocumentCannotBeFilled() {
        Document document = upload("invoice.txt", INVOICE.getBytes(StandardCharsets.UTF_8));
        systemAuthenticator.runWithUser(ADMIN, () -> {
            documentService.changeStatus(List.of(document), DocumentStatus.ACTIVE);
            documentService.changeStatus(List.of(document), DocumentStatus.ARCHIVED);
        });

        JmixButton button = button(openCard(stored(document)));

        assertThat(button.isVisible()).isTrue();
        assertThat(button.isEnabled()).isFalse();
    }

    @Test
    void aNewDocumentCanBeFilledOnceItHasAFile() {
        DocumentDetailView card = openNewCard();
        JmixButton button = button(card);

        assertThat(button.isVisible()).isTrue();
        assertThat(button.isEnabled()).isFalse();

        TestUploads.succeed(UiTestUtils.getComponent(card, "newVersionUpload"), temporaryStorage, "invoice.txt",
                INVOICE);

        assertThat(button.isEnabled()).isTrue();
    }

    @Test
    void theAiFillsTheCardFromTheFileAndNothingIsSavedBeforeOk() {
        Document document = upload("invoice.txt", INVOICE.getBytes(StandardCharsets.UTF_8));
        DocumentDetailView card = openCard(document);
        model.willReply(INVOICE_PROPOSAL);

        fill(card);

        assertThat(lastNotification().getType()).isEqualTo(Notifications.Type.SUCCESS);
        assertThat(card.getEditedEntity().getCategory()).isEqualTo(type("Invoice"));
        assertThat(EntityValues.<Object>getValue(card.getEditedEntity(), "+supplier"))
                .isEqualTo("Harbour Logistics LLC");
        assertThat(card.getEditedEntity().getDocumentDate()).hasToString("2026-11-01");
        assertThat(this.<JmixTabSheet>component(card, "cardTabs").getSelectedTab())
                .isSameAs(component(card, "propertiesTab"));
        assertThat(model.prompts().getFirst().getUserMessage().getText()).contains(INVOICE);
        assertThat(stored(document).getCategory()).isNull();
    }

    @Test
    void theAiReadsAFileUploadedIntoTheCardBeforeItIsSaved() {
        DocumentDetailView card = openCard(upload("invoice.txt", "The first version".getBytes(StandardCharsets.UTF_8)));
        TestUploads.succeed(UiTestUtils.getComponent(card, "newVersionUpload"), temporaryStorage, "invoice-v2.txt",
                INVOICE);
        model.willReply(INVOICE_PROPOSAL);

        fill(card);

        assertThat(model.prompts().getFirst().getUserMessage().getText())
                .contains(INVOICE)
                .doesNotContain("The first version");
    }

    @Test
    void theAiReadsTheVersionTheCardIsAboutToRestore() {
        Document document = upload("invoice.txt", INVOICE.getBytes(StandardCharsets.UTF_8));
        systemAuthenticator.runWithUser(ADMIN, () -> documentService.uploadRevision(document,
                TestFiles.file(tempDir, "invoice-v2.txt", "The second version")));
        DocumentDetailView card = openCard(document);
        DataGrid<DocumentRevision> revisionsGrid = component(card, "revisionsGrid");
        CollectionContainer<DocumentRevision> revisions = ViewControllerUtils.getViewData(card)
                .getContainer("revisionsDc");

        revisionsGrid.select(revisions.getItems().stream()
                .filter(revision -> revision.getNumber() == 1)
                .findFirst()
                .orElseThrow());

        Action restore = revisionsGrid.getAction("restoreRevisionAction");
        assertThat(restore).isNotNull();
        restore.actionPerform(revisionsGrid);
        model.willReply(INVOICE_PROPOSAL);

        fill(card);

        assertThat(model.prompts().getFirst().getUserMessage().getText())
                .contains(INVOICE)
                .doesNotContain("The second version");
    }

    @Test
    void aTypedDocumentAsksBeforeTheAiReplacesItsData() {
        Document document = upload("invoice.txt", INVOICE.getBytes(StandardCharsets.UTF_8));
        DocumentDetailView card = openCard(document);
        card.getEditedEntity().setCategory(type("Contract"));

        button(card).click();

        assertThat(model.prompts()).isEmpty();
        model.willReply(INVOICE_PROPOSAL);
        dialogButton(DialogAction.Type.YES).click();
        waitForNotification();

        assertThat(card.getEditedEntity().getCategory()).isEqualTo(type("Invoice"));
    }

    @Test
    void noKeepsTheDataOfATypedDocument() {
        DocumentDetailView card = openCard(upload("invoice.txt", INVOICE.getBytes(StandardCharsets.UTF_8)));
        card.getEditedEntity().setCategory(type("Contract"));

        button(card).click();
        dialogButton(DialogAction.Type.NO).click();

        assertThat(model.prompts()).isEmpty();
        assertThat(card.getEditedEntity().getCategory()).isEqualTo(type("Contract"));
    }

    @Test
    void aFileWithoutTextIsReported() {
        DocumentDetailView card = openCard(upload("scan.png", png()));

        fill(card);

        assertThat(lastNotification().getType()).isEqualTo(Notifications.Type.WARNING);
        assertThat(lastNotification().getText())
                .isEqualTo(messages.getMessage(FillWithAiAction.class, "fillWithAiAction.noText"));
        assertThat(model.prompts()).isEmpty();
        assertThat(card.getEditedEntity().getCategory()).isNull();
    }

    @Test
    void aDocumentTheAiCannotTellLeavesTheCardAsItWas() {
        DocumentDetailView card = openCard(upload("note.txt", "Call me back".getBytes(StandardCharsets.UTF_8)));
        model.willReply("{\"type\": \"UNKNOWN\", \"fields\": [], \"documentDate\": null}");

        fill(card);

        assertThat(lastNotification().getText())
                .isEqualTo(messages.getMessage(FillWithAiAction.class, "fillWithAiAction.unknownType"));
        assertThat(card.getEditedEntity().getCategory()).isNull();
    }

    @Test
    void aValueTheAiCouldNotFitIsReportedAndTheRestIsFilled() {
        DocumentDetailView card = openCard(upload("invoice.txt", INVOICE.getBytes(StandardCharsets.UTF_8)));
        model.willReply("""
                {"type": "Invoice",
                 "fields": [{"code": "supplier", "value": "Harbour Logistics LLC"},
                            {"code": "invoiceAmount", "value": "about a million"}],
                 "documentDate": "2026-11-01"}""");

        fill(card);

        assertThat(openedNotifications.getNotifications())
                .extracting(NotificationInfo::getType)
                .containsExactly(Notifications.Type.SUCCESS, Notifications.Type.WARNING);
        assertThat(lastNotification().getText()).contains("about a million");
        assertThat(EntityValues.<Object>getValue(card.getEditedEntity(), "+supplier"))
                .isEqualTo("Harbour Logistics LLC");
    }

    @Test
    void anAnswerThatIsNotAProposalIsReported() {
        DocumentDetailView card = openCard(upload("invoice.txt", INVOICE.getBytes(StandardCharsets.UTF_8)));
        model.willReply("Sorry, I cannot classify this document.");

        fill(card);

        assertThat(lastNotification().getType()).isEqualTo(Notifications.Type.ERROR);
        assertThat(card.getEditedEntity().getCategory()).isNull();
    }

    @Test
    void aModelThatFailsIsReported() {
        DocumentDetailView card = openCard(upload("invoice.txt", INVOICE.getBytes(StandardCharsets.UTF_8)));

        fill(card);

        assertThat(lastNotification().getType()).isEqualTo(Notifications.Type.ERROR);
        assertThat(lastNotification().getText())
                .isEqualTo(messages.getMessage(FillWithAiAction.class, "fillWithAiAction.failed"));
        assertThat(card.getEditedEntity().getCategory()).isNull();
    }

    @Test
    void aCallOfTheModelEndsBeforeTheCardStopsWaiting() {
        Duration call = openAiCommonProperties.getTimeout()
                .multipliedBy(openAiCommonProperties.getMaxRetries() + 1);

        assertThat(call).isLessThan(Duration.ofSeconds(FillWithAiAction.TIMEOUT_SECONDS));
    }

    @Test
    void aCardTakenOffThePageWhileTheAiReadsIgnoresTheLateAnswer() {
        DocumentDetailView card = openCard(upload("invoice.txt", INVOICE.getBytes(StandardCharsets.UTF_8)));
        VaadinSession session = VaadinSession.getCurrent();
        model.willReply(INVOICE_PROPOSAL);
        session.getService().runPendingAccessTasks(session);
        openedNotifications.closeOpenedNotifications();

        button(card).click();
        await().pollInSameThread()
                .atMost(Duration.ofSeconds(20))
                .until(() -> !session.getPendingAccessQueue().isEmpty());
        // what browser navigation does to an open card
        card.getElement().removeFromParent();
        session.getService().runPendingAccessTasks(session);

        assertThat(openedNotifications.getNotifications()).isEmpty();
        assertThat(card.getEditedEntity().getCategory()).isNull();
    }

    @Test
    void anActionTheCardHidesStaysHidden() {
        DocumentDetailView card = openNewCard();
        JmixButton button = button(card);
        Action action = button.getAction();
        assertThat(action).isNotNull();

        action.setVisible(false);
        TestUploads.succeed(UiTestUtils.getComponent(card, "newVersionUpload"), temporaryStorage, "invoice.txt",
                INVOICE);

        assertThat(button.isVisible()).isFalse();
    }

    private void fill(DocumentDetailView card) {
        openedNotifications.closeOpenedNotifications();
        button(card).click();
        waitForNotification();
    }

    // the result comes back through UI.access, which a UI test runs only when asked
    private void waitForNotification() {
        VaadinSession session = VaadinSession.getCurrent();

        await().pollInSameThread()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> {
                    session.getService().runPendingAccessTasks(session);
                    return !openedNotifications.getNotifications().isEmpty();
                });
    }

    private NotificationInfo lastNotification() {
        return openedNotifications.getLastNotification();
    }

    private Button dialogButton(DialogAction.Type type) {
        DialogInfo dialog = UiTestUtils.getLastOpenedDialog();
        assertThat(dialog).isNotNull();

        return dialog.getButtons().stream()
                .filter(button -> button.getId().equals(Optional.of(type.getId())))
                .findFirst()
                .orElseThrow();
    }

    private Document upload(String fileName, byte[] content) {
        return systemAuthenticator.withUser(ADMIN, () ->
                documentService.upload(sandbox, List.of(TestFiles.file(tempDir, fileName, content)))
                        .created()
                        .getFirst());
    }

    private Document stored(Document document) {
        return systemAuthenticator.withUser(ADMIN, () -> dataManager.load(Document.class)
                .id(document.getId())
                .one());
    }

    private Category type(String name) {
        return systemAuthenticator.withUser(ADMIN, () -> dataManager.load(Category.class)
                .query("select c from dynat_Category c where c.name = :name and c.entityType = 'Document'")
                .parameter("name", name)
                .one());
    }

    private DocumentDetailView openCard(Document document) {
        DialogWindow<DocumentDetailView> window = dialogWindows.detail(UiTestUtils.getCurrentView(), Document.class)
                .withViewClass(DocumentDetailView.class)
                .editEntity(document)
                .build();

        window.open();
        return window.getView();
    }

    private DocumentDetailView openNewCard() {
        DialogWindow<DocumentDetailView> window = dialogWindows.detail(UiTestUtils.getCurrentView(), Document.class)
                .withViewClass(DocumentDetailView.class)
                .newEntity()
                .withInitializer(document -> document.setFolder(sandbox))
                .build();

        window.open();
        return window.getView();
    }

    private JmixButton button(DocumentDetailView card) {
        return component(card, "fillWithAiButton");
    }

    private <T> T component(DocumentDetailView card, String id) {
        return UiTestUtils.getComponent(card, id);
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
