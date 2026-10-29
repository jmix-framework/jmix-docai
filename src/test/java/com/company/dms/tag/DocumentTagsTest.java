package com.company.dms.tag;

import com.company.dms.app.service.OperationRejectedException;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.app.service.trash.TrashService;
import com.company.dms.model.access.AccessLevel;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentStatus;
import com.company.dms.model.folder.Folder;
import com.company.dms.model.tag.Tag;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import com.company.dms.test_support.TestAccess;
import com.company.dms.test_support.TestFiles;
import com.company.dms.test_support.TestTrash;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.Metadata;
import io.jmix.core.security.AccessDeniedException;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tags of a document as the card saves them: whoever may change the document sets its tags, a new tag goes into
 * the vocabulary with the document, and only the administrator renames or deletes a tag.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class DocumentTagsTest {

    private static final String EMPLOYEE = "peter";
    private static final OffsetDateTime LONG_AGO = OffsetDateTime.of(2020, 1, 15, 9, 30, 0, 0, ZoneOffset.UTC);

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private TrashService trashService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private Metadata metadata;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @TempDir
    private Path tempDir;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("document-tags-test-" + suffix);
        sandbox = folderService.save(folder);
    }

    // the tags of the sandbox go only after its documents are deleted forever
    @AfterEach
    void deleteSandboxAndTags() {
        folderService.delete(sandbox);
        trashService.purge(List.of(TestTrash.itemOf(trashService.loadItems(), sandbox.getId())));
        dataManager.load(Tag.class)
                .query("select t from Tag t where t.name like :suffix")
                .parameter("suffix", "%" + suffix)
                .list()
                .forEach(dataManager::remove);
    }

    @Test
    void anEmployeeWhoMayChangeTheDocumentSetsItsTagsAndANewTagJoinsTheVocabulary() {
        Tag lease = tag("Lease " + suffix);
        Document document = upload("a.txt");

        systemAuthenticator.runWithUser(EMPLOYEE, () -> {
            Document edited = editable(document);
            edited.setTags(new ArrayList<>(List.of(lease, newTag("Office " + suffix))));
            documentService.save(edited, null, null);
        });

        assertThat(names(tagsOf(document))).containsExactly("Lease " + suffix, "Office " + suffix);
        assertThat(vocabulary()).containsExactly("Lease " + suffix, "Office " + suffix);
    }

    @Test
    void anEmployeeWhoOnlyViewsTheDocumentCannotChangeItsTags() {
        Tag lease = tag("Lease " + suffix);
        Document document = TestAccess.giveOwnAccess(dataManager, upload("a.txt"),
                TestAccess.Grant.user(EMPLOYEE, AccessLevel.VIEW));

        assertThatThrownBy(() -> systemAuthenticator.runWithUser(EMPLOYEE, () -> {
            Document edited = editable(document);
            edited.setTags(new ArrayList<>(List.of(lease)));
            documentService.save(edited, null, null);
        })).isInstanceOf(OperationRejectedException.class);

        assertThat(tagsOf(document)).isEmpty();
    }

    @Test
    void anEmployeeNeitherRenamesNorDeletesATag() {
        Tag lease = tag("Lease " + suffix);

        assertThatThrownBy(() -> systemAuthenticator.runWithUser(EMPLOYEE, () -> {
            Tag renamed = dataManager.load(Id.of(lease)).one();
            renamed.setName("Rent " + suffix);
            dataManager.save(renamed);
        })).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> systemAuthenticator.runWithUser(EMPLOYEE, () -> dataManager.remove(lease)))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(dataManager.load(Id.of(lease)).one().getName()).isEqualTo("Lease " + suffix);
    }

    @Test
    void theTagsOfAnArchivedDocumentChangeAndItsStampStays() {
        Tag lease = tag("Lease " + suffix);
        Document document = upload("a.txt");
        documentService.changeStatus(List.of(document), DocumentStatus.ACTIVE);
        documentService.changeStatus(List.of(document), DocumentStatus.ARCHIVED);
        stampedLongAgo(document);

        systemAuthenticator.runWithUser(EMPLOYEE, () -> {
            Document edited = editable(document);
            edited.setTags(new ArrayList<>(List.of(lease)));
            documentService.save(edited, null, null);
        });

        Document stored = dataManager.load(Id.of(document)).one();
        assertThat(tagsOf(document)).containsExactly(lease);
        assertThat(stored.getStatus()).isEqualTo(DocumentStatus.ARCHIVED);
        assertThat(stored.getChangedDate()).isAtSameInstantAs(LONG_AGO);
    }

    @Test
    void aNewTagNamedMeanwhileInTheVocabularyBecomesThatTag() {
        Document document = upload("a.txt");
        Document edited = editable(document);
        edited.setTags(new ArrayList<>(List.of(newTag("lease " + suffix))));
        Tag lease = tag("Lease " + suffix);

        documentService.save(edited, null, null);

        assertThat(tagsOf(document)).containsExactly(lease);
        assertThat(vocabulary()).containsExactly("Lease " + suffix);
    }

    @Test
    void aTagDeletedMeanwhileDropsOutAndATagTwiceIsKeptOnce() {
        Tag gone = tag("Gone " + suffix);
        Document document = upload("a.txt");
        Document edited = editable(document);
        edited.setTags(new ArrayList<>(List.of(gone, newTag("Office " + suffix), newTag("OFFICE " + suffix))));
        dataManager.remove(gone);

        documentService.save(edited, null, null);

        assertThat(names(tagsOf(document))).containsExactly("Office " + suffix);
        assertThat(vocabulary()).containsExactly("Office " + suffix);
    }

    @Test
    void theTagsOfADocumentComeInAlphabeticalOrderWhateverTheCase() {
        Document document = upload("a.txt");
        Document edited = editable(document);
        edited.setTags(new ArrayList<>(List.of(tag("Office " + suffix), tag("lease " + suffix))));

        documentService.save(edited, null, null);

        assertThat(names(tagsOf(document))).containsExactly("lease " + suffix, "Office " + suffix);
    }

    @Test
    void aNewDocumentComesWithItsTags() {
        Tag lease = tag("Lease " + suffix);
        Document draft = dataManager.create(Document.class);
        draft.setFolder(sandbox);
        draft.setName("b.txt");
        draft.setTags(new ArrayList<>(List.of(lease, newTag("Office " + suffix))));

        Document document = systemAuthenticator.withUser(EMPLOYEE, () ->
                documentService.create(draft, TestFiles.file(tempDir, "b.txt", "text"), null));

        assertThat(names(tagsOf(document))).containsExactly("Lease " + suffix, "Office " + suffix);
    }

    private Tag tag(String name) {
        Tag tag = dataManager.create(Tag.class);
        tag.setName(name);
        return dataManager.save(tag);
    }

    // a tag typed in the card: not in the vocabulary until the document is saved
    private Tag newTag(String name) {
        Tag tag = metadata.create(Tag.class);
        tag.setName(name);
        return tag;
    }

    private void stampedLongAgo(Document document) {
        Document stored = dataManager.load(Id.of(document)).one();
        stored.setChangedBy("maria");
        stored.setChangedDate(LONG_AGO);
        dataManager.save(stored);
    }

    // the document as the card loads it
    private Document editable(Document document) {
        return dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("folder", FetchPlan.BASE)
                        .add("tags", FetchPlan.BASE))
                .one();
    }

    private List<Tag> tagsOf(Document document) {
        return dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.add("tags", FetchPlan.BASE))
                .one()
                .getTags();
    }

    private List<String> vocabulary() {
        return names(dataManager.load(Tag.class)
                .query("select t from Tag t where t.name like :suffix order by t.name")
                .parameter("suffix", "%" + suffix)
                .list());
    }

    private static List<String> names(List<Tag> tags) {
        return tags.stream()
                .map(Tag::getName)
                .toList();
    }

    private Document upload(String fileName) {
        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, fileName, "text")))
                .created().getFirst();
    }
}
