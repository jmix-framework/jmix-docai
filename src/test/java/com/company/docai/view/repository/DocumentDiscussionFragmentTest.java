package com.company.docai.view.repository;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.StubAiConfiguration;
import com.company.docai.test_support.StubChatModel;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestGrids;
import com.company.docai.view.document.DocumentDetailView;
import com.company.docai.view.main.MainView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dnd.DropTarget;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.server.VaadinSession;
import io.jmix.aichat.component.chat.AiChat;
import io.jmix.aichat.component.messagelist.GenerationState;
import io.jmix.aichat.component.messagelist.ThinkingStatusItem;
import io.jmix.aichat.data.SimpleAiChatMessage;
import io.jmix.aitoolsflowui.model.AiConversation;
import io.jmix.aitoolsflowui.service.AiConversationService;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.grid.TreeDataGrid;
import io.jmix.flowui.component.sidedialog.SideDialog;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.kit.action.Action;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.settings.UserSettingsCache;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.notification.NotificationInfo;
import io.jmix.flowui.testassist.notification.OpenedNotifications;
import io.jmix.flowui.view.View;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

/**
 * The discussion of documents with the assistant in the window of the repository, used by an employee, with the
 * model replaced by a stub.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class,
        StubAiConfiguration.class})
@TestPropertySource(properties = "spring.ai.openai.api-key=test-key")
class DocumentDiscussionFragmentTest {

    private static final String ADMIN = "admin";
    private static final String QUESTION = "When is the rent paid?";

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private AiConversationService conversationService;

    @Autowired
    private OpenedNotifications openedNotifications;

    @Autowired
    private UserSettingsCache userSettingsCache;

    @Autowired
    private Messages messages;

    @Autowired
    private StubChatModel model;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void setUp() {
        model.reset();
        sandbox = systemAuthenticator.withUser(ADMIN, () -> {
            Folder folder = dataManager.create(Folder.class);
            folder.setParent(folderService.getRoot());
            folder.setName("document-discussion-fragment-test-" + UUID.randomUUID());
            return folderService.save(folder);
        });
    }

    @AfterEach
    void tearDown() {
        conversationService.loadConversations().forEach(conversationService::remove);
        systemAuthenticator.runWithUser(ADMIN, () -> dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete));
        // leaving the view saves the column settings of the document list for the user
        userSettingsCache.delete("RepositoryView.documentList");
    }

    @Test
    void theToolbarAndTheMenuOfferTheDiscussionOfTheSelection() {
        Document lease = upload("lease.txt");
        RepositoryView view = open();
        DataGrid<Document> grid = listComponent(view, "documentsGrid");
        JmixButton button = listComponent(view, "discussButton");
        Action action = button.getAction();

        assertThat(action).isNotNull();
        assertThat(action.isVisible()).isTrue();
        assertThat(action.isEnabled()).isFalse();
        assertThat(button.getClassNames()).contains("aura-accent-purple", "ai-button");
        assertThat(grid.getContextMenu().getItems())
                .filteredOn(item -> FragmentUtils.getComponentId(item).equals(Optional.of("discussDocumentItem")))
                .singleElement()
                .satisfies(item -> assertThat(item.getClassNames()).contains("aura-accent-purple"));

        TestGrids.clickRow(grid, lease, false);

        assertThat(action.isEnabled()).isTrue();
        assertThat(grid.isRowsDraggable()).isTrue();
    }

    @Test
    void theWindowOpensWithTheSelectionAndTakesTheNextOneIntoIt() {
        Document lease = upload("lease.txt");
        Document invoice = upload("invoice.txt");
        Document act = upload("act.txt");
        RepositoryView view = open();
        DataGrid<Document> grid = listComponent(view, "documentsGrid");

        grid.select(lease);
        grid.select(invoice);
        discuss(view);
        DocumentDiscussionFragment discussion = window().orElseThrow();

        grid.deselectAll();
        grid.select(act);
        grid.select(lease);
        discuss(view);

        assertThat(window()).containsSame(discussion);
        assertThat(chips(discussion)).containsExactly("lease.txt", "invoice.txt", "act.txt");
    }

    @Test
    void theWindowDerivesItsColorsForTheMixedScheme() {
        Document lease = upload("lease.txt");
        RepositoryView view = open();
        DataGrid<Document> grid = listComponent(view, "documentsGrid");

        grid.select(lease);
        discuss(view);

        assertThat(sideDialog().getClassNames()).contains("aura-accent-color");
    }

    @Test
    void theWindowWearsTheTintOfTheAiAccent() {
        Document lease = upload("lease.txt");
        RepositoryView view = open();
        DataGrid<Document> grid = listComponent(view, "documentsGrid");

        grid.select(lease);
        discuss(view);

        assertThat(sideDialog().getClassNames()).contains("document-discussion-window");
    }

    @Test
    void theSelectionTakesNoMoreDocumentsThanItsLimit() {
        List<Document> documents = systemAuthenticator.withUser(ADMIN, () -> documentService.upload(sandbox,
                        IntStream.rangeClosed(1, 11)
                                .mapToObj(number -> TestFiles.file(tempDir, "contract-%02d.txt".formatted(number),
                                        "Contract " + number))
                                .toList())
                .created());

        RepositoryView view = open();
        DataGrid<Document> grid = listComponent(view, "documentsGrid");
        documents.forEach(grid::select);

        discuss(view);

        NotificationInfo warning = openedNotifications.getLastNotification();
        assertThat(chips(window().orElseThrow())).hasSize(10);
        assertThat(warning).isNotNull();
        assertThat(warning.getType()).isEqualTo(Notifications.Type.WARNING);
        assertThat(warning.getText())
                .isEqualTo(messages.formatMessage(DocumentDiscussionFragment.class, "tooManyDocuments", 10));
    }

    @Test
    void aCrossTakesADocumentOutAndAnEmptySelectionAsksForDocuments() {
        Document lease = upload("lease.txt");
        Document invoice = upload("invoice.txt");
        DocumentDiscussionFragment discussion = discuss(lease, invoice);

        chipButton(discussion, "lease.txt", 1).click();
        assertThat(chips(discussion)).containsExactly("invoice.txt");

        chipButton(discussion, "invoice.txt", 1).click();
        assertThat(this.<HorizontalLayout>component(discussion, "selection").getChildren())
                .containsExactly(this.<Span>component(discussion, "dropHint"));
    }

    @Test
    void theNameOfADocumentOpensItsCard() {
        Document lease = upload("lease.txt");
        DocumentDiscussionFragment discussion = discuss(lease);

        chipButton(discussion, "lease.txt", 0).click();

        DocumentDetailView card = UiTestUtils.getLastOpenedViewDialog();
        assertThat(card).isNotNull();
        assertThat(card.getEditedEntity()).isEqualTo(lease);
    }

    @Test
    void rowsDroppedOnTheSelectionJoinIt() {
        Document lease = upload("lease.txt");
        Document invoice = upload("invoice.txt");
        Document act = upload("act.txt");
        RepositoryView view = open();
        DataGrid<Document> grid = listComponent(view, "documentsGrid");
        grid.select(lease);
        discuss(view);
        DocumentDiscussionFragment discussion = window().orElseThrow();

        TestGrids.dragRows(grid, List.of(invoice, act), this.<HorizontalLayout>component(discussion, "selection"));

        assertThat(chips(discussion)).containsExactly("lease.txt", "invoice.txt", "act.txt");
    }

    // the rest of the window, the chat and its input among it, takes no rows
    @Test
    void rowsDroppedBesideTheSelectionStayOutOfIt() {
        Document lease = upload("lease.txt");
        Document invoice = upload("invoice.txt");
        RepositoryView view = open();
        DataGrid<Document> grid = listComponent(view, "documentsGrid");
        grid.select(lease);
        discuss(view);
        DocumentDiscussionFragment discussion = window().orElseThrow();

        TestGrids.dragRows(grid, List.of(invoice), this.<VerticalLayout>component(discussion, "discussionRoot"));

        assertThat(chips(discussion)).containsExactly("lease.txt");
    }

    @Test
    void theSelectionCallsForTheRowsWhileTheTableDragsThem() {
        Document lease = upload("lease.txt");
        Document invoice = upload("invoice.txt");
        RepositoryView view = open();
        DataGrid<Document> grid = listComponent(view, "documentsGrid");
        grid.select(lease);
        discuss(view);
        DocumentDiscussionFragment discussion = window().orElseThrow();
        HorizontalLayout selection = component(discussion, "selection");

        assertThat(DropTarget.configure(selection).isActive()).isFalse();

        TestGrids.startDrag(grid, List.of(invoice));

        assertThat(DropTarget.configure(selection).isActive()).isTrue();
        assertThat(selection.getClassNames()).contains("document-discussion-selection-awaiting");
        assertThat(chips(discussion)).containsExactly("lease.txt");
        assertThat(selection.getChildren().map(FragmentUtils::getComponentId))
                .last()
                .isEqualTo(Optional.of("dropHint"));

        TestGrids.endDrag(grid);

        assertThat(DropTarget.configure(selection).isActive()).isFalse();
        assertThat(selection.getClassNames()).doesNotContain("document-discussion-selection-awaiting");
        assertThat(selection.getChildren().map(FragmentUtils::getComponentId)).doesNotContain(Optional.of("dropHint"));
    }

    @Test
    void aFolderDraggedOntoTheSelectionStaysOutOfIt() {
        Document lease = upload("lease.txt");
        RepositoryView view = open();
        DataGrid<Document> grid = listComponent(view, "documentsGrid");
        grid.select(lease);
        discuss(view);
        DocumentDiscussionFragment discussion = window().orElseThrow();
        @SuppressWarnings("unchecked")
        TreeDataGrid<Folder> tree = (TreeDataGrid<Folder>) FragmentUtils.getComponent(
                UiTestUtils.getComponent(view, "folderTree"), "foldersTree");

        TestGrids.dragRows(tree, List.of(sandbox), this.<HorizontalLayout>component(discussion, "selection"));

        assertThat(chips(discussion)).containsExactly("lease.txt");
    }

    @Test
    void theFirstAnswerNamesTheWindowAndOpensTheConversationInTheFullChat() {
        Document lease = upload("lease.txt");
        DocumentDiscussionFragment discussion = discuss(lease);
        JmixButton fullChatButton = component(discussion, "fullChatButton");
        model.willReply("[The lease](%s) is paid by the 10th.".formatted(lease.getId()));
        assertThat(fullChatButton.isEnabled()).isFalse();

        ask(discussion);

        AiConversation conversation = conversationService.loadConversations().getFirst();
        assertThat(chat(discussion).getItems().getItems())
                .extracting(SimpleAiChatMessage::getContent)
                .containsExactly(QUESTION, "[The lease](documents/%s) is paid by the 10th.".formatted(lease.getId()));
        assertThat(this.<H4>component(discussion, "discussionTitle").getText()).isEqualTo(QUESTION);
        assertThat(fullChatButton.isEnabled()).isTrue();

        fullChatButton.click();

        assertThat(window()).isEmpty();
        assertThat(UI.getCurrent().getInternals().getActiveViewLocation().getPath())
                .isEqualTo("aitls/chat/" + conversation.getId());
    }

    @Test
    void theSuggestionAsksForASummaryOfEachDocument() {
        DocumentDiscussionFragment discussion = discuss(upload("lease.txt"));
        model.willReply("A lease of a warehouse.");

        ask(discussion, () -> this.<JmixButton>component(discussion, "summaryButton").click());

        assertThat(chat(discussion).getItems().getItems())
                .extracting(SimpleAiChatMessage::getContent)
                .containsExactly(messages.getMessage(DocumentDiscussionFragment.class, "summaryButton.prompt"),
                        "A lease of a warehouse.");
    }

    @Test
    void aLinkToACardOpensTheCardOverTheWindow() {
        Document lease = upload("lease.txt");
        DocumentDiscussionFragment discussion = discuss(lease);

        UI.getCurrent().navigate("documents/" + lease.getId());

        DocumentDetailView card = UiTestUtils.getLastOpenedViewDialog();
        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(RepositoryView.class);
        assertThat(window()).containsSame(discussion);
        assertThat(card).isNotNull();
        assertThat(card.getEditedEntity()).isEqualTo(lease);
    }

    @Test
    void withoutTheWindowALinkLeadsToTheCardPage() {
        Document lease = upload("lease.txt");
        open();

        UI.getCurrent().navigate("documents/" + lease.getId());

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(DocumentDetailView.class);
    }

    @Test
    void whatTheToolsDoShowsWhileTheAssistantThinks() {
        Document lease = upload("lease.txt");
        Document invoice = upload("invoice.txt");
        DocumentDiscussionFragment discussion = discuss(lease, invoice);
        AiChat<SimpleAiChatMessage> chat = chat(discussion);
        List<ThinkingStatusItem> statuses = new CopyOnWriteArrayList<>();
        model.willCallTool("docai_readDocumentText", readCall(lease, "Reading the lease"));
        model.willCallTool("docai_readDocumentText", readCall(invoice, "Reading the invoice"));
        model.willReply(() -> {
            // the statuses reach the chat through UI.access, which the test thread runs meanwhile
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> chat.getThinkingStatusPublisher().getItems().size() == 2);

            statuses.addAll(chat.getThinkingStatusPublisher().getItems());
            return "By the 10th.";
        });

        ask(discussion);

        assertThat(statuses)
                .extracting(ThinkingStatusItem::getText, ThinkingStatusItem::isCompleted)
                .containsExactly(tuple("Reading the lease", true), tuple("Reading the invoice", false));
    }

    @Test
    void aDocumentDeletedMeanwhileLeavesTheSelectionWithTheNextAnswer() {
        Document lease = upload("lease.txt");
        Document invoice = upload("invoice.txt");
        DocumentDiscussionFragment discussion = discuss(lease, invoice);
        systemAuthenticator.runWithUser(ADMIN, () -> documentService.delete(List.of(invoice)));
        model.willReply("By the 10th.");

        ask(discussion);

        assertThat(chips(discussion)).containsExactly("lease.txt");
    }

    @Test
    void aModelThatFailsIsReported() {
        DocumentDiscussionFragment discussion = discuss(upload("lease.txt"));

        ask(discussion);

        NotificationInfo failure = openedNotifications.getLastNotification();
        assertThat(failure).isNotNull();
        assertThat(failure.getType()).isEqualTo(Notifications.Type.ERROR);
        assertThat(failure.getText())
                .isEqualTo(messages.getMessage(DocumentDiscussionFragment.class, "discussionFailed"));
    }

    @Test
    void aWindowClosedWhileTheModelAnswersStopsTheAnswerQuietly() throws InterruptedException {
        DocumentDiscussionFragment discussion = discuss(upload("lease.txt"));
        CountDownLatch answering = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        model.willReply(() -> {
            answering.countDown();

            try {
                Thread.sleep(Duration.ofSeconds(20));
                return "By the 10th.";
            } catch (InterruptedException e) {
                stopped.countDown();
                throw new IllegalStateException("The answer was stopped", e);
            }
        });
        openedNotifications.closeOpenedNotifications();

        chat(discussion).prompt(QUESTION);
        assertThat(answering.await(20, TimeUnit.SECONDS)).isTrue();
        this.<JmixButton>component(discussion, "closeButton").click();

        assertThat(window()).isEmpty();
        assertThat(stopped.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(openedNotifications.getNotifications()).isEmpty();
    }

    @Test
    void aClosedWindowLeavesTheNextActionANewDiscussion() {
        Document lease = upload("lease.txt");
        Document invoice = upload("invoice.txt");
        DocumentDiscussionFragment first = discuss(lease);

        this.<JmixButton>component(first, "closeButton").click();
        assertThat(window()).isEmpty();

        DocumentDiscussionFragment second = discuss(invoice);
        assertThat(second).isNotSameAs(first);
        assertThat(chips(second)).containsExactly("invoice.txt");
    }

    @Test
    void leavingTheRepositoryClosesTheWindow() {
        discuss(upload("lease.txt"));

        viewNavigators.view(UiTestUtils.getCurrentView(), MainView.class).navigate();

        assertThat(window()).isEmpty();
    }

    private DocumentDiscussionFragment discuss(Document... documents) {
        RepositoryView view = open();
        DataGrid<Document> grid = listComponent(view, "documentsGrid");
        List.of(documents).forEach(grid::select);
        discuss(view);

        return window().orElseThrow();
    }

    private void discuss(RepositoryView view) {
        openedNotifications.closeOpenedNotifications();
        this.<JmixButton>listComponent(view, "discussButton").click();
    }

    private void ask(DocumentDiscussionFragment discussion) {
        ask(discussion, () -> chat(discussion).prompt(QUESTION));
    }

    // the answer comes back through UI.access, which a UI test runs only when asked
    private void ask(DocumentDiscussionFragment discussion, Runnable question) {
        VaadinSession session = VaadinSession.getCurrent();
        AiChat<SimpleAiChatMessage> chat = chat(discussion);
        openedNotifications.closeOpenedNotifications();

        question.run();

        await().pollInSameThread()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> {
                    session.getService().runPendingAccessTasks(session);
                    return chat.getState() != GenerationState.GENERATING;
                });
    }

    // a dialog joins the page right before the response to the browser
    private SideDialog sideDialog() {
        UI ui = UI.getCurrent();
        ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();

        return ui.getChildren()
                .filter(SideDialog.class::isInstance)
                .map(SideDialog.class::cast)
                .filter(SideDialog::isOpened)
                .findFirst()
                .orElseThrow();
    }

    private Optional<DocumentDiscussionFragment> window() {
        UI ui = UI.getCurrent();
        ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();

        return ui.getChildren()
                .filter(SideDialog.class::isInstance)
                .map(SideDialog.class::cast)
                .filter(SideDialog::isOpened)
                .flatMap(dialog -> dialog.getContentComponents().stream())
                .filter(DocumentDiscussionFragment.class::isInstance)
                .map(DocumentDiscussionFragment.class::cast)
                .findFirst();
    }

    private List<String> chips(DocumentDiscussionFragment discussion) {
        return this.<HorizontalLayout>component(discussion, "selection").getChildren()
                .filter(chip -> chip.getElement().getClassList().contains("document-discussion-chip"))
                .map(chip -> ((JmixButton) chip.getChildren().findFirst().orElseThrow()).getText())
                .toList();
    }

    // a chip holds the name of its document and the cross
    private JmixButton chipButton(DocumentDiscussionFragment discussion, String name, int index) {
        Component chip = this.<HorizontalLayout>component(discussion, "selection").getChildren()
                .filter(candidate -> candidate.getChildren()
                        .findFirst()
                        .filter(button -> name.equals(((JmixButton) button).getText()))
                        .isPresent())
                .findFirst()
                .orElseThrow();

        return (JmixButton) chip.getChildren().toList().get(index);
    }

    private AiChat<SimpleAiChatMessage> chat(DocumentDiscussionFragment discussion) {
        return component(discussion, "chat");
    }

    @SuppressWarnings("unchecked")
    private <T> T component(DocumentDiscussionFragment discussion, String id) {
        return (T) FragmentUtils.getComponent(discussion, id);
    }

    @SuppressWarnings("unchecked")
    private <T> T listComponent(RepositoryView view, String id) {
        return (T) FragmentUtils.getComponent(UiTestUtils.getComponent(view, "documentList"), id);
    }

    private RepositoryView open() {
        viewNavigators.view(UiTestUtils.getCurrentView(), RepositoryView.class)
                .withQueryParameters(QueryParameters.simple(Map.of("folder", sandbox.getId().toString())))
                .navigate();

        return UiTestUtils.getCurrentView();
    }

    private static String readCall(Document document, String status) {
        return "{\"status\": \"" + status + "\", \"documentId\": \"" + document.getId() + "\"}";
    }

    private Document upload(String fileName) {
        return systemAuthenticator.withUser(ADMIN, () -> documentService.upload(sandbox,
                        List.of(TestFiles.file(tempDir, fileName, "The rent is paid by the 10th of each month.")))
                .created()
                .getFirst());
    }
}
