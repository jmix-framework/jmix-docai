package com.company.dms.tag;

import com.company.dms.app.service.OperationRejectedException;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.app.service.tag.TagService;
import com.company.dms.app.service.trash.TrashService;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentStatus;
import com.company.dms.model.folder.Folder;
import com.company.dms.model.tag.Tag;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import com.company.dms.test_support.TestFiles;
import com.company.dms.test_support.TestTrash;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.querycondition.PropertyCondition;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A tag is deleted for good and only when no document carries it, a document in the trash included: the tag service
 * refuses the rest, and the foreign key of the link table stops what goes past it. The documents keep their tags
 * through the trash and through the changes that do not touch the tags.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class TagDeletionTest {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private TrashService trashService;

    @Autowired
    private TagService tagService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @TempDir
    private Path tempDir;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private final List<Tag> created = new ArrayList<>();

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("tag-deletion-test-" + suffix);
        sandbox = folderService.save(folder);
    }

    // the tags of the sandbox go only after its documents are deleted forever
    @AfterEach
    void deleteSandboxAndTags() {
        folderService.delete(sandbox);
        trashService.purge(List.of(TestTrash.itemOf(trashService.loadItems(), sandbox.getId())));
        created.forEach(tag -> dataManager.load(Id.of(tag)).optional().ifPresent(dataManager::remove));
    }

    @Test
    void aTagOnADocumentIsNotDeleted() {
        Tag lease = tag("Lease " + suffix);
        tagged(upload("a.txt"), lease);

        assertThatThrownBy(() -> tagService.delete(lease))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessageContaining("Lease " + suffix);
        assertThat(dataManager.load(Id.of(lease)).optional()).isPresent();
    }

    @Test
    void aTagOnADocumentRemovedPastTheServiceStaysWithItsLinks() {
        Tag lease = tag("Lease " + suffix);
        Document document = tagged(upload("a.txt"), lease);

        assertThatThrownBy(() -> dataManager.remove(lease))
                .hasStackTraceContaining("FK_DOCUMENT_TAG_LINK_ON_TAG");
        assertThat(dataManager.load(Id.of(lease)).optional()).isPresent();
        assertThat(tagsOf(document)).containsExactly(lease);
    }

    @Test
    void aTagOnADocumentInTheTrashIsNotDeleted() {
        Tag lease = tag("Lease " + suffix);
        Document document = tagged(upload("a.txt"), lease);
        documentService.delete(List.of(document));

        assertThatThrownBy(() -> tagService.delete(lease))
                .isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void anUnusedTagIsDeleted() {
        Tag lease = tag("Lease " + suffix);

        tagService.delete(lease);

        assertThat(dataManager.load(Id.of(lease)).optional()).isEmpty();
    }

    @Test
    void aDocumentDeletedForeverTakesItsLinksAndTheTagCanGoThen() {
        Tag lease = tag("Lease " + suffix);
        Document document = tagged(upload("a.txt"), lease);
        documentService.delete(List.of(document));

        trashService.purge(List.of(TestTrash.itemOf(trashService.loadItems(), document.getId())));
        tagService.delete(lease);

        assertThat(TestTrash.exists(dataManager, Document.class, document.getId())).isFalse();
        assertThat(dataManager.load(Id.of(lease)).optional()).isEmpty();
    }

    @Test
    void aDocumentRestoredFromTheTrashComesBackWithItsTags() {
        Tag lease = tag("Lease " + suffix);
        Tag office = tag("Office " + suffix);
        Document document = tagged(upload("a.txt"), lease, office);
        documentService.delete(List.of(document));

        trashService.restore(List.of(TestTrash.itemOf(trashService.loadItems(), document.getId())));

        assertThat(tagsOf(document)).containsExactly(lease, office);
    }

    @Test
    void aMoveAStatusChangeAndANewVersionKeepTheTags() {
        Tag lease = tag("Lease " + suffix);
        Document document = tagged(upload("a.txt"), lease);
        Folder target = dataManager.create(Folder.class);
        target.setParent(sandbox);
        target.setName("Target");
        target = folderService.save(target);

        documentService.move(List.of(document), target);
        documentService.changeStatus(List.of(document), DocumentStatus.ACTIVE);
        documentService.uploadRevision(document, TestFiles.file(tempDir, "a-v2.txt", "second"));

        assertThat(tagsOf(document)).containsExactly(lease);
    }

    private Tag tag(String name) {
        Tag tag = dataManager.create(Tag.class);
        tag.setName(name);
        Tag saved = dataManager.save(tag);
        created.add(saved);
        return saved;
    }

    private Document tagged(Document document, Tag... tags) {
        Document stored = dataManager.load(Id.of(document))
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("tags", FetchPlan.BASE))
                .one();
        stored.setTags(new ArrayList<>(List.of(tags)));
        return dataManager.save(stored);
    }

    private List<Tag> tagsOf(Document document) {
        return unconstrainedDataManager.load(Document.class)
                .condition(PropertyCondition.equal("id", document.getId()))
                .fetchPlan(fetchPlan -> fetchPlan.add("tags", FetchPlan.BASE))
                .one()
                .getTags();
    }

    private Document upload(String fileName) {
        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, fileName, "text")))
                .created().getFirst();
    }
}
