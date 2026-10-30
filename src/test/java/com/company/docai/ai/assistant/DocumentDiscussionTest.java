package com.company.docai.ai.assistant;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.discussion.DocumentDiscussionService;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.StubAiConfiguration;
import com.company.docai.test_support.StubChatModel;
import com.company.docai.test_support.TestAccess;
import com.company.docai.test_support.TestAccess.Grant;
import com.company.docai.test_support.TestFiles;
import com.company.docai.view.main.MainView;
import io.jmix.aitoolsflowui.model.AiChatMessage;
import io.jmix.aitoolsflowui.model.AiChatMessageType;
import io.jmix.aitoolsflowui.model.AiConversation;
import io.jmix.aitoolsflowui.service.AiChatMessageService;
import io.jmix.aitoolsflowui.service.AiConversationService;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * A discussion of a selection of documents with the stub model: the selection before the first question and after
 * it, the conversation of the add-on the first question starts, and the answer as the conversation keeps it.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class,
        StubAiConfiguration.class})
@TestPropertySource(properties = "spring.ai.openai.api-key=test-key")
class DocumentDiscussionTest {

    private static final String ADMIN = "admin";
    private static final String QUESTION = "When is the rent paid?";

    @Autowired
    private ObjectProvider<DocumentDiscussion> discussions;

    @Autowired
    private DocumentDiscussionService discussionService;

    @Autowired
    private AiConversationService conversationService;

    @Autowired
    private AiChatMessageService messageService;

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

    @BeforeEach
    void setUp() {
        model.reset();
        sandbox = systemAuthenticator.withUser(ADMIN, () -> {
            Folder folder = dataManager.create(Folder.class);
            folder.setParent(folderService.getRoot());
            folder.setName("document-discussion-test-" + UUID.randomUUID());
            return folderService.save(folder);
        });
    }

    @AfterEach
    void tearDown() {
        conversationService.loadConversations().forEach(conversationService::remove);
        systemAuthenticator.runWithUser(ADMIN, () -> dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete));
    }

    @Test
    void theSelectionTakesNoRepeatsAndStopsAtItsLimit() {
        DocumentDiscussion discussion = discussions.getObject();
        List<Document> documents = IntStream.rangeClosed(0, discussion.getMaxDocuments())
                .mapToObj(number -> dataManager.create(Document.class))
                .toList();

        assertThat(discussion.add(documents.subList(0, 2))).isTrue();
        assertThat(discussion.add(List.of(documents.getFirst()))).isTrue();
        assertThat(discussion.getDocuments()).containsExactly(documents.get(0), documents.get(1));

        assertThat(discussion.add(documents)).isFalse();
        assertThat(discussion.getDocuments()).hasSize(discussion.getMaxDocuments())
                .startsWith(documents.get(0), documents.get(1));
    }

    @Test
    void thereIsNoConversationBeforeTheFirstQuestion() {
        int conversations = conversationService.loadConversations().size();
        DocumentDiscussion discussion = discussions.getObject();

        discussion.add(List.of(upload("lease.txt")));

        assertThat(discussion.getConversation()).isEmpty();
        assertThat(conversationService.loadConversations()).hasSize(conversations);
    }

    @Test
    void theFirstQuestionStartsAConversationNamedAfterItAboutTheSelection() {
        Document lease = upload("lease.txt");
        DocumentDiscussion discussion = discussions.getObject();
        discussion.add(List.of(lease));
        model.willReply("[The lease](%s) is paid by the 10th.".formatted(lease.getId()));

        String answer = discussion.ask(QUESTION, update -> {
        });

        AiConversation conversation = discussion.getConversation().orElseThrow();
        assertThat(answer).isEqualTo("[The lease](documents/%s) is paid by the 10th.".formatted(lease.getId()));
        assertThat(conversation.getTitle()).isEqualTo(QUESTION);
        assertThat(discussionService.documentsOf(conversation.getId())).containsExactly(lease);
        assertThat(messageService.loadMessages(conversation))
                .extracting(AiChatMessage::getType, AiChatMessage::getContent)
                .containsExactly(tuple(AiChatMessageType.USER, QUESTION), tuple(AiChatMessageType.ASSISTANT, answer));
    }

    @Test
    void theSelectionChangedAfterTheFirstQuestionIsKept() {
        Document lease = upload("lease.txt");
        Document invoice = upload("invoice.txt");
        DocumentDiscussion discussion = discussions.getObject();
        discussion.add(List.of(lease));
        model.willReply("By the 10th.");
        discussion.ask(QUESTION, update -> {
        });
        UUID conversation = discussion.getConversation().orElseThrow().getId();

        discussion.add(List.of(invoice));
        assertThat(discussionService.documentsOf(conversation)).containsExactly(lease, invoice);

        discussion.remove(lease);
        assertThat(discussionService.documentsOf(conversation)).containsExactly(invoice);
    }

    @Test
    void aDocumentThePersonMayNoLongerOpenLeavesTheSelectionWithTheNextAnswer() {
        Document lease = upload("lease.txt");
        Document invoice = upload("invoice.txt");
        DocumentDiscussion discussion = discussions.getObject();
        discussion.add(List.of(lease, invoice));
        model.willReply("By the 10th.");
        discussion.ask(QUESTION, update -> {
        });

        systemAuthenticator.runWithUser(ADMIN, () -> TestAccess.giveOwnAccess(dataManager, invoice,
                Grant.user("maria", AccessLevel.VIEW)));

        model.willReply("Still by the 10th.");
        discussion.ask(QUESTION, update -> {
        });

        assertThat(discussion.getDocuments()).containsExactly(lease);
    }

    @Test
    void whatTheToolsDoReachesTheDiscussion() {
        Document lease = upload("lease.txt");
        DocumentDiscussion discussion = discussions.getObject();
        discussion.add(List.of(lease));
        model.willCallTool("docai_readDocumentText",
                "{\"status\": \"Reading the lease\", \"documentId\": \"" + lease.getId() + "\"}");

        model.willReply("By the 10th.");
        List<String> statuses = new CopyOnWriteArrayList<>();

        discussion.ask(QUESTION, update -> statuses.add(update.getMessage()));

        assertThat(statuses).containsExactly("Reading the lease");
    }

    private Document upload(String fileName) {
        return systemAuthenticator.withUser(ADMIN, () -> documentService.upload(sandbox,
                        List.of(TestFiles.file(tempDir, fileName, "The rent is paid by the 10th of each month.")))
                .created()
                .getFirst());
    }
}
