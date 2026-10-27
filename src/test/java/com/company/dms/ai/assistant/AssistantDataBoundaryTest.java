package com.company.dms.ai.assistant;

import com.company.dms.DMSApplication;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.access.AccessLevel;
import com.company.dms.model.document.Document;
import com.company.dms.model.folder.Folder;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import com.company.dms.test_support.StubChatModelConfiguration;
import com.company.dms.test_support.TestAccess;
import com.company.dms.test_support.TestAccess.Grant;
import com.company.dms.test_support.TestFiles;
import io.jmix.aitools.dataload.execution.GeneratedJpqlParameter;
import io.jmix.aitools.dataload.execution.GeneratedJpqlResult;
import io.jmix.aitools.dataload.execution.JpqlExecutionParameter;
import io.jmix.aitools.dataload.execution.JpqlExecutionRequest;
import io.jmix.aitools.dataload.execution.JpqlExecutionResult;
import io.jmix.aitools.dataload.introspection.model.EntityPropertyDescriptor;
import io.jmix.aitools.dataload.introspection.model.EntitySummary;
import io.jmix.aitools.dataload.tool.DomainModelDiscoveryTool;
import io.jmix.aitools.dataload.tool.JpqlExecutorTool;
import io.jmix.aitools.dataload.validation.JpqlValidationIssue;
import io.jmix.aitools.dataload.validation.JpqlValidationService;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * What the data tools of the AI Tools add-on give the assistant in the menu: the documents, the folders and
 * the document types, and of them only what the person who asks may open, wherever a query reaches them.
 */
@SpringBootTest(classes = {DMSApplication.class, StubChatModelConfiguration.class})
@ExtendWith(AuthenticatedAsAdmin.class)
class AssistantDataBoundaryTest {

    private static final String EMPLOYEE = "peter";

    @Autowired
    private DomainModelDiscoveryTool discovery;

    @Autowired
    private JpqlExecutorTool executor;

    @Autowired
    private JpqlValidationService validation;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @TempDir
    private Path tempDir;

    private String prefix;
    private Folder open;
    private Folder closed;

    @BeforeEach
    void setUp() {
        prefix = "assistant-boundary-test-" + UUID.randomUUID();
        open = folder(prefix + "-open");
        closed = TestAccess.giveOwnAccess(dataManager, folder(prefix + "-closed"),
                Grant.user("maria", AccessLevel.VIEW));
    }

    @AfterEach
    void deleteFolders() {
        for (Folder folder : List.of(open, closed)) {
            dataManager.load(Id.of(folder))
                    .optional()
                    .ifPresent(folderService::delete);
        }
    }

    @Test
    void theModelLearnsOfTheDocumentsTheFoldersAndTheTypesOnly() {
        List<String> entities = asTheEmployee(() -> discovery.getAvailableEntities(toolContext())
                .stream()
                .map(EntitySummary::getEntityName)
                .toList());

        List<String> attributes = asTheEmployee(() -> discovery.getDomainModelForEntities(List.of("Document"),
                        toolContext())
                .getFirst()
                .getProperties()
                .stream()
                .map(EntityPropertyDescriptor::getName)
                .toList());

        assertThat(entities).containsExactlyInAnyOrder("Document", "Folder", "dynat_Category");
        assertThat(attributes).contains("name", "folder", "category", "status", "documentDate")
                .doesNotContain("nameKey", "accessSet", "currentRevision", "revisions");
    }

    @Test
    void theClosedEntitiesCannotBeQueriedEvenByTheAdministrator() {
        List<String> queries = List.of("select count(e) as total from AccessEntry e",
                "select count(u) as total from User u",
                "select count(r) as total from Document d join d.revisions r");

        assertThat(queries).allSatisfy(query -> assertThat(validation.validate(
                new GeneratedJpqlResult(query, List.of(), "", List.of())).isValid()).as(query).isFalse());
    }

    @Test
    void aCountTellsEachPersonOfTheDocumentsTheyMayOpen() {
        upload(open, "visible.txt");
        TestAccess.giveOwnAccess(dataManager, upload(open, "hidden.txt"), Grant.user("maria", AccessLevel.VIEW));
        String count = "select count(d) as total from Document d where d.name like :prefix";

        assertThat(total(count)).isEqualTo(2);
        assertThat(asTheEmployee(() -> total(count))).isEqualTo(1);
    }

    @Test
    void aHiddenDocumentStaysHiddenWhereverTheQueryReachesIt() {
        upload(open, "visible.txt");
        Document hidden = TestAccess.giveOwnAccess(dataManager, upload(open, "hidden.txt"),
                Grant.user("maria", AccessLevel.VIEW));

        long secondEntity = asTheEmployee(() -> total("select count(d) as total from Folder f, Document d "
                + "where d.folder = f and d.name like :prefix"));

        JpqlExecutionResult subquery = asTheEmployee(() -> execute("select count(d) as total from Document d "
                + "where d.name like :prefix and exists (select h.id from Document h where h.name = :hidden)",
                List.of("total"), new JpqlExecutionParameter("hidden", "String", hidden.getName())));

        assertThat(secondEntity).isEqualTo(1);
        assertThat(subquery.getRows()).isEmpty();
        assertThat(subquery.getExecutionError()).contains("subquery");
    }

    @Test
    void aClosedFolderStaysHiddenWhereverTheQueryReachesIt() {
        upload(open, "visible.txt");
        TestAccess.giveOwnAccess(dataManager, upload(closed, "shared.txt"), Grant.user(EMPLOYEE, AccessLevel.VIEW));

        List<Map<String, Object>> folders = asTheEmployee(() -> execute("select f.name as folderName "
                + "from Document d, Folder f where d.name like :prefix and f.name like :prefix",
                List.of("folderName")).getRows());

        List<Map<String, Object>> documents = asTheEmployee(() -> execute("select d.name as documentName, "
                        + "f.name as folderName from Document d left join d.folder f where d.name like :prefix "
                        + "order by d.name",
                List.of("documentName", "folderName")).getRows());

        assertThat(folders).extracting(row -> row.get("folderName")).containsOnly(open.getName());
        assertThat(documents).extracting(row -> row.get("documentName"), row -> row.get("folderName"))
                .containsExactly(tuple(prefix + "-shared.txt", null), tuple(prefix + "-visible.txt", open.getName()));
    }

    @Test
    void theAliasOfTheAccessRulesOpensNothingHiddenToAQuery() {
        upload(open, "visible.txt");
        TestAccess.giveOwnAccess(dataManager, upload(open, "hidden.txt"), Grant.user("maria", AccessLevel.VIEW));
        upload(closed, "in-closed.txt");

        List<Map<String, Object>> documents = asTheEmployee(() -> execute(
                "select ae.name as name from Document ae where ae.name like :prefix", List.of("name")).getRows());

        long count = asTheEmployee(() -> total("select count(AE) as total from Document AE "
                + "where AE.name like :prefix"));

        List<Map<String, Object>> folders = asTheEmployee(() -> execute(
                "select ae.name as name from Folder ae where ae.name like :prefix", List.of("name")).getRows());

        assertThat(documents).extracting(row -> row.get("name")).containsExactly(prefix + "-visible.txt");
        assertThat(count).isEqualTo(1);
        assertThat(folders).extracting(row -> row.get("name")).containsExactly(open.getName());
    }

    @Test
    void aQueryCannotNameTheUserOrTheRolesOfTheAccessRules() {
        GeneratedJpqlResult query = new GeneratedJpqlResult("select count(d) as total from Document d "
                + "where d.name <> :current_user_username and d.name not in :current_role_codes",
                List.of(new GeneratedJpqlParameter("current_user_username", "String", "maria"),
                        new GeneratedJpqlParameter("current_role_codes", "String", "employee")), "", List.of());

        List<String> issues = validation.validate(query)
                .getIssues()
                .stream()
                .map(JpqlValidationIssue::getMessage)
                .toList();

        assertThat(issues).anyMatch(issue -> issue.contains("current_user_username"))
                .anyMatch(issue -> issue.contains("current_role_codes"));
    }

    private long total(String jpql) {
        Number total = (Number) execute(jpql, List.of("total")).getRows().getFirst().get("total");
        assertThat(total).isNotNull();

        return total.longValue();
    }

    private JpqlExecutionResult execute(String jpql, List<String> columns, JpqlExecutionParameter... more) {
        List<JpqlExecutionParameter> parameters = new ArrayList<>(List.of(more));
        parameters.add(new JpqlExecutionParameter("prefix", "String", prefix + "%"));

        return executor.executeQuery(new JpqlExecutionRequest("A question of the person", jpql, parameters,
                columns, 20, 0), toolContext());
    }

    private <T> T asTheEmployee(Supplier<T> work) {
        return systemAuthenticator.withUser(EMPLOYEE, work::get);
    }

    private static ToolContext toolContext() {
        return new ToolContext(Map.of());
    }

    private Folder folder(String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName(name);

        return folderService.save(folder);
    }

    private Document upload(Folder folder, String fileName) {
        return documentService.upload(folder, List.of(TestFiles.file(tempDir, prefix + "-" + fileName,
                        "A document of the assistant test".getBytes(StandardCharsets.UTF_8))))
                .created()
                .getFirst();
    }
}
