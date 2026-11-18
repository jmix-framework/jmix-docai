package com.company.docai.stand;

import com.company.docai.search.semantic.DocumentVectorIndex;
import io.jmix.search.index.IndexConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The search indexes are named by the scope of the thread: a copy or a template has its own, and a thread without a
 * scope works with the indexes of the control database.
 */
class StandIndexNamesTest {

    private static final String PREFIX = "b5w_";

    private static final String KEY = "0123456789abcdef0123456789abcdef";

    // the configuration the add-on makes for the documents at the start, named by the prefix and the entity
    private final ScopedIndexConfiguration documents = new ScopedIndexConfiguration(
            new IndexConfiguration("Document", Object.class, PREFIX + "document", null, Set.of(),
                    entity -> true, null),
            PREFIX);

    private final DocumentVectorIndex fragments = new DocumentVectorIndex(null, PREFIX, "model", 3);

    @Test
    void aCopyHasIndexesOfItsOwn() throws Exception {
        StandScope copy = StandScope.copy(KEY);

        assertThat(nameIn(copy, documents::getIndexName)).isEqualTo("b5w_copy_" + KEY + "_document");
        assertThat(nameIn(copy, fragments::getIndexName)).isEqualTo("b5w_copy_" + KEY + "_document_vectors");
    }

    @Test
    void aTemplateHasIndexesOfItsOwn() throws Exception {
        StandScope template = StandScope.template("en");

        assertThat(nameIn(template, documents::getIndexName)).isEqualTo("b5w_tpl_en_document");
        assertThat(nameIn(template, fragments::getIndexName)).isEqualTo("b5w_tpl_en_document_vectors");
    }

    @Test
    void aThreadWithoutAScopeWorksWithTheIndexesOfTheStart() {
        assertThat(StandScopeHolder.resolve()).isNull();
        assertThat(documents.getIndexName()).isEqualTo("b5w_document");
        assertThat(fragments.getIndexName()).isEqualTo("b5w_document_vectors");
    }

    // the stand names the indexes of a scope it deletes or clones without binding the scope to its thread
    @Test
    void theIndexesOfAScopeAreNamedWithoutTheScopeOfTheThread() {
        StandScope copy = StandScope.copy(KEY);

        assertThat(documents.getIndexName(copy)).isEqualTo("b5w_copy_" + KEY + "_document");
        assertThat(fragments.getIndexName(copy)).isEqualTo("b5w_copy_" + KEY + "_document_vectors");
        assertThat(documents.getIndexName(null)).isEqualTo("b5w_document");
        assertThat(fragments.getIndexName(null)).isEqualTo("b5w_document_vectors");
    }

    private static String nameIn(StandScope scope, Callable<String> name) throws Exception {
        return StandScopeHolder.call(scope, name);
    }
}
