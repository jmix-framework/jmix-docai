package com.company.docai.stand;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StandScopeTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";

    @Test
    void aCopyIsNamedByItsKey() {
        StandScope scope = StandScope.copy(KEY);

        assertThat(scope.id()).isEqualTo("copy_" + KEY);
        assertThat(scope.isCopy()).isTrue();
        assertThat(scope.isTemplate()).isFalse();
        assertThat(scope.key()).isEqualTo(KEY);
        assertThat(scope.databaseUrl()).isEqualTo("jdbc:hsqldb:mem:copy_" + KEY);
        assertThat(scope.indexPrefix("docai_")).isEqualTo("docai_copy_" + KEY + "_");
        assertThat(scope.folder(Path.of("data"))).isEqualTo(Path.of("data", "copies", KEY));
    }

    @Test
    void aTemplateIsNamedByItsLanguage() {
        StandScope scope = StandScope.template("en");

        assertThat(scope.id()).isEqualTo("tpl_en");
        assertThat(scope.isTemplate()).isTrue();
        assertThat(scope.isCopy()).isFalse();
        assertThat(scope.language()).isEqualTo("en");
        assertThat(scope.databaseUrl()).isEqualTo("jdbc:hsqldb:mem:tpl_en");
        assertThat(scope.indexPrefix("docai_")).isEqualTo("docai_tpl_en_");
        assertThat(scope.folder(Path.of("data"))).isEqualTo(Path.of("data", "templates", "en"));
    }

    @Test
    void aMalformedKeyOrLanguageIsRejected() {
        assertThatThrownBy(() -> StandScope.copy("0123456789ABCDEF0123456789abcdef"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> StandScope.copy("0123abcd")).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> StandScope.copy("../../../../../../../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> StandScope.template("EN")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StandScope.template("../")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theKeyBelongsOnlyToACopyAndTheLanguageOnlyToATemplate() {
        assertThatThrownBy(() -> StandScope.template("en").key()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> StandScope.copy(KEY).language()).isInstanceOf(IllegalStateException.class);
    }

    // a scope also comes from an attribute of the HTTP session, so nothing but a copy or a template makes one
    @Test
    void aScopeIsACopyOrATemplateAndNothingElse() {
        String[] malformed = {"copy_../templates/en", "copy_" + KEY.toUpperCase(Locale.ROOT), "copy_" + KEY + "_",
                "tpl_EN", "tpl_eng", "tpl_../copies", "control", ""};

        for (String id : malformed) {
            assertThatThrownBy(() -> new StandScope(id)).as(id).isInstanceOf(IllegalArgumentException.class);
            assertThat(StandScope.fromId(id)).as(id).isNull();
        }

        assertThat(new StandScope("copy_" + KEY)).isEqualTo(StandScope.copy(KEY));
        assertThat(StandScope.fromId("tpl_en")).isEqualTo(StandScope.template("en"));
    }

    // the key opens the copy through the cookie of its browser, so a log names the copy by a one-way tag of the key
    @Test
    void theLogsNameACopyByATagOfItsKeyAndATemplateByItsId() throws NoSuchAlgorithmException {
        String tag = StandScope.copy(KEY)
                .tag();

        String hash = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(KEY.getBytes(StandardCharsets.US_ASCII)));

        assertThat(tag).isEqualTo("copy#" + hash.substring(0, 8));
        assertThat(KEY).doesNotContain(tag.substring("copy#".length()));
        assertThat(StandScope.copy(KEY).tag()).isEqualTo(tag);
        assertThat(StandScope.copy("fedcba9876543210fedcba9876543210").tag()).isNotEqualTo(tag);
        assertThat(StandScope.template("en").tag()).isEqualTo("tpl_en");
        // a scope printed by chance, as in the key of a cache entry
        assertThat(StandScope.copy(KEY).toString()).contains(tag).doesNotContain(KEY);
    }

    // a message of OpenSearch, of the database or of the file system names an index, a database or a folder of a copy
    @Test
    void aTextTheStandDidNotWriteNamesEachCopyByItsTag() {
        StandScope copy = StandScope.copy(KEY);
        StandScope other = StandScope.copy("fedcba9876543210fedcba9876543210");

        assertThat(StandScope.withTags("no such index [docai_copy_" + KEY + "_document]"))
                .isEqualTo("no such index [docai_" + copy.tag() + "_document]");
        assertThat(StandScope.withTags("jdbc:hsqldb:mem:" + other.id() + " and " + copy.id()))
                .isEqualTo("jdbc:hsqldb:mem:" + other.tag() + " and " + copy.tag());
        assertThat(StandScope.withTags("/srv/.jmix/stand/copies/" + KEY + "/files/2026/11/17/note.txt"))
                .isEqualTo("/srv/.jmix/stand/copies/" + copy.tag() + "/files/2026/11/17/note.txt");

        // a template, a longer run of hexadecimal characters and capitals are no keys
        String noKey = "docai_tpl_en_document copy_" + KEY + "0 copy_" + KEY.toUpperCase(Locale.ROOT);

        assertThat(StandScope.withTags(noKey)).isEqualTo(noKey);
    }

    // a cause chain logged whole, as when a copy is not made at a login
    @Test
    void aThrowableNamesEachCopyByItsTagInEveryCause() {
        StandScope copy = StandScope.copy(KEY);

        IllegalStateException failure = new IllegalStateException("The indexes of the template en are not cloned",
                new IllegalArgumentException("index [docai_" + copy.id() + "_document] already exists"));

        assertThat(StandScope.withTags(failure))
                .startsWith("java.lang.IllegalStateException: The indexes of the template en are not cloned")
                .contains("Caused by: java.lang.IllegalArgumentException: index [docai_" + copy.tag()
                        + "_document] already exists")
                .contains("\tat " + StandScopeTest.class.getName())
                .doesNotContain(KEY)
                .doesNotEndWith(System.lineSeparator());
    }
}
