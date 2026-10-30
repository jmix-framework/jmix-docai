package com.company.docai.document;

import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.document.DocumentFields;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentStatus;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import com.company.docai.test_support.TestFiles;
import io.jmix.core.DataManager;
import io.jmix.core.DeletePolicyException;
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
import io.jmix.dynattr.MsgBundleTools;
import io.jmix.dynattr.model.Category;
import io.jmix.dynattr.model.CategoryAttribute;
import io.jmix.dynattr.model.CategoryAttributeValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Document types: the categories of the dynamic attributes add-on. A document keeps only the fields of its
 * type, and a change of the type or a field is a change of the document itself.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class DocumentTypesTest {

    private static final String EMPLOYEE = "peter";
    private static final String EARLIER_EDITOR = "maria";
    private static final OffsetDateTime LONG_AGO = OffsetDateTime.of(2020, 1, 15, 9, 30, 0, 0, ZoneOffset.UTC);

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentFields documentFields;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private Metadata metadata;

    @Autowired
    private DynAttrMetadata dynAttrMetadata;

    @Autowired
    private MsgBundleTools msgBundleTools;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    private final List<Object> typesToRemove = new ArrayList<>();

    @BeforeEach
    void createSandbox() {
        sandbox = folder("document-types-test-" + UUID.randomUUID());
    }

    @AfterEach
    void deleteSandbox() {
        folderService.delete(sandbox);
        typesToRemove.forEach(type -> dataManager.load(Id.of(type)).optional().ifPresent(dataManager::remove));
    }

    @Test
    void fourTypesComeWithTheirFields() {
        assertThat(types()).extracting(CategoryDefinition::getName)
                .containsExactlyInAnyOrder("Contract", "Invoice", "Completion certificate", "HR document");

        assertThat(fields("Contract")).extracting(AttributeDefinition::getCode)
                .containsExactly("contractNumber", "contractKind", "counterparty", "contractAmount", "validUntil");
        assertThat(fields("Invoice")).extracting(AttributeDefinition::getCode)
                .containsExactly("invoiceNumber", "supplier", "invoiceAmount", "dueDate");
        assertThat(fields("Completion certificate")).extracting(AttributeDefinition::getCode)
                .containsExactly("contractor", "certificateAmount", "workCompletedOn", "basisContract");
        assertThat(fields("HR document")).extracting(AttributeDefinition::getCode)
                .containsExactly("employee", "department", "hireDate");

        assertThat(fields("Contract")).extracting(AttributeDefinition::getDataType)
                .containsExactly(AttributeType.STRING, AttributeType.ENUMERATION, AttributeType.STRING,
                        AttributeType.DECIMAL, AttributeType.DATE_WITHOUT_TIME);
        assertThat(types()).flatExtracting(CategoryDefinition::getAttributeDefinitions)
                .noneMatch(AttributeDefinition::isRequired);
        assertThat(contractKind().getEnumeration()).isEqualTo("supply,lease,work,services,leasing");
    }

    @Test
    void typesAndFieldsAreNamedInBothLanguages() {
        for (CategoryDefinition type : types()) {
            Category category = (Category) type.getSource();
            assertThat(msgBundleTools.getMsgBundleValues(category.getLocaleNames())).containsKeys("en", "ru");

            for (AttributeDefinition field : type.getAttributeDefinitions()) {
                assertThat(msgBundleTools.getMsgBundleValues(field.getNameMsgBundle())).containsKeys("en", "ru");
            }
        }

        Map<String, String> kinds = msgBundleTools.getMsgBundleValues(contractKind().getEnumerationMsgBundle());
        assertThat(kinds).containsKeys("en/supply", "ru/supply", "en/leasing", "ru/leasing");
    }

    @Test
    void aDocumentIsCreatedWithItsTypeAndFields() {
        Document draft = dataManager.create(Document.class);
        draft.setFolder(sandbox);
        draft.setName("contract.txt");
        draft.setCategory(type("Contract"));
        setField(draft, "counterparty", "Acme Ltd");
        setField(draft, "contractAmount", new BigDecimal("1250000.00"));
        setField(draft, "validUntil", LocalDate.of(2026, 12, 31));

        Document created = systemAuthenticator.withUser(EMPLOYEE, () ->
                documentService.create(draft, TestFiles.file(tempDir, "contract.txt", "text"), null));

        Document stored = reloadWithFields(created);
        assertThat(stored.getCategory()).isEqualTo(type("Contract"));
        assertThat(field(stored, "counterparty")).isEqualTo("Acme Ltd");
        assertThat((BigDecimal) field(stored, "contractAmount")).isEqualByComparingTo("1250000");
        assertThat(field(stored, "validUntil")).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void aTypeChosenAndChangedBeforeTheFirstSaveLeavesNoValues() {
        Document draft = dataManager.create(Document.class);
        draft.setFolder(sandbox);
        draft.setName("invoice.txt");
        draft.setCategory(type("Contract"));
        setField(draft, "counterparty", "Acme Ltd");
        draft.setCategory(type("Invoice"));
        setField(draft, "supplier", "Globex");

        Document created = documentService.create(draft, TestFiles.file(tempDir, "invoice.txt", "text"), null);

        assertThat(storedCodes(created)).containsExactly("supplier");
    }

    @Test
    void aNewTypeDropsTheFieldsOfTheFormerOne() {
        Document document = typed("Contract", "counterparty", "Acme Ltd");

        Document edited = reloadWithFields(document);
        edited.setCategory(type("Invoice"));
        setField(edited, "supplier", "Globex");
        systemAuthenticator.runWithUser(EMPLOYEE, () -> documentService.save(edited, null, null));

        Document stored = reloadWithFields(document);
        assertThat(stored.getCategory()).isEqualTo(type("Invoice"));
        assertThat(field(stored, "counterparty")).isNull();
        assertThat(field(stored, "supplier")).isEqualTo("Globex");
        assertThat(storedCodes(document)).containsExactly("supplier");
    }

    @Test
    void theFormerTypeChosenAgainBeforeSavingKeepsItsValues() {
        Document document = typed("Contract", "counterparty", "Acme Ltd");

        Document edited = reloadWithFields(document);
        edited.setCategory(type("Invoice"));
        edited.setCategory(type("Contract"));
        documentService.save(edited, null, null);

        assertThat(field(reloadWithFields(document), "counterparty")).isEqualTo("Acme Ltd");
    }

    @Test
    void aDocumentWithoutTypeKeepsNoFields() {
        Document document = typed("Contract", "counterparty", "Acme Ltd");

        Document edited = reloadWithFields(document);
        edited.setCategory(null);
        documentService.save(edited, null, null);

        assertThat(reloadWithFields(document).getCategory()).isNull();
        assertThat(storedCodes(document)).isEmpty();
    }

    @Test
    void aRefusedSaveLeavesTheValuesOfTheFormerTypeInTheEditedDocument() {
        Document document = typed("Contract", "counterparty", "Acme Ltd");

        Document edited = reloadWithFields(document);
        edited.setCategory(type("Invoice"));
        // a draft does not go to the archive
        assertThatThrownBy(() -> documentService.save(edited, null, DocumentStatus.ARCHIVED))
                .isInstanceOf(OperationRejectedException.class);

        assertThat(field(edited, "counterparty")).isEqualTo("Acme Ltd");
    }

    @Test
    void aTypeTheCacheDoesNotKnowKeepsTheFields() {
        Document edited = reloadWithFields(typed("Contract", "counterparty", "Acme Ltd"));
        Category unknown = dataManager.create(Category.class);
        unknown.setName("Unknown");
        unknown.setEntityType("Document");
        edited.setCategory(unknown);

        documentFields.keepOwnType(edited);

        assertThat(field(edited, "counterparty")).isEqualTo("Acme Ltd");
    }

    @Test
    void aNewFieldValueStampsTheDocument() {
        Document document = stampedLongAgo(typed("Contract", "counterparty", "Acme Ltd"));

        Document edited = reloadWithFields(document);
        setField(edited, "counterparty", "Initech");
        systemAuthenticator.runWithUser(EMPLOYEE, () -> documentService.save(edited, null, null));

        Document stored = reloadWithFields(document);
        assertThat(field(stored, "counterparty")).isEqualTo("Initech");
        assertThat(stored.getChangedBy()).isEqualTo(EMPLOYEE);
        assertThat(stored.getChangedDate()).isAfter(LONG_AGO);
    }

    @Test
    void aNewTypeStampsTheDocument() {
        Document document = stampedLongAgo(upload());

        Document edited = reloadWithFields(document);
        edited.setCategory(type("HR document"));
        systemAuthenticator.runWithUser(EMPLOYEE, () -> documentService.save(edited, null, null));

        assertThat(reloadWithFields(document).getChangedBy()).isEqualTo(EMPLOYEE);
    }

    @Test
    void aValuePutBackKeepsTheStamp() {
        Document document = stampedLongAgo(typed("Invoice", "invoiceAmount", new BigDecimal("1.50")));

        Document edited = reloadWithFields(document);
        setField(edited, "invoiceAmount", new BigDecimal("99"));
        setField(edited, "invoiceAmount", new BigDecimal("1.5"));
        systemAuthenticator.runWithUser(EMPLOYEE, () -> documentService.save(edited, null, null));

        Document stored = reloadWithFields(document);
        assertThat(stored.getChangedBy()).isEqualTo(EARLIER_EDITOR);
        assertThat(stored.getChangedDate()).isAtSameInstantAs(LONG_AGO);
    }

    @Test
    void anArchivedDocumentKeepsItsTypeAndFields() {
        Document document = archived(typed("Contract", "counterparty", "Acme Ltd"));

        Document newValue = reloadWithFields(document);
        setField(newValue, "counterparty", "Initech");
        assertThatThrownBy(() -> documentService.save(newValue, null, null))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessageContaining("a.txt");

        Document newType = reloadWithFields(document);
        newType.setCategory(type("Invoice"));
        assertThatThrownBy(() -> documentService.save(newType, null, null))
                .isInstanceOf(OperationRejectedException.class);

        Document stored = reloadWithFields(document);
        assertThat(stored.getCategory()).isEqualTo(type("Contract"));
        assertThat(field(stored, "counterparty")).isEqualTo("Acme Ltd");
    }

    @Test
    void anArchivedDocumentKeepsItsTypeOnAnySavePath() {
        Document document = archived(typed("Contract", "counterparty", "Acme Ltd"));

        Document stored = dataManager.load(Id.of(document)).one();
        stored.setCategory(type("Invoice"));

        assertThatThrownBy(() -> dataManager.save(stored))
                .isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void aNewVersionAStatusChangeAndAMoveKeepTheFields() {
        Document document = typed("Contract", "counterparty", "Acme Ltd");
        Folder target = folder(sandbox, "Target");

        documentService.uploadRevision(document, TestFiles.file(tempDir, "a-v2.txt", "second"));
        documentService.changeStatus(List.of(document), DocumentStatus.ACTIVE);
        documentService.move(List.of(document), target);

        assertThat(field(reloadWithFields(document), "counterparty")).isEqualTo("Acme Ltd");
    }

    @Test
    void aTypeThatDocumentsAreMarkedWithIsNotDeleted() {
        Category type = newType("ForDelete" + UUID.randomUUID().toString().replace("-", ""));
        Document document = upload();
        Document edited = reloadWithFields(document);
        edited.setCategory(type);
        documentService.save(edited, null, null);

        assertThatThrownBy(() -> dataManager.remove(dataManager.load(Id.of(type)).one()))
                .isInstanceOf(DeletePolicyException.class);
    }

    @Test
    void anUnusedTypeIsDeleted() {
        Category type = newType("Unused" + UUID.randomUUID().toString().replace("-", ""));

        dataManager.remove(type);

        assertThat(dataManager.load(Id.of(type)).optional()).isEmpty();
    }

    @Test
    void aTypeOfDeletedDocumentsOnlyIsDeleted() {
        Category type = newType("OfDeleted" + UUID.randomUUID().toString().replace("-", ""));
        Document document = upload();
        Document edited = reloadWithFields(document);
        edited.setCategory(type);
        documentService.save(edited, null, null);

        documentService.delete(List.of(document));
        dataManager.remove(dataManager.load(Id.of(type)).one());

        assertThat(dataManager.load(Id.of(type)).optional()).isEmpty();
    }

    @Test
    void aDocumentWithAValueOfADeletedFieldIsStillSaved() {
        Category type = newType("WithDeletedField" + UUID.randomUUID().toString().replace("-", ""));
        CategoryAttribute field = newField(type, "gone" + UUID.randomUUID().toString().replace("-", ""));
        Document document = upload();
        Document edited = reloadWithFields(document);
        edited.setCategory(type);
        setField(edited, field.getCode(), "value");
        documentService.save(edited, null, null);

        dataManager.remove(dataManager.load(Id.of(field)).one());
        Document renamed = reloadWithFields(document);
        renamed.setName("b.txt");
        documentService.save(renamed, null, null);

        Document stored = reloadWithFields(document);
        assertThat(stored.getName()).isEqualTo("b.txt");
        assertThat(field(stored, field.getCode())).isNull();
    }

    @Test
    void aSavedFieldIsAppliedAtOnce() {
        Category type = newType("Fresh" + UUID.randomUUID().toString().replace("-", ""));
        String code = "fresh" + UUID.randomUUID().toString().replace("-", "");

        // the cache holds the fields of documents now, so only a reload shows the new one
        assertThat(dynAttrMetadata.getAttributeByCode(metadata.getClass(Document.class), code)).isEmpty();

        newField(type, code);

        assertThat(dynAttrMetadata.getAttributeByCode(metadata.getClass(Document.class), code)).isPresent();
    }

    private Collection<CategoryDefinition> types() {
        return dynAttrMetadata.getCategories(metadata.getClass(Document.class));
    }

    private List<AttributeDefinition> fields(String typeName) {
        return types().stream()
                .filter(type -> typeName.equals(type.getName()))
                .findFirst()
                .orElseThrow()
                .getAttributeDefinitions().stream()
                .sorted(Comparator.comparingInt(AttributeDefinition::getOrderNo))
                .toList();
    }

    private AttributeDefinition contractKind() {
        return fields("Contract").stream()
                .filter(field -> "contractKind".equals(field.getCode()))
                .findFirst()
                .orElseThrow();
    }

    private Category type(String name) {
        return dataManager.load(Category.class)
                .query("select c from dynat_Category c where c.name = :name and c.entityType = 'Document'")
                .parameter("name", name)
                .one();
    }

    private Category newType(String name) {
        Category type = dataManager.create(Category.class);
        type.setName(name);
        type.setEntityType("Document");
        Category saved = dataManager.save(type);
        typesToRemove.add(saved);
        return saved;
    }

    private CategoryAttribute newField(Category type, String code) {
        CategoryAttribute field = dataManager.create(CategoryAttribute.class);
        field.setCategory(type);
        field.setCategoryEntityType("Document");
        field.setName("Test field");
        field.setCode(code);
        field.setDataType(AttributeType.STRING);
        field.setOrderNo(10);
        CategoryAttribute saved = dataManager.save(field);
        typesToRemove.addFirst(saved);
        return saved;
    }

    private Document typed(String typeName, String code, Object value) {
        Document document = upload();
        Document edited = reloadWithFields(document);
        edited.setCategory(type(typeName));
        setField(edited, code, value);
        return documentService.save(edited, null, null);
    }

    private Document archived(Document document) {
        documentService.changeStatus(List.of(document), DocumentStatus.ACTIVE);
        documentService.changeStatus(List.of(document), DocumentStatus.ARCHIVED);
        return document;
    }

    private Document stampedLongAgo(Document document) {
        Document stored = dataManager.load(Id.of(document)).one();
        stored.setChangedBy(EARLIER_EDITOR);
        stored.setChangedDate(LONG_AGO);
        return dataManager.save(stored);
    }

    private List<String> storedCodes(Document document) {
        return dataManager.load(CategoryAttributeValue.class)
                .query("select v from dynat_CategoryAttributeValue v where v.entity.entityId = :id")
                .parameter("id", document.getId())
                .list().stream()
                .map(CategoryAttributeValue::getCode)
                .toList();
    }

    private Document upload() {
        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, "a.txt", "text")))
                .created()
                .getFirst();
    }

    private Folder folder(String name) {
        return folder(folderService.getRoot(), name);
    }

    private Folder folder(Folder parent, String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(parent);
        folder.setName(name);
        return folderService.save(folder);
    }

    private Document reloadWithFields(Document document) {
        return dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("folder", FetchPlan.BASE)
                        .add("category", FetchPlan.INSTANCE_NAME))
                .hint(DynAttrQueryHints.LOAD_DYN_ATTR, true)
                .one();
    }

    private static void setField(Document document, String code, Object value) {
        EntityValues.setValue(document, "+" + code, value);
    }

    private static Object field(Document document, String code) {
        return EntityValues.getValue(document, "+" + code);
    }
}
