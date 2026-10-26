package com.company.dms.ai.classification;

import com.company.dms.ai.AiSettings;
import com.company.dms.ai.DocumentTextReader;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.document.NewVersion;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.document.Document;
import com.company.dms.model.folder.Folder;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import com.company.dms.test_support.TestFiles;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.entity.EntityValues;
import io.jmix.dynattr.DynAttrQueryHints;
import io.jmix.dynattr.model.Category;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAI model classifies the English samples next to this test. Runs only with {@code ./gradlew aiTest}
 * and the key in {@code OPENAI_API_KEY}; each run costs a few cents.
 */
@Tag("ai")
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class LiveClassificationTest {

    @Autowired
    private DocumentClassifier documentClassifier;

    @Autowired
    private DocumentTextReader documentTextReader;

    @Autowired
    private AiSettings aiSettings;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void setUp() {
        assertThat(aiSettings.isAvailable()).as("aiTest needs the key in OPENAI_API_KEY").isTrue();

        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("live-classification-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);
    }

    @AfterEach
    void deleteSandbox() {
        dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete);
    }

    @Test
    void anInvoiceGetsItsTypeAndFields() {
        Document invoice = fill("invoice-1187.txt");

        assertThat(invoice.getCategory()).isEqualTo(type("Invoice"));
        assertThat(text(invoice, "supplier")).contains("Harbour Logistics");
        assertThat(text(invoice, "invoiceNumber")).contains("1187");
        assertThat((BigDecimal) value(invoice, "invoiceAmount")).isEqualByComparingTo("185000");
        assertThat(value(invoice, "dueDate")).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat(invoice.getDocumentDate()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    void aLeaseAgreementGetsItsTypeAndFields() {
        Document lease = fill("lease-agreement-112.txt");

        assertThat(lease.getCategory()).isEqualTo(type("Contract"));
        assertThat(value(lease, "contractKind")).isEqualTo("lease");
        assertThat(text(lease, "contractNumber")).contains("112");
        assertThat(value(lease, "validUntil")).isEqualTo(LocalDate.of(2027, 12, 31));
        assertThat(lease.getDocumentDate()).isEqualTo(LocalDate.of(2026, 3, 1));
    }

    @Test
    void aCompletionCertificateGetsItsTypeAndFields() {
        Document certificate = fill("completion-certificate-45.txt");

        assertThat(certificate.getCategory()).isEqualTo(type("Completion certificate"));
        assertThat(text(certificate, "contractor")).contains("Northbridge");
        assertThat((BigDecimal) value(certificate, "certificateAmount")).isEqualByComparingTo("640000");
        assertThat(value(certificate, "workCompletedOn")).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(text(certificate, "basisContract")).contains("77");
    }

    @Test
    void aHiringOrderGetsItsTypeAndFields() {
        Document order = fill("hiring-order-315.txt");

        assertThat(order.getCategory()).isEqualTo(type("HR document"));
        assertThat(text(order, "employee")).contains("Anna Smith");
        assertThat(text(order, "department")).contains("Logistics");
        assertThat(value(order, "hireDate")).isEqualTo(LocalDate.of(2026, 10, 5));
    }

    // a neutral name, so that the model goes by the text
    private Document fill(String sample) {
        Document document = editable(documentService.upload(sandbox,
                        List.of(TestFiles.file(tempDir, "scan-" + UUID.randomUUID() + ".txt", resource(sample))))
                .created()
                .getFirst());
        String text = documentTextReader.read(new NewVersion.FromRevision(document.getCurrentRevision()))
                .orElseThrow();

        ClassificationResult result = documentClassifier.apply(
                documentClassifier.classify(document.getName(), text), document);

        assertThat(result.typeName()).isNotNull();
        assertThat(result.skipped()).isEmpty();
        return document;
    }

    private Category type(String name) {
        return dataManager.load(Category.class)
                .query("select c from dynat_Category c where c.name = :name and c.entityType = 'Document'")
                .parameter("name", name)
                .one();
    }

    private Document editable(Document document) {
        return dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(plan -> plan.addFetchPlan(FetchPlan.BASE)
                        .add("category", FetchPlan.INSTANCE_NAME)
                        .add("currentRevision", FetchPlan.BASE))
                .hint(DynAttrQueryHints.LOAD_DYN_ATTR, true)
                .one();
    }

    private static Object value(Document document, String code) {
        return EntityValues.getValue(document, "+" + code);
    }

    private static String text(Document document, String code) {
        return Objects.toString(value(document, code), "");
    }

    private static byte[] resource(String name) {
        try (InputStream stream = LiveClassificationTest.class.getResourceAsStream(name)) {
            return Objects.requireNonNull(stream).readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
