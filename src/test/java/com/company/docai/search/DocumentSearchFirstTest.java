package com.company.docai.search;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import com.company.docai.test_support.TestAccess;
import com.company.docai.test_support.TestAccess.Grant;
import com.company.docai.test_support.TestFiles;
import io.jmix.core.DataManager;
import io.jmix.core.EntityStates;
import io.jmix.core.Id;
import io.jmix.core.IdSerialization;
import io.jmix.core.MessageTools;
import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.search.searching.EntitySearcher;
import io.jmix.search.searching.FieldHit;
import io.jmix.search.searching.SearchContext;
import io.jmix.search.searching.SearchResult;
import io.jmix.search.searching.SearchResultEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The first documents found by the beginnings of their words, as the search palette lists them. The index is
 * replaced and answers with the documents of the test; the context is the one of the UI tests that replace it, so
 * that no new context joins the cache.
 */
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
@ExtendWith(AuthenticatedAsAdmin.class)
class DocumentSearchFirstTest {

    private static final String EMPLOYEE = "peter";
    private static final String COLLEAGUE = "maria";
    private static final String FILE_TEXT = "currentRevision.file._content";

    @MockitoBean
    private EntitySearcher entitySearcher;

    @Autowired
    private DocumentSearch documentSearch;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private EntityStates entityStates;

    @Autowired
    private IdSerialization idSerialization;

    @Autowired
    private MessageTools messageTools;

    @Autowired
    private Metadata metadata;

    @Autowired
    private MetadataTools metadataTools;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        sandbox = folder(folderService.getRoot(), "document-search-first-test-" + UUID.randomUUID());
    }

    @AfterEach
    void deleteSandbox() {
        dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete);
    }

    @Test
    void oneRequestByTheBeginningsOfTheWordsGivesTheFirstDocumentsInTheOrderOfTheIndex() {
        Document first = upload(sandbox, "lease-1.txt");
        Document second = upload(sandbox, "lease-2.txt");
        Document third = upload(sandbox, "lease-3.txt");

        answer(entry(third, FILE_TEXT, "a <b>lease</b>"), entry(first, FILE_TEXT, "a <b>lease</b>"),
                entry(second, FILE_TEXT, "a <b>lease</b>"));

        List<DocumentSearchHit> hits = documentSearch.first("lea", 2);

        assertThat(hits).extracting(DocumentSearchHit::document).containsExactly(third, first);
        assertThat(hits.getFirst().folderPath())
                .containsExactly(metadataTools.getInstanceName(folderService.getRoot()), sandbox.getName());
        // the palette shows the icon of each file: a revision left out of the fetch plan would load row by row
        assertThat(hits).allMatch(hit -> entityStates.isLoaded(hit.document(), "currentRevision"));

        ArgumentCaptor<SearchContext> context = ArgumentCaptor.forClass(SearchContext.class);
        verify(entitySearcher).search(context.capture(), eq("startsWith"));
        assertThat(context.getValue().getSearchText()).isEqualTo("lea");
        // the slack of a page: a document the user may not see leaves its place to the next one
        assertThat(context.getValue().getSize()).isEqualTo(DocumentSearch.PAGE_SIZE + 1);
        verify(entitySearcher, never()).searchNextPage(any());
    }

    @Test
    void aDocumentDeletedOrHiddenSinceTheIndexSawItLeavesItsPlaceToTheNextOne() {
        Document deleted = upload(sandbox, "lease-1.txt");
        Document hidden = upload(secretFolder(), "lease-2.txt");
        Document kept = upload(sandbox, "lease-3.txt");
        documentService.delete(List.of(deleted));

        answer(entry(deleted, FILE_TEXT, "<b>lease</b>"), entry(hidden, FILE_TEXT, "<b>lease</b>"),
                entry(kept, FILE_TEXT, "<b>lease</b>"));

        List<DocumentSearchHit> hits = systemAuthenticator.withUser(EMPLOYEE, () -> documentSearch.first("lea", 1));

        assertThat(hits).extracting(DocumentSearchHit::document).containsExactly(kept);
    }

    @Test
    void theNameIsMarkedFromItsOwnFieldOrItsBeginningsAndAnotherFieldMarksNothing() {
        Document byBeginning = upload(sandbox, "Lease agreement.txt");
        Document byName = upload(sandbox, "Lease plan.txt");
        Document byText = upload(sandbox, "Notes.txt");

        answer(entry(byBeginning, "name.prefix", "<b>Lease</b> agreement.txt"),
                new SearchResultEntry(idOf(byName), byName.getName(), "Document", List.of(
                        new FieldHit(FILE_TEXT, "the <b>lease</b>"),
                        new FieldHit("name", "<b>Lease</b> plan.txt"))),
                entry(byText, FILE_TEXT, "the <b>lease</b>"));

        List<DocumentSearchHit> hits = documentSearch.first("lea", 3);

        String name = messageTools.getPropertyCaption(metadata.getClass(Document.class), "name");
        assertThat(hits).extracting(DocumentSearchHit::field, DocumentSearchHit::snippet).containsExactly(
                tuple(name, "<b>Lease</b> agreement.txt"),
                tuple(name, "<b>Lease</b> plan.txt"),
                tuple(null, null));
    }

    @Test
    void wordsUnderThreeLettersAreLeftOutAndAloneFindNothing() {
        answer();

        assertThat(documentSearch.first("qz", 5)).isEmpty();
        verify(entitySearcher, never()).search(any(), anyString());

        documentSearch.first("a lea", 5);

        ArgumentCaptor<SearchContext> context = ArgumentCaptor.forClass(SearchContext.class);
        verify(entitySearcher).search(context.capture(), eq("startsWith"));
        assertThat(context.getValue().getSearchText()).isEqualTo("lea");
    }

    @Test
    void anIndexThatDoesNotAnswerIsSaidAsForThePage() {
        when(entitySearcher.search(any(), anyString())).thenThrow(new IllegalStateException("No index"));

        assertThatThrownBy(() -> documentSearch.first("lea", 5)).isInstanceOf(SearchUnavailableException.class);
    }

    private void answer(SearchResultEntry... entries) {
        SearchResult result = mock(SearchResult.class);
        when(result.getAllEntries()).thenReturn(List.of(entries));
        when(entitySearcher.search(any(), anyString())).thenReturn(result);
    }

    private SearchResultEntry entry(Document document, String field, String highlights) {
        return new SearchResultEntry(idOf(document), document.getName(), "Document",
                List.of(new FieldHit(field, highlights)));
    }

    private String idOf(Document document) {
        return idSerialization.idToString(Id.of(document));
    }

    private Folder secretFolder() {
        return TestAccess.giveOwnAccess(dataManager, folder(sandbox, "secret"),
                Grant.user(COLLEAGUE, AccessLevel.EDIT));
    }

    private Folder folder(Folder parent, String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(parent);
        folder.setName(name);

        return folderService.save(folder);
    }

    private Document upload(Folder folder, String fileName) {
        return documentService.upload(folder, List.of(TestFiles.file(tempDir, fileName, "text")))
                .created()
                .getFirst();
    }
}
