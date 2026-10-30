package com.company.dms.view.document;

import com.company.dms.DMSApplication;
import com.company.dms.app.service.OperationRejectedException;
import com.company.dms.app.service.document.DocumentCommentService;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.access.AccessLevel;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentComment;
import com.company.dms.model.folder.Folder;
import com.company.dms.model.user.User;
import com.company.dms.test_support.EmployeeUiTestAuthenticator;
import com.company.dms.test_support.TestAccess;
import com.company.dms.test_support.TestAccess.Grant;
import com.company.dms.test_support.TestComments;
import com.company.dms.test_support.TestFiles;
import com.company.dms.view.main.MainView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.avatar.Avatar;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.data.provider.Query;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.metamodel.datatype.DatatypeFormatter;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.Fragments;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.component.tabsheet.JmixTabSheet;
import io.jmix.flowui.component.textarea.JmixTextArea;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.component.virtuallist.JmixVirtualList;
import io.jmix.flowui.fragment.Fragment;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.dialog.DialogInfo;
import io.jmix.flowui.view.DialogWindow;
import io.jmix.flowui.view.ViewControllerUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The comments tab of the document card, used by peter, an employee who may change the documents of the sandbox
 * unless a test gives a document an access of its own. The list of the tab draws its rows only when a browser asks
 * for them, so a test reads the comments the list holds and makes the row of one comment the way the fragment
 * renderer of the list does; the rows in the browser are checked by the walk.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DMSApplication.class, FlowuiTestAssistConfiguration.class})
class DocumentCommentsFragmentTest {

    private static final String AUTHOR = EmployeeUiTestAuthenticator.USERNAME;

    @Autowired
    private DialogWindows dialogWindows;

    @Autowired
    private Fragments fragments;

    @Autowired
    private DocumentCommentService commentService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private CurrentAuthentication currentAuthentication;

    @Autowired
    private DatatypeFormatter datatypeFormatter;

    @TempDir
    private Path tempDir;

    private Folder sandbox;
    private Document document;

    @BeforeEach
    void createSandbox() {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("document-comments-fragment-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);
        document = documentService.upload(sandbox, List.of(TestFiles.file(tempDir, "lease.txt", "Lease")))
                .created()
                .getFirst();
    }

    // a document with an access of its own may be closed to peter, so the administrator cleans up
    @AfterEach
    void deleteSandbox() {
        systemAuthenticator.runWithUser("admin", () -> {
            TestComments.removeAll(dataManager, sandbox);
            folderService.delete(sandbox);
        });
    }

    @Test
    void aNewDocumentHasNoCommentsTab() {
        DialogWindow<DocumentDetailView> window = dialogWindows.detail(UiTestUtils.getCurrentView(), Document.class)
                .withViewClass(DocumentDetailView.class)
                .newEntity()
                .withInitializer(created -> created.setFolder(sandbox))
                .build();
        window.open();

        assertThat(this.<Tab>component(window.getView(), "commentsTab").isVisible()).isFalse();
    }

    @Test
    void theTabCountsTheCommentsAndShowsTheNewestFirst() {
        DocumentComment older = post("alice", "Check item 4.2");
        backdate(older, OffsetDateTime.now().minusMinutes(5));
        post(AUTHOR, "Agreed at 0.2 percent");

        DocumentDetailView card = openCommentsTab();

        assertThat(this.<Tab>component(card, "commentsTab").getLabel()).endsWith("(2)");
        assertThat(texts(card)).containsExactly("Agreed at 0.2 percent", "Check item 4.2");
        assertThat(this.<Span>inner(comments(card), "noComments").isVisible()).isFalse();
    }

    @Test
    void aCommentIsSentFromTheFieldAndTheFieldEmpties() {
        DocumentDetailView card = openCommentsTab();
        JmixTextArea field = inner(comments(card), "commentField");

        field.setValue("Please add the annex");
        this.<JmixButton>inner(comments(card), "postCommentButton").click();

        assertThat(texts(card)).containsExactly("Please add the annex");
        assertThat(field.getValue()).isEmpty();
        assertThat(this.<Tab>component(card, "commentsTab").getLabel()).endsWith("(1)");
        assertThat(card.hasUnsavedChanges()).isFalse();
    }

    @Test
    void theCommentsStayOutOfTheDataOfTheCard() {
        DocumentComment comment = post(AUTHOR, "Check item 4.2");

        DocumentDetailView card = openCommentsTab();

        assertThat(texts(card)).containsExactly("Check item 4.2");
        assertThat(ViewControllerUtils.getViewData(card)
                .getDataContext()
                .find(DocumentComment.class, comment.getId())).isNull();
    }

    @Test
    void theSendButtonIsAnIconInsideTheField() {
        DocumentDetailView card = openCommentsTab();
        JmixTextArea field = inner(comments(card), "commentField");
        JmixButton button = inner(comments(card), "postCommentButton");

        assertThat(field.getSuffixComponent()).isSameAs(button);
        assertThat(button.getIcon()).isNotNull();
        assertThat(button.getText()).isEmpty();
        // an icon keeps its name in a tooltip, as the icons of the card's header
        assertThat(button.getTooltip().getText()).isNotBlank();
    }

    @Test
    void theButtonSendsOnlyATextThatFits() {
        DocumentDetailView card = openCommentsTab();
        JmixTextArea field = inner(comments(card), "commentField");
        JmixButton button = inner(comments(card), "postCommentButton");
        Span length = inner(comments(card), "commentLength");

        assertThat(button.isEnabled()).isFalse();

        field.setValue("x".repeat(DocumentComment.MAX_TEXT_LENGTH + 1));
        assertThat(button.isEnabled()).isFalse();
        assertThat(length.getClassNames()).contains("document-comment-length-over");

        field.setValue("Fine");
        assertThat(button.isEnabled()).isTrue();
        assertThat(length.getClassNames()).doesNotContain("document-comment-length-over");
        assertThat(length.getText()).startsWith("4");
    }

    @Test
    void theAuthorEditsTheirCommentInPlace() {
        post(AUTHOR, "Check item 4.2");
        DocumentDetailView card = openCommentsTab();
        DocumentCommentItemFragment row = row(card, items(card).getFirst());

        assertThat(this.<Span>inner(row, "commentText").getText()).isEqualTo("Check item 4.2");
        assertThat(this.<Span>inner(row, "editedMark").isVisible()).isFalse();

        this.<JmixButton>inner(row, "editCommentButton").click();
        JmixTextArea field = inner(row, "editCommentField");
        assertThat(this.<VerticalLayout>inner(row, "editPanel").isVisible()).isTrue();
        assertThat(this.<Span>inner(row, "commentText").isVisible()).isFalse();
        assertThat(field.getValue()).isEqualTo("Check item 4.2");

        field.setValue("Check items 4.2 and 4.3");
        this.<JmixButton>inner(row, "saveCommentButton").click();

        assertThat(texts(card)).containsExactly("Check items 4.2 and 4.3");
        DocumentCommentItemFragment changed = row(card, items(card).getFirst());
        assertThat(this.<Span>inner(changed, "commentText").getText()).isEqualTo("Check items 4.2 and 4.3");
        assertThat(this.<Span>inner(changed, "editedMark").isVisible()).isTrue();
        assertThat(this.<VerticalLayout>inner(changed, "editPanel").isVisible()).isFalse();
    }

    @Test
    void theSaveButtonOfAChangeTakesOnlyATextThatFits() {
        post(AUTHOR, "Check item 4.2");
        DocumentDetailView card = openCommentsTab();
        DocumentCommentItemFragment row = row(card, items(card).getFirst());
        this.<JmixButton>inner(row, "editCommentButton").click();
        JmixTextArea field = inner(row, "editCommentField");
        JmixButton save = inner(row, "saveCommentButton");
        Span length = inner(row, "editCommentLength");

        field.setValue("x".repeat(DocumentComment.MAX_TEXT_LENGTH + 1));
        assertThat(save.isEnabled()).isFalse();
        assertThat(length.getClassNames()).contains("document-comment-length-over");

        field.setValue("Fine");
        assertThat(save.isEnabled()).isTrue();
        assertThat(length.getClassNames()).doesNotContain("document-comment-length-over");
        assertThat(length.getText()).startsWith("4");
    }

    // on a narrow screen the buttons of a comment go to the next line instead of past its edge
    @Test
    void theHeaderOfACommentWraps() {
        post(AUTHOR, "Check item 4.2");
        DocumentDetailView card = openCommentsTab();
        DocumentCommentItemFragment row = row(card, items(card).getFirst());

        assertThat(this.<HorizontalLayout>inner(row, "commentHeader").isWrap()).isTrue();
    }

    @Test
    void theAuthorDeletesTheirCommentAfterAQuestion() {
        post(AUTHOR, "Never mind");
        DocumentDetailView card = openCommentsTab();

        this.<JmixButton>inner(row(card, items(card).getFirst()), "deleteCommentButton").click();
        dialogButton(DialogAction.Type.YES).click();

        assertThat(items(card)).isEmpty();
        assertThat(this.<Span>inner(comments(card), "noComments").isVisible()).isTrue();
        assertThat(this.<Tab>component(card, "commentsTab").getLabel()).endsWith("(0)");
    }

    @Test
    void theCommentOfAnotherPersonHasNoButtonsButTheAdministratorDeletesIt() {
        post("alice", "Check item 4.2");

        DocumentDetailView card = openCommentsTab();
        DocumentCommentItemFragment row = row(card, items(card).getFirst());

        assertThat(this.<JmixButton>inner(row, "editCommentButton").isVisible()).isFalse();
        assertThat(this.<JmixButton>inner(row, "deleteCommentButton").isVisible()).isFalse();

        systemAuthenticator.runWithUser("admin", () -> {
            DocumentDetailView cardOfAdmin = openCommentsTab();
            DocumentCommentItemFragment seenByAdmin = row(cardOfAdmin, items(cardOfAdmin).getFirst());

            assertThat(this.<JmixButton>inner(seenByAdmin, "editCommentButton").isVisible()).isFalse();
            assertThat(this.<JmixButton>inner(seenByAdmin, "deleteCommentButton").isVisible()).isTrue();
        });
    }

    @Test
    void aPersonWhoOnlyViewsTheDocumentCommentsInTheReadOnlyCard() {
        systemAuthenticator.runWithSystem(() -> TestAccess.giveOwnAccess(dataManager, document,
                Grant.user(AUTHOR, AccessLevel.VIEW)));
        DocumentDetailView card = openCommentsTab();
        JmixTextArea field = inner(comments(card), "commentField");

        assertThat(this.<TypedTextField<String>>component(card, "nameField").isReadOnly()).isTrue();
        assertThat(field.isReadOnly()).isFalse();

        field.setValue("Please add the annex");
        this.<JmixButton>inner(comments(card), "postCommentButton").click();

        assertThat(texts(card)).containsExactly("Please add the annex");
    }

    @Test
    void aRefusedChangeShowsTheCommentsAsTheyAre() {
        DocumentComment comment = post(AUTHOR, "Check item 4.2");
        DocumentDetailView card = openCommentsTab();
        DocumentCommentItemFragment row = row(card, items(card).getFirst());
        systemAuthenticator.runWithUser("admin", () -> commentService.delete(comment));

        this.<JmixButton>inner(row, "editCommentButton").click();
        this.<JmixTextArea>inner(row, "editCommentField").setValue("Too late");

        assertThatThrownBy(() -> this.<JmixButton>inner(row, "saveCommentButton").click())
                .isInstanceOf(OperationRejectedException.class);
        assertThat(items(card)).isEmpty();
    }

    @Test
    void aCommentOfAUserDeletedSinceShowsTheUsername() {
        DocumentComment comment = post(AUTHOR, "Check item 4.2");
        systemAuthenticator.runWithSystem(() -> {
            DocumentComment stored = dataManager.load(Id.of(comment)).one();
            stored.setCreatedBy("gone");
            dataManager.save(stored);
        });

        DocumentDetailView card = openCommentsTab();
        DocumentCommentItemFragment row = row(card, items(card).getFirst());

        assertThat(this.<Span>inner(row, "authorName").getText()).isEqualTo("gone");
        assertThat(this.<Avatar>inner(row, "authorAvatar").getAbbreviation()).isEqualTo("G");
        assertThat(this.<JmixButton>inner(row, "editCommentButton").isVisible()).isFalse();
        assertThat(this.<JmixButton>inner(row, "deleteCommentButton").isVisible()).isFalse();
    }

    @Test
    void theTimeOfACommentIsInThePersonsTimeZone() {
        backdate(post("alice", "Check item 4.2"), OffsetDateTime.of(2020, 1, 15, 12, 0, 0, 0, ZoneOffset.UTC));
        // the person works in Kiritimati, where noon in UTC is already the next day; the user of the test's login
        // is loaded for this test alone
        ((User) currentAuthentication.getUser()).setTimeZoneId("Pacific/Kiritimati");

        DocumentDetailView card = openCommentsTab();
        DocumentCommentItemFragment row = row(card, items(card).getFirst());

        assertThat(this.<Span>inner(row, "writtenTime").getText())
                .isEqualTo(datatypeFormatter.formatLocalDateTime(LocalDateTime.of(2020, 1, 16, 2, 0)));
    }

    private DocumentComment post(String username, String text) {
        return systemAuthenticator.withUser(username, () -> commentService.post(document, text));
    }

    private void backdate(DocumentComment comment, OffsetDateTime createdDate) {
        systemAuthenticator.runWithSystem(() -> {
            DocumentComment stored = dataManager.load(Id.of(comment)).one();
            stored.setCreatedDate(createdDate);
            dataManager.save(stored);
        });
    }

    // the card opens over the current view with its comments tab selected: the content of a tab that is not
    // selected is disabled
    private DocumentDetailView openCommentsTab() {
        DialogWindow<DocumentDetailView> window = dialogWindows.detail(UiTestUtils.getCurrentView(), Document.class)
                .withViewClass(DocumentDetailView.class)
                .editEntity(dataManager.load(Id.of(document)).one())
                .build();
        window.open();

        DocumentDetailView card = window.getView();
        this.<JmixTabSheet>component(card, "cardTabs").setSelectedTab(component(card, "commentsTab"));

        return card;
    }

    private DocumentCommentsFragment comments(DocumentDetailView card) {
        return component(card, "comments");
    }

    // the comments the list holds, in its order
    private List<DocumentComment> items(DocumentDetailView card) {
        JmixVirtualList<DocumentComment> list = inner(comments(card), "commentsList");

        return Objects.requireNonNull(list.getDataProvider())
                .fetch(new Query<>())
                .toList();
    }

    private List<String> texts(DocumentDetailView card) {
        return items(card).stream()
                .map(DocumentComment::getText)
                .toList();
    }

    // the row of the comment as the fragment renderer of the list makes it: a fragment with the comments tab as
    // its parent, given the comment
    private DocumentCommentItemFragment row(DocumentDetailView card, DocumentComment comment) {
        DocumentCommentItemFragment row = fragments.create(comments(card), DocumentCommentItemFragment.class);
        row.setItem(comment);

        return row;
    }

    private static Button dialogButton(DialogAction.Type type) {
        DialogInfo dialog = UiTestUtils.getLastOpenedDialog();

        return dialog.getButtons().stream()
                .filter(button -> button.getId().equals(Optional.of(type.getId())))
                .findFirst()
                .orElseThrow();
    }

    private <T> T component(DocumentDetailView card, String id) {
        return UiTestUtils.getComponent(card, id);
    }

    @SuppressWarnings("unchecked")
    private <T extends Component> T inner(Fragment<?> fragment, String id) {
        return (T) FragmentUtils.getComponent(fragment, id);
    }
}
