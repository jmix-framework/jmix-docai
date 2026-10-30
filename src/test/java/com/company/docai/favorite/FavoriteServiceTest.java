package com.company.docai.favorite;

import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.favorite.FavoriteService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.access.AccessEntry;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.document.Document;
import com.company.docai.model.favorite.Favorite;
import com.company.docai.model.favorite.FavoriteItem;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.user.User;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import com.company.docai.test_support.TestAccess;
import com.company.docai.test_support.TestFiles;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.SaveContext;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.core.usersubstitution.UserSubstitutionManager;
import io.jmix.securitydata.entity.UserSubstitutionEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class FavoriteServiceTest {

    private static final String PETER = "peter";
    private static final String MARIA = "maria";

    @Autowired
    private FavoriteService favoriteService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private UserSubstitutionManager userSubstitutionManager;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        sandbox = folder(folderService.getRoot(), "favorite-service-test-" + UUID.randomUUID());
    }

    // no other test keeps favorites, so the marks of every user go
    @AfterEach
    void deleteSandbox() {
        unconstrainedDataManager.save(new SaveContext()
                .removing(unconstrainedDataManager.load(Favorite.class).all().list()));
        folderService.delete(sandbox);
    }

    @Test
    void aUserMarksAFolderAndADocumentOnce() {
        Folder contracts = folder(sandbox, "Contracts");
        Document lease = upload(contracts, "lease.txt");

        as(PETER, () -> favoriteService.mark(List.of(contracts, lease)));
        as(PETER, () -> favoriteService.mark(List.of(contracts, lease)));

        assertThat(as(PETER, () -> favoriteService.markedIds(List.of(contracts, lease))))
                .containsExactlyInAnyOrder(contracts.getId(), lease.getId());
        assertThat(unconstrainedDataManager.load(Favorite.class).all().list()).hasSize(2);
    }

    @Test
    void theBlockListsTheFoldersThenTheDocumentsAlphabeticallyWithTheirPlaces() {
        Folder contracts = folder(sandbox, "Contracts");
        Folder archive = folder(contracts, "archive");
        Document lease = upload(contracts, "lease.txt");
        Document act = upload(sandbox, "Act.txt");

        as(PETER, () -> favoriteService.mark(List.of(lease, sandbox, contracts, act, archive)));

        assertThat(as(PETER, favoriteService::loadItems))
                .extracting(FavoriteItem::getName, FavoriteItem::getLocation)
                .containsExactly(
                        tuple("archive", "Contracts"),
                        tuple("Contracts", sandbox.getName()),
                        tuple(sandbox.getName(), null),
                        tuple("Act.txt", sandbox.getName()),
                        tuple("lease.txt", "Contracts"));
    }

    // the order of the language of the user: a letter with a diacritic goes with its base letter, not after z
    @Test
    void theBlockListsTheNamesInTheOrderOfTheLanguage() {
        Folder zebra = folder(sandbox, "Zebra");
        Folder apples = folder(sandbox, "Äpfel");

        as(PETER, () -> favoriteService.mark(List.of(zebra, apples)));

        assertThat(as(PETER, favoriteService::loadItems))
                .extracting(FavoriteItem::getName)
                .containsExactly("Äpfel", "Zebra");
    }

    @Test
    void onlyTheOwnerSeesTheMarksTheAdministratorToo() {
        Folder contracts = folder(sandbox, "Contracts");

        as(PETER, () -> favoriteService.mark(List.of(contracts)));

        assertThat(itemIds(PETER)).containsExactly(contracts.getId());
        assertThat(itemIds(MARIA)).isEmpty();
        assertThat(favoriteService.loadItems()).isEmpty();
        assertThat(as(MARIA, () -> dataManager.load(Favorite.class).all().list())).isEmpty();
    }

    @Test
    void unmarkingRemovesOnlyTheOwnMark() {
        Folder contracts = folder(sandbox, "Contracts");
        as(PETER, () -> favoriteService.mark(List.of(contracts)));
        as(MARIA, () -> favoriteService.mark(List.of(contracts)));

        as(PETER, () -> favoriteService.unmark(List.of(contracts)));

        assertThat(itemIds(PETER)).isEmpty();
        assertThat(itemIds(MARIA)).containsExactly(contracts.getId());
    }

    @Test
    void aMarkIsRemovedAlsoWhenTheUserNoLongerReadsItsTarget() {
        Document memo = upload(sandbox, "memo.txt");
        as(PETER, () -> favoriteService.mark(List.of(memo)));
        TestAccess.giveOwnAccess(dataManager, memo, TestAccess.Grant.user(MARIA, AccessLevel.EDIT));

        as(PETER, () -> favoriteService.unmark(List.of(memo)));

        assertThat(unconstrainedDataManager.load(Favorite.class).all().list()).isEmpty();
    }

    @Test
    void aFolderTheUserMayNotReadIsNotMarked() {
        Folder closed = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "Closed"),
                TestAccess.Grant.user(MARIA, AccessLevel.EDIT));

        assertThatThrownBy(() -> as(PETER, () -> favoriteService.mark(List.of(closed))))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessageContaining("Closed");
    }

    @Test
    void aMarkHidesWhileTheAccessIsLostAndComesBackWithIt() {
        Document memo = upload(sandbox, "memo.txt");
        as(PETER, () -> favoriteService.mark(List.of(memo)));

        TestAccess.giveOwnAccess(dataManager, memo, TestAccess.Grant.user(MARIA, AccessLevel.EDIT));
        assertThat(itemIds(PETER)).isEmpty();

        AccessEntry entry = dataManager.create(AccessEntry.class);
        entry.setAccessSet(TestAccess.setOf(dataManager, memo));
        entry.setUsername(PETER);
        entry.setAccessLevel(AccessLevel.VIEW);
        dataManager.save(entry);
        assertThat(itemIds(PETER)).containsExactly(memo.getId());
    }

    @Test
    void aMarkNeedsExactlyOneTarget() {
        Favorite none = unconstrainedDataManager.create(Favorite.class);
        none.setUser(user(PETER));

        Favorite both = unconstrainedDataManager.create(Favorite.class);
        both.setUser(user(PETER));
        both.setFolder(sandbox);
        both.setDocument(upload(sandbox, "both.txt"));

        assertThatThrownBy(() -> unconstrainedDataManager.save(none))
                .hasStackTraceContaining("A favorite marks exactly one folder or document");
        assertThatThrownBy(() -> unconstrainedDataManager.save(both))
                .hasStackTraceContaining("A favorite marks exactly one folder or document");
    }

    @Test
    void theMarksOfADeletedUserGoWithTheUser() {
        User owner = unconstrainedDataManager.create(User.class);
        owner.setUsername("favorite-owner-" + UUID.randomUUID());
        User saved = unconstrainedDataManager.save(owner);

        Favorite mark = unconstrainedDataManager.create(Favorite.class);
        mark.setUser(saved);
        mark.setFolder(sandbox);
        Favorite savedMark = unconstrainedDataManager.save(mark);

        unconstrainedDataManager.remove(saved);

        assertThat(unconstrainedDataManager.load(Id.of(savedMark)).optional()).isEmpty();
    }

    @Test
    void underASubstitutionTheMarkBelongsToTheSubstitutedUser() {
        UserSubstitutionEntity substitution = unconstrainedDataManager.create(UserSubstitutionEntity.class);
        substitution.setUsername(PETER);
        substitution.setSubstitutedUsername(MARIA);
        UserSubstitutionEntity saved = unconstrainedDataManager.save(substitution);

        try {
            systemAuthenticator.runWithUser(PETER, () -> {
                userSubstitutionManager.substituteUser(MARIA);
                favoriteService.mark(List.of(sandbox));

                // the person reads the marks of the user they work as
                assertThat(favoriteService.loadItems())
                        .extracting(FavoriteItem::getId)
                        .containsExactly(sandbox.getId());
            });
        } finally {
            unconstrainedDataManager.remove(saved);
        }

        assertThat(itemIds(MARIA)).containsExactly(sandbox.getId());
        assertThat(itemIds(PETER)).isEmpty();
    }

    private List<UUID> itemIds(String username) {
        return as(username, () -> favoriteService.loadItems().stream()
                .map(FavoriteItem::getId)
                .toList());
    }

    private void as(String username, Runnable work) {
        systemAuthenticator.runWithUser(username, work);
    }

    private <T> T as(String username, Supplier<T> work) {
        return systemAuthenticator.withUser(username, work::get);
    }

    private User user(String username) {
        return unconstrainedDataManager.load(User.class)
                .query("select u from User u where u.username = :username")
                .parameter("username", username)
                .one();
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
