package com.company.docai.tag;

import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.tag.TagService;
import com.company.docai.model.tag.Tag;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import io.jmix.core.DataManager;
import io.jmix.core.EntityStates;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.util.ArrayList;
import java.util.List;
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

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private final List<Tag> created = new ArrayList<>();

    @AfterEach
    void deleteTags() {
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
