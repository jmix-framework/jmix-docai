package com.company.docai.ai;

import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.document.NewVersion;
import com.company.docai.app.service.document.UploadedFile;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import com.company.docai.test_support.TestFiles;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ExtendWith({AuthenticatedAsAdmin.class, OutputCaptureExtension.class})
class DocumentTextReaderTest {

    private static final String LEASE_PDF = "/com/company/docai/search/lease.pdf";

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
    void createSandbox() {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("ai-text-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);
    }

    @AfterEach
    void deleteSandbox() {
        dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete);
    }

    @Test
    void theTextOfASavedVersionIsReadWithItsWhitespaceTidied() {
        Document document = documentService.upload(sandbox,
                        List.of(TestFiles.file(tempDir, "lease.txt", "Lease  of a   warehouse\n\n\n\nfor  ten years")))
                .created()
                .getFirst();

        assertThat(documentTextReader.read(new NewVersion.FromRevision(document.getCurrentRevision())))
                .contains("Lease of a warehouse\n\nfor ten years");
    }

    @Test
    void aFileUploadedIntoTheCardIsReadBeforeItIsSaved() {
        UploadedFile file = TestFiles.file(tempDir, "invoice.txt", "Invoice 1187 from Harbour Logistics LLC");

        assertThat(documentTextReader.read(new NewVersion.FromFile(file)))
                .contains("Invoice 1187 from Harbour Logistics LLC");
    }

    @Test
    void theTextOfAPdfIsRead() {
        UploadedFile file = TestFiles.file(tempDir, "lease.pdf", leasePdf());

        assertThat(documentTextReader.read(new NewVersion.FromFile(file)))
                .hasValueSatisfying(text -> assertThat(text).contains("Chamomile Trading LLC"));
    }

    @Test
    void aPictureHasNoText() {
        UploadedFile file = TestFiles.file(tempDir, "scan.png", png());

        assertThat(documentTextReader.read(new NewVersion.FromFile(file))).isEmpty();
    }

    @Test
    void aFileWithoutTextIsLoggedWithItsNameAndFormat(CapturedOutput output) {
        UploadedFile file = TestFiles.file(tempDir, "scan-0412.png", png());

        documentTextReader.read(new NewVersion.FromFile(file));

        assertThat(output).contains("scan-0412.png", "image/png");
    }

    @Test
    void aFileOfAnUnknownFormatHasNoText() {
        UploadedFile file = TestFiles.file(tempDir, "data.bin", new byte[]{0, 1, 2, 3, (byte) 0xFF, (byte) 0xFE});

        assertThat(documentTextReader.read(new NewVersion.FromFile(file))).isEmpty();
    }

    @Test
    void aBrokenFileHasNoText() {
        UploadedFile file = TestFiles.file(tempDir, "contract.pdf", "%PDF-1.7\nnot a document\n%%EOF");

        assertThat(documentTextReader.read(new NewVersion.FromFile(file))).isEmpty();
    }

    @Test
    void aLongTextIsCutToTheLengthTheModelReadsAndSaysSo() {
        UploadedFile file = TestFiles.file(tempDir, "long.txt", "word ".repeat(10_000));
        String note = "\n\n[The text stops here: only its first %d characters are read.]"
                .formatted(aiSettings.getMaxTextLength());

        assertThat(documentTextReader.read(new NewVersion.FromFile(file)))
                .hasValueSatisfying(text -> assertThat(text)
                        .startsWith("word word")
                        .endsWith(note)
                        .hasSizeLessThanOrEqualTo(aiSettings.getMaxTextLength() + note.length()));
    }

    private static byte[] leasePdf() {
        try (InputStream stream = DocumentTextReaderTest.class.getResourceAsStream(LEASE_PDF)) {
            return Objects.requireNonNull(stream).readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] png() {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "png", bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
