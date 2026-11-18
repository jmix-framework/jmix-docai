package com.company.docai.stand;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.document.Document;
import com.company.docai.test_support.AnonymousUiTestAuthenticator;
import com.company.docai.test_support.StubAiConfiguration;
import com.company.docai.test_support.StubChatModel;
import com.company.docai.test_support.TestFiles;
import com.company.docai.view.login.LoginView;
import io.jmix.aitools.dataload.execution.JpqlExecutionParameter;
import io.jmix.aitools.dataload.execution.JpqlExecutionRequest;
import io.jmix.aitools.dataload.execution.JpqlExecutionResult;
import io.jmix.aitools.dataload.tool.JpqlExecutorTool;
import io.jmix.aitools.dataload.validation.JpqlValidationIssue;
import io.jmix.aitools.dataload.validation.validator.ReadOnlyQueryValidator;
import io.jmix.core.DataManager;
import io.jmix.core.Metadata;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.dynattr.AttributeDefinition;
import io.jmix.dynattr.AttributeType;
import io.jmix.dynattr.DynAttrMetadata;
import io.jmix.dynattr.OptionsLoaderType;
import io.jmix.dynattr.model.Category;
import io.jmix.dynattr.model.CategoryAttribute;
import io.jmix.dynattr.model.CategoryAttributeConfiguration;
import io.jmix.dynattrflowui.impl.AttributeOptionsLoader;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * No SQL that a visitor gets to run in a copy reaches beyond it. The class shares the context of
 * {@link StandLoginTest}, and the start of the stand makes the templates ready once for the class.
 */
@UiTest(authenticator = AnonymousUiTestAuthenticator.class, initialView = LoginView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class, StubAiConfiguration.class},
        properties = "docai.stand.data-dir=build/test-stand-login")
@ActiveProfiles({"test", "stand"})
class StandSqlTest {

    // letters outside ASCII whose capitals, as the parsers of JPQL read them, are S and I
    private static final String LONG_S = "ſ";
    private static final String DOTLESS_I = "ı";

    private static final String SECRET = "the files of another copy";

    @Autowired
    private CopyFactory copyFactory;

    @Autowired
    private CopyRegistry registry;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private Metadata metadata;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private DynAttrMetadata dynAttrMetadata;

    @Autowired
    private AttributeOptionsLoader optionsLoader;

    @Autowired
    private JpqlExecutorTool queryTool;

    @Autowired
    private StubChatModel model;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @TempDir
    private Path tempDir;

    @BeforeAll
    static void startTheStand(@Autowired StandStartup startup) throws Exception {
        startup.start()
                .get(3, TimeUnit.MINUTES);
    }

    @AfterEach
    void deleteTheCopies() {
        registry.all()
                .forEach(copy -> copyFactory.delete(copy.key()));

        model.reset();
    }

    // SCRIPT writes the whole database into any file the application may write
    @Test
    void theConnectionOfACopyWritesNoFile() {
        StandScope copy = newCopy();
        Path file = tempDir.resolve("copy.script");

        assertThatThrownBy(() -> executeInCopy(copy, "SCRIPT '" + file + "'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("admin rights required");
        assertThat(file).doesNotExist();
    }

    // LOAD_FILE reads any file the application may read, the files of the other copies included
    @Test
    void theConnectionOfACopyReadsNoFile() throws Exception {
        StandScope copy = newCopy();
        Path secret = secretFile();

        assertThatThrownBy(() -> valueInCopy(copy, "VALUES CAST(LOAD_FILE('" + secret
                + "', 'UTF-8') AS VARCHAR(100))"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("admin rights required");
    }

    // the stand connects to a copy as SA to fill it and to shut it down, and HSQLDB gives a database in memory any
    // path on the disk unless told otherwise
    @Test
    void noDatabaseOfTheStandReadsAFile() throws Exception {
        StandScope copy = newCopy();
        Path secret = secretFile();

        try (Connection connection = StandDataSource.connect(copy);
             Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeQuery("VALUES CAST(LOAD_FILE('" + secret
                    + "', 'UTF-8') AS VARCHAR(100))"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Access is denied");
        }
    }

    // the user of the pool of a copy may read and change the rows of every table, which is all the application does
    @Test
    void aDocumentSavesAndLoadsInACopyThatIsNotAnsweredAsADba() throws Exception {
        StandScope copy = newCopy();

        UUID id = asAdmin(copy, () -> documentService.upload(folderService.getRoot(),
                        List.of(TestFiles.file(tempDir, "Stand note.txt", "A note of the visitor")))
                .created()
                .getFirst()
                .getId());

        Document loaded = asAdmin(copy, () -> dataManager.load(Document.class)
                .id(id)
                .one());

        asAdmin(copy, () -> {
            documentService.delete(List.of(loaded));

            return null;
        });

        assertThat(loaded.getName()).isEqualTo("Stand note.txt");
        assertThat(asAdmin(copy, () -> dataManager.load(Document.class)
                .id(id)
                .optional())).isEmpty();
        assertThat(valueInCopy(copy, "SELECT ADMIN FROM INFORMATION_SCHEMA.SYSTEM_USERS"
                + " WHERE USER_NAME = CURRENT_USER")).isEqualTo("FALSE");
    }

    // the editor of a field takes no script once Groovy is off, and the import of document types leaves the loader
    // of a field out; a loader kept in a copy all the same runs no SQL of its own
    @Test
    void aFieldWhoseOptionsComeFromSqlKeptInTheCopyGetsNone() throws Exception {
        StandScope copy = newCopy();
        Path secret = secretFile();
        Path written = tempDir.resolve("loader.script");

        Document document = asAdmin(copy, () -> dataManager.load(Document.class)
                .all()
                .maxResults(1)
                .one());

        AttributeDefinition reads = sqlField(copy, "readsAFile", "SELECT CAST(LOAD_FILE('" + secret
                + "', 'UTF-8') AS VARCHAR(100)) FROM (VALUES(0))");

        AttributeDefinition writes = sqlField(copy, "writesAFile", "SCRIPT '" + written + "'");
        // SQL( in the where clause of a JPQL loader goes into the query of the options as it is
        AttributeDefinition sqlInJpql = jpqlField(copy, "sqlInJpql", "SQL('1') = 1");
        AttributeDefinition functionInJpql = jpqlField(copy, "functionInJpql", "FUNCTION('USER') is not null");

        assertThat(asAdmin(copy, () -> optionsLoader.loadOptions(document, reads))).isEmpty();
        assertThat(asAdmin(copy, () -> optionsLoader.loadOptions(document, writes))).isEmpty();
        assertThat(written).doesNotExist();
        assertThat(asAdmin(copy, () -> optionsLoader.loadOptions(document, sqlInJpql))).isEmpty();
        assertThat(asAdmin(copy, () -> optionsLoader.loadOptions(document, functionInJpql))).isEmpty();
    }

    // the add-on refuses SQL( and FUNCTION( by their names in small letters, which a letter whose capital is S or I
    // passes, while the parsers of Jmix and EclipseLink read the same letter as S or I
    @Test
    void theQueryToolOfTheAssistantRunsNoSqlOfItsOwn() throws Exception {
        StandScope copy = newCopy();
        Path secret = secretFile();
        // the tool asks the model to repair a query it refuses, once for each query
        model.willReply("The query cannot be repaired");
        model.willReply("The query cannot be repaired");
        model.willReply("The query cannot be repaired");

        List<JpqlExecutionResult> results = List.of(
                query(copy, "select d.name as docName, " + LONG_S + "QL('CURRENT_USER') as dbUser from Document d"),
                query(copy, "select d.name as docName, FUNCT" + DOTLESS_I + "ON('USER') as dbUser from Document d"),
                query(copy, "select d.name as docName, " + LONG_S + "QL('CAST(LOAD_FILE(?, ?) AS VARCHAR(100))',"
                                + " :path, :charset) as dbUser from Document d",
                        new JpqlExecutionParameter("path", "String", secret.toString()),
                        new JpqlExecutionParameter("charset", "String", "UTF-8")));

        assertThat(results).allSatisfy(result -> {
            assertThat(result.getRows()).isEmpty();
            assertThat(result.getValidationResult()
                    .getIssues())
                    .extracting(JpqlValidationIssue::getCode)
                    .contains(ReadOnlyQueryValidator.JPQL_NATIVE_FUNCTION_CODE);
        });
    }

    private StandScope newCopy() {
        return StandScope.copy(copyFactory.create("en")
                .key());
    }

    private Path secretFile() throws Exception {
        return Files.writeString(tempDir.resolve("secret.txt"), SECRET);
    }

    private void executeInCopy(StandScope copy, String sql) throws Exception {
        StandScopeHolder.call(copy, () -> {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                return statement.execute(sql);
            }
        });
    }

    private @Nullable String valueInCopy(StandScope copy, String sql) throws Exception {
        return StandScopeHolder.call(copy, () -> {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(sql)) {
                rows.next();

                return rows.getString(1);
            }
        });
    }

    private <T> T asAdmin(StandScope copy, Supplier<T> work) throws Exception {
        return StandScopeHolder.call(copy, () -> systemAuthenticator.withUser("admin", work::get));
    }

    private AttributeDefinition sqlField(StandScope copy, String code, String script) throws Exception {
        return field(copy, code, AttributeType.STRING, null, OptionsLoaderType.SQL, script, null);
    }

    private AttributeDefinition jpqlField(StandScope copy, String code, String where) throws Exception {
        return field(copy, code, AttributeType.ENTITY, Document.class.getName(), OptionsLoaderType.JPQL, null,
                where);
    }

    private AttributeDefinition field(StandScope copy, String code, AttributeType type, @Nullable String entityClass,
                                      OptionsLoaderType loaderType, @Nullable String script,
                                      @Nullable String where) throws Exception {
        asAdmin(copy, () -> {
            CategoryAttributeConfiguration configuration = new CategoryAttributeConfiguration();
            configuration.setOptionsLoaderType(loaderType);
            configuration.setOptionsLoaderScript(script);

            CategoryAttribute attribute = dataManager.create(CategoryAttribute.class);
            attribute.setCategory(dataManager.load(Category.class)
                    .query("select c from dynat_Category c where c.name = :name")
                    .parameter("name", "Contract")
                    .one());
            attribute.setCategoryEntityType(metadata.getClass(Document.class)
                    .getName());
            attribute.setName(code);
            attribute.setCode(code);
            attribute.setDataType(type);
            attribute.setEntityClass(entityClass);
            attribute.setLookup(true);
            attribute.setWhereClause(where);
            attribute.setConfiguration(configuration);

            return dataManager.save(attribute);
        });

        return asAdmin(copy, () -> {
            dynAttrMetadata.reload();

            return dynAttrMetadata.getAttributeByCode(metadata.getClass(Document.class), code)
                    .orElseThrow();
        });
    }

    private JpqlExecutionResult query(StandScope copy, String jpql, JpqlExecutionParameter... parameters)
            throws Exception {
        List<String> columns = List.of("docName", "dbUser");

        return asAdmin(copy, () -> queryTool.executeQuery(new JpqlExecutionRequest("A question of the visitor", jpql,
                List.of(parameters), columns, 3, 0), new ToolContext(Map.of())));
    }
}
