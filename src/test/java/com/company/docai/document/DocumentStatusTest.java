package com.company.docai.document;

import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.document.DocumentStatusService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.service.trash.TrashService;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentStatus;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestStatuses;
import com.company.docai.test_support.TestTrash;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.UnconstrainedDataManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rules that the statuses of the dictionary give their documents, and the rules that keep the dictionary whole.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class DocumentStatusTest {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentStatusService statusService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private TrashService trashService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @TempDir
    private Path tempDir;

    private final List<DocumentStatus> created = new ArrayList<>();

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("document-status-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);
    }

    // the documents of the sandbox go for good, so that the statuses made here can be deleted; Draft is given to new
    // documents again whatever a test did
    @AfterEach
    void cleanUp() {
        folderService.delete(sandbox);
        trashService.purge(List.of(TestTrash.itemOf(trashService.loadItems(), sandbox.getId())));

        DocumentStatus draft = TestStatuses.draft(dataManager);
        if (!draft.getInitial()) {
            draft.setInitial(true);
            statusService.save(draft);
        }

        created.forEach(status -> statusService.delete(reload(status)));
    }

    @Test
    void theDefaultStatusesKeepTheLifecycleOfTheFormerEnum() {
        DocumentStatus draft = TestStatuses.draft(dataManager);
        DocumentStatus active = TestStatuses.active(dataManager);
        DocumentStatus archived = TestStatuses.archived(dataManager);

        assertThat(statusService.getInitial()).isEqualTo(draft);
        assertThat(draft.canMoveTo(active)).isTrue();
        assertThat(draft.canMoveTo(archived)).isFalse();
        assertThat(active.canMoveTo(archived)).isTrue();
        assertThat(active.canMoveTo(draft)).isFalse();
        assertThat(archived.canMoveTo(active)).isTrue();
        assertThat(archived.canMoveTo(draft)).isFalse();
        assertThat(archived.getLocked()).isTrue();
    }

    @Test
    void aNewDocumentGetsTheInitialStatus() {
        assertThat(upload("a.txt").getStatus()).isEqualTo(TestStatuses.draft(dataManager));
    }

    @Test
    void aDocumentMovesOnlyToAStatusItsStatusLists() {
        Document document = upload("a.txt");

        assertThatThrownBy(() -> documentService.changeStatus(List.of(document),
                TestStatuses.archived(dataManager)))
                .isInstanceOf(OperationRejectedException.class);

        documentService.changeStatus(List.of(document), TestStatuses.active(dataManager));
        assertThat(reload(document).getStatus()).isEqualTo(TestStatuses.active(dataManager));
    }

    @Test
    void aStatusThatMovesToAnyStatusLetsTheDocumentGoAnywhere() {
        DocumentStatus review = status("Review", false, List.of());
        DocumentStatus draft = TestStatuses.draft(dataManager);
        draft.getTargets().add(review);
        statusService.save(draft);

        try {
            Document document = upload("a.txt");
            documentService.changeStatus(List.of(document), review);
            documentService.changeStatus(List.of(document), TestStatuses.archived(dataManager));

            assertThat(reload(document).getStatus()).isEqualTo(TestStatuses.archived(dataManager));
        } finally {
            DocumentStatus restored = TestStatuses.draft(dataManager);
            restored.getTargets().remove(review);
            statusService.save(restored);
        }
    }

    @Test
    void aLockedStatusKeepsTheContentAndLetsTheDocumentMoveOn() {
        Document document = upload("a.txt");
        documentService.changeStatus(List.of(document), TestStatuses.active(dataManager));
        documentService.changeStatus(List.of(document), TestStatuses.archived(dataManager));

        Document archived = reload(document);
        archived.setName("b.txt");

        assertThatThrownBy(() -> documentService.save(archived, null, null))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessageContaining(TestStatuses.archived(dataManager).getName());

        documentService.changeStatus(List.of(document), TestStatuses.active(dataManager));
        Document active = reload(document);
        active.setName("b.txt");

        assertThat(documentService.save(active, null, null).getName()).isEqualTo("b.txt");
    }

    @Test
    void anotherStatusMadeInitialTakesTheFlagOver() {
        DocumentStatus first = status("First", false, List.of());
        first.setInitial(true);
        statusService.save(first);

        assertThat(statusService.getInitial()).isEqualTo(first);
        assertThat(TestStatuses.draft(dataManager).getInitial()).isFalse();
        assertThat(upload("a.txt").getStatus()).isEqualTo(first);
    }

    // the first version of a new document is a change that a locked status would refuse
    @Test
    void aStatusGivenToNewDocumentsDoesNotLockThem() {
        DocumentStatus locked = status("Locked", true, List.of());
        locked.setInitial(true);

        assertThatThrownBy(() -> statusService.save(locked))
                .isInstanceOf(OperationRejectedException.class);

        DocumentStatus first = status("First", false, List.of());
        first.setInitial(true);
        DocumentStatus initial = statusService.save(first);
        initial.setLocked(true);

        assertThatThrownBy(() -> statusService.save(initial))
                .isInstanceOf(OperationRejectedException.class);
        assertThat(upload("a.txt").getStatus()).isEqualTo(first);
    }

    // a card or the table may still offer a status that the administrator has deleted meanwhile
    @Test
    void aStatusDeletedMeanwhileIsRefusedByItsName() {
        DocumentStatus any = status("Any", false, List.of());
        DocumentStatus gone = status("Gone", false, List.of());
        DocumentStatus draft = TestStatuses.draft(dataManager);
        draft.getTargets().add(gone);
        draft.getTargets().add(any);
        statusService.save(draft);

        try {
            Document document = upload("a.txt");
            statusService.delete(reload(gone));
            created.remove(gone);

            assertThatThrownBy(() -> documentService.changeStatus(List.of(document), gone))
                    .isInstanceOf(OperationRejectedException.class)
                    .hasMessageContaining(gone.getName());

            DocumentStatus later = status("Later", false, List.of());
            documentService.changeStatus(List.of(document), any);
            statusService.delete(reload(later));
            created.remove(later);

            // the status the document is in moves to any status, so only the check of the target can refuse
            assertThatThrownBy(() -> documentService.changeStatus(List.of(document), later))
                    .isInstanceOf(OperationRejectedException.class)
                    .hasMessageContaining(later.getName());
        } finally {
            DocumentStatus restored = TestStatuses.draft(dataManager);
            restored.getTargets().remove(any);
            statusService.save(restored);
        }
    }

    // the dialog of a status still offers a status that another administrator has deleted meanwhile
    @Test
    void aTargetDeletedMeanwhileIsRefusedByItsName() {
        DocumentStatus gone = status("Gone", false, List.of());
        DocumentStatus edited = TestStatuses.draft(dataManager);
        edited.getTargets().add(gone);
        statusService.delete(reload(gone));
        created.remove(gone);

        assertThatThrownBy(() -> statusService.save(edited))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessageContaining(gone.getName());
        assertThat(TestStatuses.draft(dataManager).getTargets()).containsExactly(TestStatuses.active(dataManager));
    }

    // a list keeps the copy it loaded, from before another status took the flag over
    @Test
    void aStatusThatGaveTheFlagUpIsDeletedFromACopyLoadedBefore() {
        DocumentStatus first = status("First", false, List.of());
        first.setInitial(true);
        DocumentStatus loadedBefore = statusService.save(first);
        DocumentStatus second = status("Second", false, List.of());
        second.setInitial(true);
        statusService.save(second);

        statusService.delete(loadedBefore);
        created.remove(first);

        assertThat(statusService.find(first.getName())).isEmpty();
    }

    @Test
    void theOnlyInitialStatusCannotGiveTheFlagUp() {
        DocumentStatus draft = TestStatuses.draft(dataManager);
        draft.setInitial(false);

        assertThatThrownBy(() -> statusService.save(draft))
                .isInstanceOf(OperationRejectedException.class);
        assertThat(statusService.getInitial()).isEqualTo(TestStatuses.draft(dataManager));
    }

    @Test
    void aStatusThatADocumentHasIsNotDeletedEvenFromTheTrash() {
        DocumentStatus review = status("Review", false, List.of());
        DocumentStatus draft = TestStatuses.draft(dataManager);
        draft.getTargets().add(review);
        statusService.save(draft);

        try {
            Document document = upload("a.txt");
            documentService.changeStatus(List.of(document), review);

            assertThatThrownBy(() -> statusService.delete(reload(review)))
                    .isInstanceOf(OperationRejectedException.class)
                    .hasMessageContaining(review.getName());

            documentService.delete(List.of(reload(document)));

            assertThatThrownBy(() -> statusService.delete(reload(review)))
                    .isInstanceOf(OperationRejectedException.class);

            // a document deleted on its own is an item of the trash of its own, outside the sandbox
            trashService.purge(List.of(TestTrash.itemOf(trashService.loadItems(), document.getId())));
        } finally {
            DocumentStatus restored = TestStatuses.draft(dataManager);
            restored.getTargets().remove(review);
            statusService.save(restored);
        }
    }

    @Test
    void theInitialStatusIsNotDeleted() {
        assertThatThrownBy(() -> statusService.delete(TestStatuses.draft(dataManager)))
                .isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void aDeletedStatusLeavesTheListsOfTheOthers() {
        DocumentStatus review = status("Review", false, List.of());
        DocumentStatus draft = TestStatuses.draft(dataManager);
        draft.getTargets().add(review);
        statusService.save(draft);

        statusService.delete(reload(review));
        created.remove(review);

        assertThat(TestStatuses.draft(dataManager).getTargets()).containsExactly(TestStatuses.active(dataManager));
    }

    @Test
    void aNameIsUniqueWhateverItsCaseAndAColorIsHex() {
        DocumentStatus namesake = unconstrainedDataManager.create(DocumentStatus.class);
        namesake.setName("DRAFT");
        namesake.setColor("#3266e4");
        namesake.setPosition(100);

        assertThatThrownBy(() -> statusService.save(namesake))
                .isInstanceOf(OperationRejectedException.class);

        DocumentStatus green = unconstrainedDataManager.create(DocumentStatus.class);
        green.setName("Green " + UUID.randomUUID().toString().substring(0, 8));
        green.setColor("green");
        green.setPosition(100);

        assertThatThrownBy(() -> statusService.save(green))
                .isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void aStatusDoesNotListItself() {
        DocumentStatus review = status("Review", false, List.of());
        DocumentStatus edited = reload(review);
        edited.setAnyTarget(false);
        edited.getTargets().add(edited);

        assertThat(statusService.save(edited).getTargets()).isEmpty();
    }

    // the statuses are numbered from one again, so the order stays whole whatever a drag did before
    @Test
    void aStatusMovedAboveAnotherTakesItsPlaceInTheOrder() {
        DocumentStatus first = status("First", false, List.of());
        DocumentStatus second = status("Second", false, List.of());

        statusService.move(second, first, false);

        List<DocumentStatus> order = statusService.loadAll();
        assertThat(order.indexOf(second)).isLessThan(order.indexOf(first));
        assertThat(order).containsSubsequence(TestStatuses.draft(dataManager), TestStatuses.active(dataManager),
                TestStatuses.archived(dataManager));
        assertThat(order.stream().map(DocumentStatus::getPosition).toList())
                .isEqualTo(IntStream.rangeClosed(1, order.size()).boxed().toList());
    }

    @Test
    void aStatusMovedBelowAnotherFollowsIt() {
        DocumentStatus first = status("First", false, List.of());
        DocumentStatus second = status("Second", false, List.of());

        statusService.move(first, second, true);

        List<DocumentStatus> order = statusService.loadAll();
        assertThat(order.indexOf(first)).isEqualTo(order.indexOf(second) + 1);
    }

    private DocumentStatus status(String prefix, boolean locked, List<DocumentStatus> targets) {
        DocumentStatus status = TestStatuses.create(unconstrainedDataManager, prefix, locked, targets);
        created.add(status);
        return status;
    }

    private DocumentStatus reload(DocumentStatus status) {
        return dataManager.load(DocumentStatus.class)
                .id(status.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("targets", FetchPlan.BASE))
                .one();
    }

    private Document reload(Document document) {
        return dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("folder", FetchPlan.BASE)
                        .add("status", FetchPlan.BASE))
                .one();
    }

    private Document upload(String fileName) {
        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, fileName, "text")))
                .created()
                .getFirst();
    }
}
