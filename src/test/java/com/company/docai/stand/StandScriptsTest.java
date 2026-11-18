package com.company.docai.stand;

import com.company.docai.DocAiApplication;
import com.company.docai.model.document.Document;
import com.company.docai.test_support.AnonymousUiTestAuthenticator;
import com.company.docai.test_support.StubAiConfiguration;
import com.company.docai.view.login.LoginView;
import com.vaadin.flow.router.RouteParam;
import com.vaadin.flow.router.RouteParameters;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Metadata;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.dynattr.model.CategoryAttribute;
import io.jmix.dynattrflowui.view.categoryattr.CategoryAttributesDetailView;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.codeeditor.CodeEditor;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.navigation.UrlParamSerializer;
import io.jmix.security.model.RowLevelPolicyAction;
import io.jmix.security.model.RowLevelPolicyType;
import io.jmix.security.role.assignment.RoleAssignmentRoleType;
import io.jmix.securitydata.entity.RoleAssignmentEntity;
import io.jmix.securitydata.entity.RowLevelPolicyEntity;
import io.jmix.securitydata.entity.RowLevelRoleEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No script a visitor keeps in a copy runs on the stand, where it would run in the one JVM that holds every copy. The
 * class shares the context of {@link StandLoginTest}, and the start of the stand makes the templates ready once for the
 * class.
 */
@UiTest(authenticator = AnonymousUiTestAuthenticator.class, initialView = LoginView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class, StubAiConfiguration.class},
        properties = "docai.stand.data-dir=build/test-stand-login")
@ActiveProfiles({"test", "stand"})
@ExtendWith(OutputCaptureExtension.class)
class StandScriptsTest {

    private static final List<String> SCRIPTS = List.of("validationScriptField", "recalculationScriptField",
            "optionsLoaderScriptField");

    private static final String GROOVY_SUPPORT =
            "io.jmix.securitydata.impl.role.provider.SecurityDataGroovyFeatureSupport";

    @Autowired
    private CopyFactory copyFactory;

    @Autowired
    private CopyRegistry registry;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private Metadata metadata;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private UrlParamSerializer urlParamSerializer;

    @Autowired
    private LoggingSystem loggingSystem;

    @Autowired
    private Environment environment;

    @BeforeAll
    static void startTheStand(@Autowired StandStartup startup) throws Exception {
        startup.start()
                .get(3, TimeUnit.MINUTES);
    }

    @AfterEach
    void deleteTheCopies() {
        registry.all()
                .forEach(copy -> copyFactory.delete(copy.key()));
    }

    // in Jmix a field validates its value, recalculates it and loads its options by scripts
    @Test
    void theEditorOfAFieldOfADocumentTypeTakesNoScript() {
        StandScope copy = newCopy();

        StandScopeHolder.run(copy, () -> systemAuthenticator.runWithUser("admin", () -> {
            CategoryAttributesDetailView editor = openEditorOf(aFieldOf("Contract"));

            assertThat(SCRIPTS).allSatisfy(id -> assertThat(UiTestUtils.<CodeEditor>getComponent(editor, id)
                    .isReadOnly()).as(id).isTrue());
            assertThat(UiTestUtils.<TypedTextField<?>>getComponent(editor, "nameField")
                    .isReadOnly()).isFalse();
        }));
    }

    // a script that lets every document through would show alice the documents she sees without the role; the add-on
    // would log a warning for every row it denies, at every load of a visitor who has the role
    @Test
    void aRowLevelPredicateThatIsAScriptDeniesEveryRowWithoutRunning(CapturedOutput output) throws Exception {
        takeTheLevelOfTheStandBack(GROOVY_SUPPORT);
        StandScope copy = newCopy();

        List<Document> before = StandScopeHolder.call(copy, () -> documentsOf("alice"));
        StandScopeHolder.run(copy, () -> systemAuthenticator.runWithSystem(() -> assignPredicate("alice", "true")));
        List<Document> after = StandScopeHolder.call(copy, () -> documentsOf("alice"));

        assertThat(before).isNotEmpty();
        assertThat(after).isEmpty();
        assertThat(output).doesNotContain("Groovy execution is disabled for security row-level predicates");
    }

    // the contexts of a test run share one logging system, and each sets the levels of its profiles as it starts
    private void takeTheLevelOfTheStandBack(String logger) {
        String level = environment.getProperty("logging.level." + logger);
        loggingSystem.setLogLevel(logger, level == null ? null : LogLevel.valueOf(level.toUpperCase(Locale.ROOT)));
    }

    private StandScope newCopy() {
        return StandScope.copy(copyFactory.create("en")
                .key());
    }

    private CategoryAttribute aFieldOf(String typeName) {
        return dataManager.load(CategoryAttribute.class)
                .query("select a from dynat_CategoryAttribute a where a.category.name = :type order by a.orderNo")
                .parameter("type", typeName)
                .fetchPlan(plan -> plan.addFetchPlan(FetchPlan.BASE)
                        .add("category", FetchPlan.BASE))
                .maxResults(1)
                .one();
    }

    private CategoryAttributesDetailView openEditorOf(CategoryAttribute field) {
        RouteParam type = new RouteParam(CategoryAttributesDetailView.CATEGORY_ID_ROUTE_PARAMETER,
                urlParamSerializer.serialize(field.getCategory()
                        .getId()));

        RouteParam id = new RouteParam(StandardDetailView.DEFAULT_ROUTE_PARAM,
                urlParamSerializer.serialize(field.getId()));

        viewNavigators.view(UiTestUtils.getCurrentView(), CategoryAttributesDetailView.class)
                .withRouteParameters(new RouteParameters(type, id))
                .navigate();

        return UiTestUtils.getCurrentView();
    }

    private List<Document> documentsOf(String username) {
        return systemAuthenticator.withUser(username, () -> dataManager.load(Document.class)
                .all()
                .list());
    }

    private void assignPredicate(String username, String script) {
        MetaClass documents = metadata.getClass(Document.class);

        RowLevelRoleEntity role = dataManager.create(RowLevelRoleEntity.class);
        role.setCode("scriptPredicate");
        role.setName("Script predicate");

        RowLevelPolicyEntity policy = dataManager.create(RowLevelPolicyEntity.class);
        policy.setRole(role);
        policy.setType(RowLevelPolicyType.PREDICATE);
        policy.setAction(RowLevelPolicyAction.READ);
        policy.setEntityName(documents.getName());
        policy.setScript(script);

        RoleAssignmentEntity assignment = dataManager.create(RoleAssignmentEntity.class);
        assignment.setUsername(username);
        assignment.setRoleCode(role.getCode());
        assignment.setRoleType(RoleAssignmentRoleType.ROW_LEVEL);

        dataManager.save(role, policy, assignment);
    }
}
