package com.company.docai.tag;

import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.service.tag.TagCount;
import com.company.docai.app.service.tag.TagService;
import com.company.docai.app.service.trash.TrashService;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.tag.Tag;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import com.company.docai.test_support.TestAccess;
import com.company.docai.test_support.TestAccess.Grant;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestTags;
import com.company.docai.test_support.TestTrash;
import io.jmix.core.DataManager;
import io.jmix.core.EntityStates;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The vocabulary of tags: a name is unique whatever its case, loses the spaces around it and has at most
 * {@link Tag#MAX_NAME_LENGTH} characters, on every save path.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class TagTest {

    private static final String EMPLOYEE = "peter";
    private static final String COLLEAGUE = "maria";

    @Autowired
    private TagService tagService;

    @Autowired
    private Messages messages;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private EntityStates entityStates;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private TrashService trashService;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @TempDir
    private Path tempDir;

    @Nullable
    private Folder sandbox;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private final List<Tag> created = new ArrayList<>();

    @AfterEach
    void deleteSandboxAndTags() {
        if (sandbox != null) {
            folderService.delete(sandbox);
            trashService.purge(List.of(TestTrash.itemOf(trashService.loadItems(), sandbox.getId())));
        }

        // the vocabulary rows go only after the documents that carry them have gone
        created.forEach(tag -> dataManager.load(Id.of(tag)).optional().ifPresent(dataManager::remove));
    }

    @Test
    void aTagNamedLikeAnotherWhateverTheCaseIsRefused() {
        save("Lease " + suffix);
        Tag office = save("Office " + suffix);

        assertThatThrownBy(() -> save("lease " + suffix))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessageContaining("Lease " + suffix);

        office.setName("LEASE " + suffix);

        assertThatThrownBy(() -> dataManager.save(office))
                .isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void aTagNamedLikeAnotherInAnotherCaseIsRefusedBeyondAsciiToo() {
        save("Übergabe " + suffix);

        assertThatThrownBy(() -> save("ÜBERGABE " + suffix))
                .isInstanceOf(OperationRejectedException.class);
        assertThat(tagService.find("übergabe " + suffix)).isPresent();
    }

    // a name is one line of text: a control character would break the lists and the prompts that show it
    @Test
    void aNameWithALineBreakOrAnotherControlCharacterIsRefused() {
        assertThatThrownBy(() -> save("Lease\n" + suffix))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessage(messages.getMessage(TagService.class, "tagNameControl"));
        assertThatThrownBy(() -> tagService.findOrCreate("Lease\u0007" + suffix))
                .isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void theNameLosesTheSpacesAroundIt() {
        Tag tag = save("  Office " + suffix + "  ");

        assertThat(dataManager.load(Id.of(tag)).one().getName()).isEqualTo("Office " + suffix);
    }

    @Test
    void aNameOfMoreThanFiftyCharactersIsRefused() {
        String fifty = suffix + "x".repeat(Tag.MAX_NAME_LENGTH - suffix.length());

        assertThat(save(fifty).getName()).hasSize(Tag.MAX_NAME_LENGTH);
        assertThatThrownBy(() -> save(fifty + "y"))
                .isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void anEmptyNameIsRefused() {
        assertThatThrownBy(() -> save("   "))
                .isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(() -> tagService.findOrCreate("   "))
                .isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void theVocabularyGivesItsTagWhateverTheCaseAndANewOneOtherwise() {
        Tag lease = save("Lease " + suffix);

        Tag found = tagService.findOrCreate("  LEASE " + suffix + " ");
        Tag fresh = tagService.findOrCreate(" Warehouse " + suffix + " ");

        assertThat(found).isEqualTo(lease);
        assertThat(entityStates.isNew(fresh)).isTrue();
        assertThat(fresh.getName()).isEqualTo("Warehouse " + suffix);
        assertThat(tagService.find("warehouse " + suffix)).isEmpty();
        assertThatThrownBy(() -> tagService.findOrCreate("x".repeat(Tag.MAX_NAME_LENGTH + 1)))
                .isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void aTagKeepsItsNameInLowerCaseAsTheKeyOfTheVocabulary() {
        Tag tag = save("Lease " + suffix);

        assertThat(keyOf(tag)).isEqualTo("lease " + suffix);

        Tag renamed = dataManager.load(Id.of(tag)).one();
        renamed.setName("SUPPLY " + suffix);
        dataManager.save(renamed);

        assertThat(keyOf(tag)).isEqualTo("supply " + suffix);
    }

    // two saves at once, or a write past the application, miss the check of the tag listener
    @Test
    void theDatabaseRefusesANamesakeWhateverTheCaseThatGoesPastTheRules() {
        save("Lease " + suffix);

        assertThatThrownBy(() -> new JdbcTemplate(dataSource).update(
                        "insert into TAG (ID, VERSION, NAME, NAME_KEY) values (?, 1, ?, ?)",
                        UUID.randomUUID(), "LEASE " + suffix, "lease " + suffix))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void theNamesOfTheVocabularyComeInAlphabeticalOrder() {
        save("Supply " + suffix);
        save("office " + suffix);
        save("Lease " + suffix);

        List<String> names = tagService.loadNames();

        assertThat(names).containsSubsequence("Lease " + suffix, "office " + suffix, "Supply " + suffix);
    }

    @Test
    void theTagsWithAPartOfTheNameComeByNameWithTheDocumentsTheUserMaySee() {
        sandbox = folder(folderService.getRoot(), "tag-test-" + suffix);

        Folder secret = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "secret"),
                Grant.user(COLLEAGUE, AccessLevel.EDIT));

        Document first = upload(sandbox, "a.txt");
        Document second = upload(sandbox, "b.txt");
        Document hidden = upload(secret, "c.txt");
        Document trashed = upload(sandbox, "d.txt");
        Tag lease = tag("Lease " + suffix);

        tagged(first, lease, tag("Sublease " + suffix), tag("50% " + suffix), tag("50x " + suffix),
                tag("a_b " + suffix), tag("axb " + suffix));

        tagged(second, lease);
        tagged(hidden, lease, tag("Leasehold " + suffix));
        tagged(trashed, lease);
        documentService.delete(List.of(trashed));

        // by name, whatever the case and the spaces; a tag on the documents of the others only is left out,
        // and the documents of the others and of the trash do not count
        assertThat(asEmployee(" " + suffix.toUpperCase(Locale.ROOT) + " ", 10)).containsExactly(
                new TagCount("50% " + suffix, 1),
                new TagCount("50x " + suffix, 1),
                new TagCount("a_b " + suffix, 1),
                new TagCount("axb " + suffix, 1),
                new TagCount("Lease " + suffix, 2),
                new TagCount("Sublease " + suffix, 1));

        // a percent sign and an underscore are matched as they are
        assertThat(asEmployee("% " + suffix, 10)).containsExactly(new TagCount("50% " + suffix, 1));
        assertThat(asEmployee("_b " + suffix, 10)).containsExactly(new TagCount("a_b " + suffix, 1));
        assertThat(asEmployee(suffix, 2)).hasSize(2);
    }

    private List<TagCount> asEmployee(String text, int max) {
        return systemAuthenticator.withUser(EMPLOYEE, () -> tagService.findByName(text, max));
    }

    private Tag tag(String name) {
        return save(name);
    }

    private void tagged(Document document, Tag... tags) {
        TestTags.tagged(unconstrainedDataManager, document, tags);
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

    private String keyOf(Tag tag) {
        return dataManager.loadValue("select t.nameKey from Tag t where t.id = :id", String.class)
                .parameter("id", tag.getId())
                .one();
    }

    private Tag save(String name) {
        Tag tag = dataManager.create(Tag.class);
        tag.setName(name);
        Tag saved = dataManager.save(tag);
        created.add(saved);
        return saved;
    }
}
