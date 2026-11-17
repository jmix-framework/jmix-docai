package com.company.docai.stand;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.document.UploadedFile;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.demo.DemoDataLoader;
import com.company.docai.demo.DemoSetTemplate;
import com.company.docai.test_support.StubAiConfiguration;
import com.company.docai.test_support.TestFiles;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.WrappedHttpSession;
import com.vaadin.flow.server.WrappedSession;
import io.jmix.core.DataManager;
import io.jmix.core.security.SecurityContextHelper;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.data.PersistenceHints;
import io.jmix.search.index.IndexConfiguration;
import io.jmix.search.index.mapping.IndexConfigurationManager;
import jakarta.servlet.http.HttpSession;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.ref.Reference;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;

/**
 * The data of the stand: the template of a language gets the demo set in a database of its own, completes and seals
 * its indexes while it has the database and leaves its script, a copy made from the script has the rows of the
 * template, copies do not see each other, the control database holds the schema without documents, a thread of a
 * visitor reaches no database without a scope of its own or of the browser whose command it runs, and the index of
 * the search add-on is the one of the scope of the thread. Search is off and the index of fragments lives in memory,
 * as in the other tests.
 * <p>
 * {@link StandStartupTest} has the same configuration, the two spies included, so the two classes share a Spring
 * context: a change here gives that class a context of its own, which the heap of a full test run has no room for.
 */
@SpringBootTest(classes = {DocAiApplication.class, StubAiConfiguration.class},
        properties = "docai.stand.data-dir=build/test-stand")
@ActiveProfiles({"test", "stand"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StandDataTest {

    // the field Contract kind, whose translations the changelog writes into a large object
    private static final UUID CONTRACT_KIND = UUID.fromString("5c2d7e94-1a3b-4f6c-8d0e-2b9f00000002");

    @Autowired
    private TemplateBuilder templateBuilder;

    @Autowired
    private CopyFactory copyFactory;

    @Autowired
    private CopyRegistry registry;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    // reads what the template holds in its own scope, right after the set loads into it
    @MockitoSpyBean
    private DemoDataLoader loader;

    // notes the steps of the indexes of the template while it builds
    @MockitoSpyBean
    private StandIndexes indexes;

    @Autowired
    private IndexConfigurationManager indexConfigurationManager;

    @TempDir
    private Path tempDir;

    private @Nullable Path script;
    private List<UUID> templateDocuments = List.of();
    private String templateLocales = "";
    private List<String> templateSteps = List.of();

    private final List<String> copies = new ArrayList<>();

    @AfterEach
    void deleteTheCopies() {
        copies.forEach(copyFactory::delete);
        copies.clear();
    }

    @Test
    void theTemplateGetsTheSetAndLeavesOnlyItsScript() throws IOException {
        Path built = englishTemplate();

        assertThat(built).isRegularFile().endsWith(Path.of("templates", "en", "db.script"));
        // every document of the set, the deleted ones included
        assertThat(templateDocuments).hasSize(documentsOf(DemoSetTemplate.read("en").folders()));
        assertThat(exists("tpl_en")).isFalse();
    }

    // a template completes its indexes while its database answers in its scope, and seals them before its dump
    @Test
    void theTemplateCompletesAndSealsItsIndexesBeforeItsDump() {
        englishTemplate();

        assertThat(templateSteps).containsExactly(
                "renew the indexes",
                "load the set",
                "complete the indexes, the scope reaches jdbc:hsqldb:mem:tpl_en",
                "seal the indexes, the database is kept");
    }

    @Test
    void aCopyHasTheRowsOfTheTemplate() throws Exception {
        englishTemplate();
        String key = newCopy();

        assertThat(exists("copy_" + key)).isTrue();
        assertThat(registry.find(key)).map(Copy::language).hasValue("en");
        assertThat(inCopy(key, this::documents)).containsExactlyInAnyOrderElementsOf(templateDocuments);
        // a large object of the template survives its script
        assertThat(inCopy(key, this::contractKindLocales)).isEqualTo(templateLocales).contains("en/supply=Supply");
    }

    @Test
    void copiesDoNotSeeEachOther() throws Exception {
        englishTemplate();
        String first = newCopy();
        String second = newCopy();

        UUID note = inCopy(first, () -> systemAuthenticator.withUser("admin", this::uploadANote));

        assertThat(inCopy(first, this::documents)).contains(note);
        assertThat(inCopy(second, this::documents)).containsExactlyInAnyOrderElementsOf(templateDocuments);
        // the template keeps its rows: a copy made afterwards has no note
        assertThat(inCopy(newCopy(), this::documents)).containsExactlyInAnyOrderElementsOf(templateDocuments);
    }

    @Test
    void theControlDatabaseHoldsTheSchemaWithoutDocuments() throws SQLException {
        assertThat(urlOfTheDatabase()).isEqualTo("jdbc:hsqldb:mem:control");
        // the start of the application loads no set into it
        assertThat(systemAuthenticator.withSystem(this::documents)).isEmpty();
        assertThat(systemAuthenticator.withSystem(this::usernames)).contains("admin", "alice");
    }

    // the login form works as nobody, before a copy is chosen
    @Test
    void anAnonymousThreadWorksWithTheControlDatabase() throws SQLException {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext anonymous = SecurityContextHolder.createEmptyContext();

        anonymous.setAuthentication(new AnonymousAuthenticationToken("stand", "anonymous",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        SecurityContextHelper.installContext(anonymous);

        try {
            assertThat(urlOfTheDatabase()).isEqualTo("jdbc:hsqldb:mem:control");
        } finally {
            SecurityContextHelper.restoreContext(previous);
        }
    }

    @Test
    void aVisitorWithoutAScopeReachesNoDatabase() {
        assertThatThrownBy(() -> systemAuthenticator.withUser("alice", this::documents))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alice");
    }

    // push and UI.access run the commands of a browser on threads without its scope
    @Test
    void aCommandOfABrowserWorksWithTheCopyOfItsSession() {
        englishTemplate();
        String key = newCopy();
        MockHttpSession httpSession = new MockHttpSession();
        httpSession.setAttribute(StandScopeHolder.SESSION_ATTRIBUTE, StandScope.copy(key).id());
        VaadinSession browser = browserSession(httpSession);
        VaadinSession.setCurrent(browser);

        try {
            assertThat(systemAuthenticator.withSystem(this::documents))
                    .containsExactlyInAnyOrderElementsOf(templateDocuments);
            assertThat(systemAuthenticator.withUser("alice", this::documents)).isNotEmpty();
        } finally {
            VaadinSession.setCurrent(null);
            // Vaadin holds its current instances weakly: a collected session would leave alice without a scope
            Reference.reachabilityFence(browser);
        }
    }

    // the add-on names the index of the scope a thread works with, so a copy writes and searches its own index
    @Test
    void theIndexOfTheSearchAddOnIsTheOneOfTheScopeOfTheThread() throws Exception {
        StandScope copy = StandScope.copy(CopyRegistry.newKey());
        IndexConfiguration documents = indexConfigurationManager.getIndexConfigurationByEntityName("Document");

        assertThat(documents).isInstanceOf(ScopedIndexConfiguration.class);
        assertThat(documents.getIndexName()).isEqualTo("docai_test_document");
        assertThat(StandScopeHolder.call(copy, documents::getIndexName))
                .isEqualTo("docai_test_" + copy.id() + "_document");
    }

    @Test
    void aDeletedCopyLeavesNoDatabase() {
        englishTemplate();
        String key = newCopy();

        copyFactory.delete(key);

        assertThat(registry.find(key)).isEmpty();
        assertThat(exists("copy_" + key)).isFalse();
        // a late request of the copy fails instead of going to another database
        assertThatThrownBy(() -> inCopy(key, this::documents))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no database");
    }

    // the template of the English set, built once for the class
    private Path englishTemplate() {
        if (script == null) {
            StandScope template = StandScope.template("en");
            List<String> steps = new ArrayList<>();

            doAnswer(invocation -> {
                steps.add("renew the indexes");

                return invocation.callRealMethod();
            }).when(indexes).renew(template);

            doAnswer(invocation -> {
                Object outcome = invocation.callRealMethod();
                templateDocuments = documents();
                templateLocales = contractKindLocales();
                steps.add("load the set");

                return outcome;
            }).when(loader).loadIfEmpty("en");

            doAnswer(invocation -> {
                steps.add("complete the indexes, the scope reaches " + urlOfTheDatabaseOf(template));

                return invocation.callRealMethod();
            }).when(indexes).complete(template);

            doAnswer(invocation -> {
                steps.add("seal the indexes, the database is " + (exists(template.id()) ? "kept" : "gone"));

                return invocation.callRealMethod();
            }).when(indexes).seal(template);

            script = templateBuilder.build("en");
            templateSteps = List.copyOf(steps);
        }

        return script;
    }

    private String newCopy() {
        String key = copyFactory.create("en").key();
        copies.add(key);

        return key;
    }

    private <T> T inCopy(String key, Supplier<T> work) throws Exception {
        return StandScopeHolder.call(StandScope.copy(key), () -> systemAuthenticator.withSystem(work::get));
    }

    private List<UUID> documents() {
        return dataManager.loadValue("select d.id from Document d", UUID.class)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .list();
    }

    private List<String> usernames() {
        return dataManager.loadValue("select u.username from User u", String.class)
                .list();
    }

    private String contractKindLocales() {
        return dataManager.loadValue("select a.enumerationLocales from dynat_CategoryAttribute a where a.id = :id",
                        String.class)
                .parameter("id", CONTRACT_KIND)
                .one();
    }

    private UUID uploadANote() {
        UploadedFile file = TestFiles.file(tempDir, "Stand note.txt", "text");

        return documentService.upload(folderService.getRoot(), List.of(file))
                .created()
                .getFirst()
                .getId();
    }

    private String urlOfTheDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        }
    }

    private String urlOfTheDatabaseOf(StandScope scope) {
        try {
            return StandScopeHolder.call(scope, this::urlOfTheDatabase);
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    // the session of Vaadin over the HTTP session of a browser
    private static VaadinSession browserSession(HttpSession httpSession) {
        return new VaadinSession(null) {
            @Override
            public WrappedSession getSession() {
                return new WrappedHttpSession(httpSession);
            }
        };
    }

    // ifexists=true does not create an empty database in place of one that was shut down
    private static boolean exists(String database) {
        try (Connection ignored = DriverManager.getConnection("jdbc:hsqldb:mem:" + database + ";ifexists=true",
                "SA", "")) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static int documentsOf(List<DemoSetTemplate.Folder> folders) {
        return folders.stream()
                .mapToInt(folder -> folder.documents().size() + documentsOf(folder.folders()))
                .sum();
    }
}
