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
import io.jmix.search.utils.FileProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import static com.company.docai.test_support.TestSamples.png;
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

    @Autowired
    private FileProcessor fileProcessor;

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("filesWithoutText")
    void aFileWhoseTextCannotBeReadHasNoText(String fileName, byte[] content) {
        UploadedFile file = TestFiles.file(tempDir, fileName, content);

        assertThat(documentTextReader.read(new NewVersion.FromFile(file))).isEmpty();
    }

    @Test
    void aFileWithoutTextIsLoggedWithItsNameAndFormat(CapturedOutput output) {
        UploadedFile file = TestFiles.file(tempDir, "scan-0412.png", png());

        documentTextReader.read(new NewVersion.FromFile(file));

        assertThat(output).contains("scan-0412.png", "image/png");
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

    // only the stand reads the texts of files for the search within limits, see StandUploadTypesTest
    @Test
    void outsideTheStandTheSearchAddOnReadsTheTextsOfFilesItself() {
        assertThat(fileProcessor.getClass()).isEqualTo(FileProcessor.class);
    }

    private static byte[] leasePdf() {
        try (InputStream stream = DocumentTextReaderTest.class.getResourceAsStream(LEASE_PDF)) {
            return Objects.requireNonNull(stream).readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Stream<Arguments> filesWithoutText() {
        return Stream.of(
                Arguments.of(Named.of("a picture", "scan.png"), png()),
                Arguments.of(Named.of("an unknown format", "data.bin"),
                        new byte[]{0, 1, 2, 3, (byte) 0xFF, (byte) 0xFE}),
                Arguments.of(Named.of("a broken file", "contract.pdf"),
                        "%PDF-1.7\nnot a document\n%%EOF".getBytes(StandardCharsets.UTF_8)));
    }
}
