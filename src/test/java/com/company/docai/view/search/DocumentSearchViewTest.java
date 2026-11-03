package com.company.docai.view.search;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.tag.Tag;
import com.company.docai.search.DocumentSearch;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestAccess;
import com.company.docai.test_support.TestAccess.Grant;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestTags;
import com.company.docai.view.document.DocumentDetailView;
import com.company.docai.view.main.MainView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.combobox.ComboBoxBase;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.QueryParameters;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.IdSerialization;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.component.multiselectcombobox.JmixMultiSelectComboBox;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.notification.NotificationInfo;
import io.jmix.search.searching.EntitySearcher;
import io.jmix.search.searching.FieldHit;
import io.jmix.search.searching.SearchResult;
import io.jmix.search.searching.SearchResultEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The search results as an employee sees them. The index is replaced: it answers with the documents of the test,
 * and the rest of the way, from the reload through {@code DataManager} to the rows, is real.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class DocumentSearchViewTest {

    private static final String ADMIN = "admin";
    private static final String COLLEAGUE = "maria";
    private static final String FILE_TEXT = "currentRevision.file._content";

    @MockitoBean
    private EntitySearcher entitySearcher;

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private IdSerialization idSerialization;

    @Autowired
    private Messages messages;

    @Autowired
    private MetadataTools metadataTools;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        sandbox = systemAuthenticator.withUser(ADMIN, () -> {
            Folder folder = dataManager.create(Folder.class);
            folder.setParent(folderService.getRoot());
            folder.setName("document-search-test-" + UUID.randomUUID());
            return folderService.save(folder);
        });
    }

    @AfterEach
    void deleteSandbox() {
        systemAuthenticator.runWithUser(ADMIN, () -> folderService.delete(sandbox));
    }

    @Test
    void aRowShowsTheDocumentItsFolderItsStatusAndTheMatchedText() {
        Document document = upload();
        answer(entry(document, "…supply of <b>tractors</b> and combines…"));

        DocumentSearchView view = search("tractor");
        Component row = rows(view).getFirst();

        assertThat(rows(view)).hasSize(1);
        assertThat(this.<JmixButton>byClass(row, "document-search-name").getText()).isEqualTo("contract.txt");
        assertThat(this.<Span>byClass(row, "document-search-path").getText())
                .isEqualTo(metadataTools.getInstanceName(folderService.getRoot()) + " / " + sandbox.getName());
        assertThat(byClass(row, "document-search-snippet").getElement().getTextRecursively())
                .isEqualTo(messages.getMessage(DocumentSearch.class, "fileText")
                        + "…supply of tractors and combines…");
        assertThat(this.<Span>byClass(row, "document-search-match").getText()).isEqualTo("tractors");
        assertThat(row.getElement().getTextRecursively())
                .contains(document.getStatus().getName());
    }

    @Test
    void theMatchedTextIsShownAsTextAndNeverAsMarkup() {
        Document document = upload();
        answer(entry(document, "<img src=x onerror=alert(1)> and <b>word</b>"));

        Component snippet = byClass(rows(search("word")).getFirst(), "document-search-snippet");

        assertThat(snippet.getElement().getTextRecursively()).contains("<img src=x onerror=alert(1)> and word");
        assertThat(snippet.getElement().getChildren())
                .allMatch(child -> child.isTextNode() || child.getTag().equals("span"));
    }

    @Test
    void showMoreAddsTheNextPage() {
        List<Document> documents = uploadMoreThanAPage();

        answer(documents.stream()
                .map(document -> entry(document, "<b>word</b>"))
                .toArray(SearchResultEntry[]::new));

        DocumentSearchView view = search("word");
        JmixButton showMore = UiTestUtils.getComponent(view, "showMoreButton");

        assertThat(rows(view)).hasSize(DocumentSearch.PAGE_SIZE);
        assertThat(showMore.isVisible()).isTrue();

        showMore.click();

        assertThat(rows(view)).hasSize(DocumentSearch.PAGE_SIZE + 1);
        assertThat(showMore.isVisible()).isFalse();
    }

    @Test
    void nothingFoundIsSaid() {
        answer();

        DocumentSearchView view = search("word");

        assertThat(rows(view)).isEmpty();
        assertThat(message(view)).isEqualTo(messages.getMessage(DocumentSearchView.class, "nothingFound"));
    }

    @Test
    void anUnavailableSearchIsSaid() {
        when(entitySearcher.search(any(), anyString())).thenThrow(new RuntimeException("Search failed"));

        DocumentSearchView view = search("word");

        assertThat(rows(view)).isEmpty();
        assertThat(message(view)).isEqualTo(messages.getMessage(DocumentSearchView.class, "searchUnavailable"));
    }

    @Test
    void aLinkToTheAiSearchSearchesByWordsWithoutTheKey() {
        Document document = upload();
        answer(entry(document, "<b>word</b>"));

        viewNavigators.view(UiTestUtils.getCurrentView(), DocumentSearchView.class)
                .withQueryParameters(QueryParameters.simple(Map.of(DocumentSearchView.QUERY_PARAMETER, "word",
                        DocumentSearchView.MODE_PARAMETER, DocumentSearchView.AI_MODE)))
                .navigate();
        DocumentSearchView view = UiTestUtils.getCurrentView();

        assertThat(rows(view)).hasSize(1);
        assertThat(UiTestUtils.<Span>getComponent(view, "noteText").getText())
                .isEqualTo(messages.getMessage(DocumentSearchView.class, "aiSearchOff"));
    }

    @Test
    void noWordsAskForWords() {
        DocumentSearchView view = search("  ");

        assertThat(message(view)).isEqualTo(messages.getMessage(DocumentSearchView.class, "enterQuery"));
        verify(entitySearcher, never()).search(any(), anyString());
    }

    @Test
    void theNameOpensTheCardInADialog() {
        Document document = upload();
        answer(entry(document, "<b>word</b>"));

        this.<JmixButton>byClass(rows(search("word")).getFirst(), "document-search-name").click();

        DocumentDetailView card = UiTestUtils.getLastOpenedViewDialog();
        assertThat(card).isNotNull();
        assertThat(card.getEditedEntity()).isEqualTo(document);
    }

    @Test
    void aDocumentGoneSinceTheSearchIsSaidToBeUnavailable() {
        Document document = upload();
        answer(entry(document, "<b>word</b>"));
        JmixButton name = byClass(rows(search("word")).getFirst(), "document-search-name");

        systemAuthenticator.runWithUser(ADMIN, () -> documentService.delete(List.of(document)));
        name.click();

        NotificationInfo notification = UiTestUtils.getLastOpenedNotification();
        assertThat(UiTestUtils.getOpenedViewDialogs()).isEmpty();
        assertThat(notification).isNotNull();
        assertThat(notification.getText())
                .isEqualTo(messages.getMessage(DocumentDetailView.class, "documentUnavailable"));
    }

    @Test
    void aDocumentTheEmployeeMayNotSeeIsNotShownThoughTheIndexFindsIt() {
        Document hidden = systemAuthenticator.withUser(ADMIN, () ->
                TestAccess.giveOwnAccess(dataManager, upload(), Grant.user(COLLEAGUE, AccessLevel.EDIT)));
        answer(entry(hidden, "<b>word</b>"));

        DocumentSearchView view = search("word");

        assertThat(rows(view)).isEmpty();
        assertThat(message(view)).isEqualTo(messages.getMessage(DocumentSearchView.class, "nothingFound"));
    }

    @Test
    void aDeletedDocumentIsNotShownThoughTheIndexFindsIt() {
        Document deleted = upload();
        systemAuthenticator.runWithUser(ADMIN, () -> documentService.delete(List.of(deleted)));
        answer(entry(deleted, "<b>word</b>"));

        DocumentSearchView view = search("word");

        assertThat(rows(view)).isEmpty();
        assertThat(message(view)).isEqualTo(messages.getMessage(DocumentSearchView.class, "nothingFound"));
    }

    @Test
    void aRowShowsTheTagsOfTheDocumentAndATagOpensItsPage() {
        String lease = "Lease " + UUID.randomUUID().toString().substring(0, 8);
        Document document = TestTags.tagged(unconstrainedDataManager, upload(),
                TestTags.tag(unconstrainedDataManager, lease));
        answer(entry(document, "<b>word</b>"));

        Component row = rows(search("word")).getFirst();

        assertThat(TestTags.names(row)).containsExactly(lease);

        TestTags.click(row, lease);

        DocumentSearchView view = UiTestUtils.getCurrentView();
        assertThat(TestTags.tagInAddress()).hasValue(lease);
        assertThat(view.getPageTitle()).isEqualTo(messages.formatMessage(DocumentSearchView.class, "tagTitle", lease));
        assertThat(rows(view)).hasSize(1);
    }

    @Test
    void aRowShowsTheDocumentAsTheCardSavedIt() {
        String lease = "Lease " + UUID.randomUUID().toString().substring(0, 8);
        answer(entry(upload(), "<b>word</b>"));
        DocumentSearchView view = search("word");
        this.<JmixButton>byClass(rows(view).getFirst(), "document-search-name").click();
        DocumentDetailView card = UiTestUtils.getLastOpenedViewDialog();
        JmixMultiSelectComboBox<Tag> tagsField = UiTestUtils.getComponent(card, "tagsField");

        UiTestUtils.<TypedTextField<String>>getComponent(card, "nameField").setValue("renamed.txt");
        ComponentUtil.fireEvent(tagsField, new ComboBoxBase.CustomValueSetEvent<>(tagsField, true, lease));
        UiTestUtils.<JmixButton>getComponent(card, "saveButton").click();

        Component row = rows(view).getFirst();
        assertThat(this.<JmixButton>byClass(row, "document-search-name").getText()).isEqualTo("renamed.txt");
        assertThat(TestTags.names(row)).containsExactly(lease);
    }

    @Test
    void aDocumentWhoseTagTheCardTookOffLeavesThePageOfTheTag() {
        Tag tag = TestTags.tag(unconstrainedDataManager, "Lease " + UUID.randomUUID().toString().substring(0, 8));
        TestTags.tagged(unconstrainedDataManager, upload("kept.txt"), tag);
        TestTags.tagged(unconstrainedDataManager, upload("untagged.txt"), tag);
        DocumentSearchView view = tagPage(tag.getName());
        JmixButton untagged = rows(view).stream()
                .map(row -> this.<JmixButton>byClass(row, "document-search-name"))
                .filter(name -> name.getText().equals("untagged.txt"))
                .findFirst()
                .orElseThrow();

        untagged.click();
        DocumentDetailView card = UiTestUtils.getLastOpenedViewDialog();
        UiTestUtils.<JmixMultiSelectComboBox<Tag>>getComponent(card, "tagsField").setValue(Set.of());
        UiTestUtils.<JmixButton>getComponent(card, "saveButton").click();

        assertThat(rows(view)).extracting(row -> this.<JmixButton>byClass(row, "document-search-name").getText())
                .containsExactly("kept.txt");
    }

    @Test
    void theTagPageListsTheLiveDocumentsWithTheTagThatTheEmployeeMaySeeTheLatestChangedFirst() {
        Tag tag = TestTags.tag(unconstrainedDataManager, "Lease " + UUID.randomUUID().toString().substring(0, 8));
        Document older = changedAt(TestTags.tagged(unconstrainedDataManager, upload("older.txt"), tag), 2);
        Document newer = changedAt(TestTags.tagged(unconstrainedDataManager, upload("newer.txt"), tag), 1);
        Document secret = TestTags.tagged(unconstrainedDataManager, upload(secretFolder(), "secret.txt"), tag);
        Document deleted = TestTags.tagged(unconstrainedDataManager, upload("deleted.txt"), tag);
        systemAuthenticator.runWithUser(ADMIN, () -> documentService.delete(List.of(deleted)));

        DocumentSearchView view = tagPage(tag.getName().toUpperCase());

        assertThat(view.getPageTitle())
                .isEqualTo(messages.formatMessage(DocumentSearchView.class, "tagTitle", tag.getName()));
        assertThat(rows(view)).extracting(row -> this.<JmixButton>byClass(row, "document-search-name").getText())
                .containsExactly(newer.getName(), older.getName())
                .doesNotContain(secret.getName(), deleted.getName());
        assertThat(UiComponentUtils.getComponents(rows(view).getFirst()))
                .noneMatch(component -> component.hasClassName("document-search-snippet"));
    }

    @Test
    void showMoreAddsTheNextPageOfTheTag() {
        Tag tag = TestTags.tag(unconstrainedDataManager, "Lease " + UUID.randomUUID().toString().substring(0, 8));
        uploadMoreThanAPage().forEach(document -> TestTags.tagged(unconstrainedDataManager, document, tag));

        DocumentSearchView view = tagPage(tag.getName());
        JmixButton showMore = UiTestUtils.getComponent(view, "showMoreButton");

        assertThat(rows(view)).hasSize(DocumentSearch.PAGE_SIZE);
        assertThat(showMore.isVisible()).isTrue();

        showMore.click();

        assertThat(rows(view)).hasSize(DocumentSearch.PAGE_SIZE + 1);
        assertThat(showMore.isVisible()).isFalse();
    }

    @Test
    void aTagThatIsNotThereIsSaid() {
        String name = "Gone " + UUID.randomUUID().toString().substring(0, 8);

        DocumentSearchView view = tagPage(name);

        assertThat(rows(view)).isEmpty();
        assertThat(message(view)).isEqualTo(messages.formatMessage(DocumentSearchView.class, "tagNotFound", name));
    }

    @Test
    void aTagWithoutDocumentsTheEmployeeMaySeeSaysNothingOfTheOthers() {
        Tag tag = TestTags.tag(unconstrainedDataManager, "Lease " + UUID.randomUUID().toString().substring(0, 8));
        TestTags.tagged(unconstrainedDataManager, upload(secretFolder(), "secret.txt"), tag);

        DocumentSearchView view = tagPage(tag.getName());

        assertThat(rows(view)).isEmpty();
        assertThat(message(view))
                .isEqualTo(messages.formatMessage(DocumentSearchView.class, "nothingTagged", tag.getName()));
    }

    // nothing else carries the tag, so the page says where its documents are
    @Test
    void aTagOnlyOnDocumentsInTheTrashSaysSo() {
        Tag tag = TestTags.tag(unconstrainedDataManager, "Lease " + UUID.randomUUID().toString().substring(0, 8));
        Document document = TestTags.tagged(unconstrainedDataManager, upload(), tag);
        systemAuthenticator.runWithUser(ADMIN, () -> documentService.delete(List.of(document)));

        DocumentSearchView view = tagPage(tag.getName());

        assertThat(rows(view)).isEmpty();
        assertThat(message(view))
                .isEqualTo(messages.formatMessage(DocumentSearchView.class, "taggedOnlyInTrash", tag.getName()));
    }

    private DocumentSearchView tagPage(String name) {
        viewNavigators.view(UiTestUtils.getCurrentView(), DocumentSearchView.class)
                .withQueryParameters(QueryParameters.of(DocumentSearchView.TAG_PARAMETER, name))
                .navigate();

        return UiTestUtils.getCurrentView();
    }

    private Document changedAt(Document document, int daysAgo) {
        Document stored = unconstrainedDataManager.load(Id.of(document)).one();
        stored.setChangedDate(OffsetDateTime.now().minusDays(daysAgo));
        return unconstrainedDataManager.save(stored);
    }

    private Folder secretFolder() {
        return systemAuthenticator.withUser(ADMIN, () -> {
            Folder folder = dataManager.create(Folder.class);
            folder.setParent(sandbox);
            folder.setName("secret");
            return TestAccess.giveOwnAccess(dataManager, folderService.save(folder),
                    Grant.user(COLLEAGUE, AccessLevel.EDIT));
        });
    }

    private DocumentSearchView search(String query) {
        viewNavigators.view(UiTestUtils.getCurrentView(), DocumentSearchView.class)
                .withQueryParameters(QueryParameters.of(DocumentSearchView.QUERY_PARAMETER, query))
                .navigate();

        return UiTestUtils.getCurrentView();
    }

    private void answer(SearchResultEntry... entries) {
        SearchResult result = mock(SearchResult.class);
        when(result.getAllEntries()).thenReturn(List.of(entries));
        when(entitySearcher.search(any(), anyString())).thenReturn(result);
    }

    private SearchResultEntry entry(Document document, String highlights) {
        return new SearchResultEntry(idSerialization.idToString(Id.of(document)), document.getName(), "Document",
                List.of(new FieldHit(FILE_TEXT, highlights)));
    }

    private static List<Component> rows(DocumentSearchView view) {
        return UiTestUtils.<VerticalLayout>getComponent(view, "hitsBox").getChildren().toList();
    }

    private static String message(DocumentSearchView view) {
        Span message = UiTestUtils.getComponent(view, "messageText");
        assertThat(message.isVisible()).isTrue();

        return message.getText();
    }

    @SuppressWarnings("unchecked")
    private <C extends Component> C byClass(Component root, String className) {
        return (C) UiComponentUtils.getComponents(root).stream()
                .filter(component -> component.getClassNames().contains(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + className));
    }

    private Document upload() {
        return upload("contract.txt");
    }

    private Document upload(String fileName) {
        return upload(sandbox, fileName);
    }

    private Document upload(Folder folder, String fileName) {
        return systemAuthenticator.withUser(ADMIN, () ->
                documentService.upload(folder, List.of(TestFiles.file(tempDir, fileName, "text")))
                        .created()
                        .getFirst());
    }

    private List<Document> uploadMoreThanAPage() {
        return systemAuthenticator.withUser(ADMIN, () ->
                documentService.upload(sandbox, IntStream.range(0, DocumentSearch.PAGE_SIZE + 1)
                                .mapToObj(number -> TestFiles.file(tempDir, "doc-" + number + ".txt", "text"))
                                .toList())
                        .created());
    }
}
