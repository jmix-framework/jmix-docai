package com.company.docai.stand;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.model.document.Document;
import com.company.docai.security.role.AccessRowLevelRole;
import com.company.docai.security.role.FullAccessRole;
import com.company.docai.test_support.StubAiConfiguration;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.WrappedHttpSession;
import com.vaadin.flow.server.WrappedSession;
import io.jmix.core.DataManager;
import io.jmix.core.EntitySet;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.Metadata;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.dynattr.AttributeDefinition;
import io.jmix.dynattr.AttributeType;
import io.jmix.dynattr.CategoryDefinition;
import io.jmix.dynattr.DynAttrMetadata;
import io.jmix.dynattr.DynAttrQueryHints;
import io.jmix.dynattr.model.Category;
import io.jmix.dynattr.model.CategoryAttribute;
import io.jmix.quartz.service.RunningJobsCache;
import io.jmix.security.role.ResourceRoleRepository;
import io.jmix.security.role.RowLevelRoleRepository;
import io.jmix.securitydata.entity.ResourceRoleEntity;
import io.jmix.securitydata.entity.RowLevelRoleEntity;
import jakarta.servlet.http.HttpSession;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.NestedTestConfiguration;

import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The caches of the stand, kept per scope for the types of documents and the roles. The class has the configuration of
 * the stand tests on {@code build/test-stand-files}, {@link StandFilesTest} and {@link StandUploadTypesTest}, so the
 * three share a Spring context.
 */
@SpringBootTest(classes = {DocAiApplication.class, StubAiConfiguration.class},
        properties = "docai.stand.data-dir=build/test-stand-files")
@ActiveProfiles({"test", "stand"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StandCachesTest {

    private static final String ADMIN = "admin";
    private static final String CONTRACT = "Contract";

    @Autowired
    private TemplateBuilder templateBuilder;

    @Autowired
    private CopyFactory copyFactory;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private DynAttrMetadata dynAttrMetadata;

    @Autowired
    private ResourceRoleRepository resourceRoles;

    @Autowired
    private RowLevelRoleRepository rowLevelRoles;

    @Autowired
    private Metadata metadata;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private DocumentService documentService;

    private final List<String> copies = new ArrayList<>();

    @BeforeAll
    void buildTheTemplate() {
        templateBuilder.build("en");
    }

    @AfterEach
    void deleteTheCopies() {
        copies.forEach(copyFactory::delete);
        copies.clear();
    }

    // the scheduler's threads write its cache without a scope, and the views of every visitor read it
    @Test
    void onlyTheCachesOfTypesAndRolesArePerScope() {
        assertThat(cacheManager).isInstanceOf(StandCacheManager.class);
        assertThat(StandCacheManager.SCOPED_CACHES).allSatisfy(name -> assertThat(cacheManager.getCache(name))
                .isInstanceOf(StandScopedCache.class));
        assertThat(cacheManager.getCache(RunningJobsCache.CACHE_NAME)).isInstanceOf(ConcurrentMapCache.class);
    }

    @Test
    void aFieldAddedToATypeInOneCopyIsNotInAnother() throws Exception {
        String first = newCopy();
        String second = newCopy();
        String code = newCode();

        // both copies have their types cached before the first one changes one of them
        assertThat(asAdmin(first, () -> fieldsOf(CONTRACT))).doesNotContain(code);
        assertThat(asAdmin(second, () -> fieldsOf(CONTRACT))).doesNotContain(code);

        asAdmin(first, () -> addField(code));
        Set<StandScope> cachedAfterTheChange = scopesIn(DynAttrMetadata.DYN_ATTR_CACHE_NAME);

        assertThat(asAdmin(first, () -> fieldsOf(CONTRACT))).contains(code);
        assertThat(asAdmin(second, () -> fieldsOf(CONTRACT))).doesNotContain(code);
        // the change in the first copy cleared the entries of that copy only
        assertThat(cachedAfterTheChange).contains(StandScope.copy(second));
    }

    // a field of another copy, if the card showed it, would break the save: the database has no such field
    @Test
    void aCopySavesItsDocumentsWhileAnotherCopyHasAFieldOfItsOwn() throws Exception {
        String first = newCopy();
        String second = newCopy();
        String code = newCode();

        asAdmin(first, () -> addField(code));

        Document inFirst = asAdmin(first, () -> filled(aContract(), "Typed in the first copy"));
        Document inSecond = asAdmin(second, () -> filled(aContract(), "Typed in the second copy"));

        assertThat(asAdmin(first, () -> fieldOf(inFirst, code))).isEqualTo("Typed in the first copy");
        assertThat(asAdmin(second, () -> fieldOf(inSecond, "counterparty"))).isEqualTo("Typed in the second copy");
    }

    @Test
    void aRoleCreatedInOneCopyIsNotInAnother() throws Exception {
        String first = newCopy();
        String second = newCopy();
        String code = "cache-test-" + UUID.randomUUID();

        // both copies look the role up before the first one creates it
        assertThat(asAdmin(first, () -> resourceRoles.findRoleByCode(code))).isNull();
        assertThat(asAdmin(first, () -> rowLevelRoles.findRoleByCode(code))).isNull();
        assertThat(asAdmin(second, () -> resourceRoles.findRoleByCode(code))).isNull();
        assertThat(asAdmin(second, () -> rowLevelRoles.findRoleByCode(code))).isNull();

        asAdmin(first, () -> createRoles(code));

        assertThat(asAdmin(first, () -> resourceRoles.findRoleByCode(code))).isNotNull();
        assertThat(asAdmin(first, () -> rowLevelRoles.findRoleByCode(code))).isNotNull();
        assertThat(asAdmin(second, () -> resourceRoles.findRoleByCode(code))).isNull();
        assertThat(asAdmin(second, () -> rowLevelRoles.findRoleByCode(code))).isNull();
    }

    // Vaadin runs a command of a browser, such as a push, on whichever thread holds the lock of its session
    @Test
    void aCommandOfABrowserReadsTheEntriesOfItsCopy() throws Exception {
        String first = newCopy();
        String second = newCopy();
        String code = newCode();

        asAdmin(first, () -> addField(code));

        assertThat(inBrowserOf(first, () -> fieldsOf(CONTRACT))).contains(code);
        assertThat(inBrowserOf(second, () -> fieldsOf(CONTRACT))).doesNotContain(code);
    }

    @Test
    void aDeletedCopyLeavesNoEntriesInTheCaches() throws Exception {
        String deleted = newCopy();
        String kept = newCopy();

        asAdmin(deleted, this::readTheCaches);
        asAdmin(kept, this::readTheCaches);

        assertThat(StandCacheManager.SCOPED_CACHES).allSatisfy(name -> assertThat(scopesIn(name))
                .contains(StandScope.copy(deleted), StandScope.copy(kept)));

        copyFactory.delete(deleted);

        assertThat(StandCacheManager.SCOPED_CACHES).allSatisfy(name -> assertThat(scopesIn(name))
                .doesNotContain(StandScope.copy(deleted))
                .contains(StandScope.copy(kept)));
    }

    private String newCopy() {
        String key = copyFactory.create("en").key();
        copies.add(key);

        return key;
    }

    private <T> T asAdmin(String key, Supplier<T> work) throws Exception {
        return StandScopeHolder.call(StandScope.copy(key), () -> systemAuthenticator.withUser(ADMIN, work::get));
    }

    private <T> T inBrowserOf(String key, Supplier<T> work) {
        MockHttpSession httpSession = new MockHttpSession();
        httpSession.setAttribute(StandScopeHolder.SESSION_ATTRIBUTE, StandScope.copy(key).id());
        VaadinSession browser = browserSession(httpSession);
        VaadinSession.setCurrent(browser);

        try {
            return systemAuthenticator.withUser(ADMIN, work::get);
        } finally {
            VaadinSession.setCurrent(null);
            // Vaadin holds its current instances weakly: a collected session would leave the work without a scope
            Reference.reachabilityFence(browser);
        }
    }

    private CategoryAttribute addField(String code) {
        Category contract = dataManager.load(Category.class)
                .query("select c from dynat_Category c where c.name = :name and c.entityType = 'Document'")
                .parameter("name", CONTRACT)
                .one();

        CategoryAttribute field = dataManager.create(CategoryAttribute.class);
        field.setCategory(contract);
        field.setCategoryEntityType("Document");
        field.setName("Cache test field");
        field.setCode(code);
        field.setDataType(AttributeType.STRING);
        field.setOrderNo(60);

        return dataManager.save(field);
    }

    private EntitySet createRoles(String code) {
        ResourceRoleEntity resourceRole = dataManager.create(ResourceRoleEntity.class);
        resourceRole.setCode(code);
        resourceRole.setName("Cache test role");

        RowLevelRoleEntity rowLevelRole = dataManager.create(RowLevelRoleEntity.class);
        rowLevelRole.setCode(code);
        rowLevelRole.setName("Cache test role");

        return dataManager.save(resourceRole, rowLevelRole);
    }

    private List<String> fieldsOf(String typeName) {
        return typeOfDocuments(typeName)
                .getAttributeDefinitions()
                .stream()
                .map(AttributeDefinition::getCode)
                .toList();
    }

    private CategoryDefinition typeOfDocuments(String typeName) {
        return dynAttrMetadata.getCategories(metadata.getClass(Document.class))
                .stream()
                .filter(type -> typeName.equals(type.getName()))
                .findFirst()
                .orElseThrow();
    }

    // a contract of the set in a status that takes changes
    private Document aContract() {
        return dataManager.load(Document.class)
                .query("select d from Document d where d.category.name = :type and d.status.locked = false "
                        + "order by d.name")
                .parameter("type", CONTRACT)
                .fetchPlan(plan -> plan.addFetchPlan(FetchPlan.BASE)
                        .add("folder", FetchPlan.BASE)
                        .add("category", FetchPlan.INSTANCE_NAME))
                .hint(DynAttrQueryHints.LOAD_DYN_ATTR, true)
                .maxResults(1)
                .one();
    }

    private Document filled(Document contract, String text) {
        typeOfDocuments(CONTRACT)
                .getAttributeDefinitions()
                .stream()
                .filter(field -> field.getDataType() == AttributeType.STRING)
                .forEach(field -> EntityValues.setValue(contract, "+" + field.getCode(), text));

        return documentService.save(contract, null, null);
    }

    private @Nullable Object fieldOf(Document document, String code) {
        Document stored = dataManager.load(Id.of(document))
                .hint(DynAttrQueryHints.LOAD_DYN_ATTR, true)
                .one();

        return EntityValues.getValue(stored, "+" + code);
    }

    private boolean readTheCaches() {
        dynAttrMetadata.getAttributes(metadata.getClass(Document.class));
        resourceRoles.findRoleByCode(FullAccessRole.CODE);
        rowLevelRoles.findRoleByCode(AccessRowLevelRole.CODE);

        return true;
    }

    private Set<StandScope> scopesIn(String cacheName) {
        Cache cache = Objects.requireNonNull(cacheManager.getCache(cacheName));
        Map<?, ?> entries = (Map<?, ?>) cache.getNativeCache();

        return entries.keySet()
                .stream()
                .filter(StandScopedCache.ScopedKey.class::isInstance)
                .map(key -> ((StandScopedCache.ScopedKey) key).scope())
                .collect(Collectors.toSet());
    }

    // the code of a field becomes the name of a property
    private static String newCode() {
        return "cacheField" + UUID.randomUUID().toString().replace("-", "");
    }

    private static VaadinSession browserSession(HttpSession httpSession) {
        return new VaadinSession(null) {
            @Override
            public WrappedSession getSession() {
                return new WrappedHttpSession(httpSession);
            }
        };
    }

    // the context that most tests share, without the profile of the stand
    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest
    class OutsideTheStand {

        @Autowired
        private CacheManager applicationCaches;

        @Test
        void noCacheIsPerScope() {
            assertThat(applicationCaches).isNotInstanceOf(StandCacheManager.class);
            assertThat(StandCacheManager.SCOPED_CACHES).allSatisfy(name -> assertThat(applicationCaches.getCache(name))
                    .isInstanceOf(ConcurrentMapCache.class));
        }
    }
}
