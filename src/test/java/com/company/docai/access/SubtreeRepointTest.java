package com.company.docai.access;

import com.company.docai.app.service.access.AccessService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.access.AccessDraft;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.access.AccessSet;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.security.role.EmployeeRole;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import com.company.docai.test_support.TestAccess;
import com.company.docai.test_support.TestAccess.Grant;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.SaveContext;
import io.jmix.data.PersistenceHints;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Breaking the inheritance of a big branch points everything in it that inherited, deleted items included, to the
 * new set in one save. The branch: 21 folders in three levels with 13 documents each; one leaf has its own access;
 * one second-level folder with its subtree and ten more documents are deleted. Next to the branch lies a folder
 * that must not change.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class SubtreeRepointTest {

    private static final Logger log = LoggerFactory.getLogger(SubtreeRepointTest.class);

    private static final int DOCUMENTS_PER_FOLDER = 13;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private FolderService folderService;

    @Autowired
    private AccessService accessService;

    private Folder sandbox;
    private AccessSet inherited;

    @BeforeEach
    void createSandbox() {
        sandbox = folder(folderService.getRoot(), "subtree-repoint-test-" + UUID.randomUUID());
        inherited = TestAccess.setOf(dataManager, sandbox);
    }

    @AfterEach
    void deleteSandbox() {
        folderService.delete(sandbox);
    }

    @Test
    void breakingTheInheritanceRepointsTheBranchWithItsDeletedItems() {
        Branch branch = createBranch("Branch");
        AccessDraft draft = accessService.describe(branch.top());
        draft.setInherit(false);

        long started = System.nanoTime();
        accessService.save(draft);
        log.info("Repointed {} items in {} ms", branch.inheriting(), (System.nanoTime() - started) / 1_000_000);

        AccessSet own = TestAccess.setOf(dataManager, branch.top());
        assertThat(own).isNotEqualTo(inherited);
        assertThat(countWithSet(own)).isEqualTo(branch.inheriting());
        assertThat(countWithSet(branch.ownSet())).isEqualTo(1 + DOCUMENTS_PER_FOLDER);
        assertThat(countDocumentsWithSet(inherited, branch.neighbour())).isEqualTo(DOCUMENTS_PER_FOLDER);
    }

    /**
     * Builds the branch and its neighbour under the sandbox. The first leaf gets its own access, the last
     * second-level folder is deleted with its subtree, and so are ten documents of the first one.
     */
    private Branch createBranch(String name) {
        Folder top = newFolder(sandbox, name);
        List<Folder> middle = new ArrayList<>();
        List<Folder> leaves = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            Folder child = newFolder(top, "Level 1-" + i);
            middle.add(child);
            for (int j = 1; j <= 4; j++) {
                leaves.add(newFolder(child, "Level 2-" + i + "-" + j));
            }
        }

        Folder neighbour = newFolder(sandbox, name + " neighbour");
        List<Folder> folders = new ArrayList<>(List.of(top));
        folders.addAll(middle);
        folders.addAll(leaves);
        List<Document> documents = new ArrayList<>();
        for (Folder folder : folders) {
            documents.addAll(newDocuments(folder));
        }

        dataManager.save(new SaveContext()
                .saving(folders, neighbour, documents, newDocuments(neighbour))
                .setDiscardSaved(true));

        Folder ownLeaf = leaves.getFirst();
        AccessSet ownSet = TestAccess.giveOwnAccess(dataManager, ownLeaf,
                        Grant.role(EmployeeRole.CODE, AccessLevel.EDIT))
                .getAccessSet();

        List<Document> ownLeafDocuments = documentsOf(documents, ownLeaf);
        ownLeafDocuments.forEach(document -> document.setAccessSet(ownSet));
        dataManager.save(new SaveContext()
                .saving(ownLeafDocuments)
                .setDiscardSaved(true));

        dataManager.remove(dataManager.load(Id.of(middle.getLast())).one());
        dataManager.save(new SaveContext()
                .removing(documentsOf(documents, middle.getFirst()).subList(0, 10))
                .setDiscardSaved(true));

        int inheriting = folders.size() - 1 + (folders.size() - 1) * DOCUMENTS_PER_FOLDER;
        return new Branch(top, neighbour, ownSet, inheriting);
    }

    private List<Document> documentsOf(List<Document> documents, Folder folder) {
        return documents.stream()
                .filter(document -> document.getFolder().equals(folder))
                .map(document -> dataManager.load(Id.of(document)).one())
                .toList();
    }

    private long countWithSet(AccessSet accessSet) {
        return count("select count(f) from Folder f where f.accessSet = :accessSet", accessSet)
                + count("select count(d) from Document d where d.accessSet = :accessSet", accessSet);
    }

    private long countDocumentsWithSet(AccessSet accessSet, Folder folder) {
        return dataManager.loadValue("select count(d) from Document d"
                        + " where d.accessSet = :accessSet and d.folder = :folder", Long.class)
                .parameter("accessSet", accessSet)
                .parameter("folder", folder)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one();
    }

    private long count(String query, AccessSet accessSet) {
        return dataManager.loadValue(query, Long.class)
                .parameter("accessSet", accessSet)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one();
    }

    private Folder newFolder(Folder parent, String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(parent);
        folder.setName(name);
        folder.setAccessSet(inherited);
        return folder;
    }

    private List<Document> newDocuments(Folder folder) {
        List<Document> documents = new ArrayList<>();
        for (int i = 1; i <= DOCUMENTS_PER_FOLDER; i++) {
            Document document = dataManager.create(Document.class);
            document.setFolder(folder);
            document.setName("document-" + i + ".txt");
            document.setAccessSet(inherited);
            documents.add(document);
        }

        return documents;
    }

    private Folder folder(Folder parent, String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(parent);
        folder.setName(name);
        return folderService.save(folder);
    }

    private record Branch(Folder top, Folder neighbour, AccessSet ownSet, int inheriting) {
    }
}
