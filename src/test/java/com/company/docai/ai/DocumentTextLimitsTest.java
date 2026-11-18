package com.company.docai.ai;

import com.company.docai.app.config.FilesProperties;
import com.company.docai.app.service.document.NewVersion;
import com.company.docai.app.service.document.UploadedFile;
import com.company.docai.search.DocumentFileProcessor;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestSamples;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorage;
import io.jmix.core.FileStorageLocator;
import io.jmix.search.exception.UnsupportedFileFormatException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The limits of reading the text of a file, set low here; the files are built in the test.
 */
@ExtendWith(OutputCaptureExtension.class)
class DocumentTextLimitsTest {

    private static final int MAX_TEXT_LENGTH = 1000;
    private static final Duration TIMEOUT = Duration.ofMillis(300);

    private final FileStorage storage = mock(FileStorage.class);
    private final FileStorageLocator fileStorageLocator = mock(FileStorageLocator.class);

    @TempDir
    private Path tempDir;

    private DocumentTextReader reader;
    private DocumentFileProcessor processor;

    @BeforeEach
    void createTheReader() {
        doReturn(storage)
                .when(fileStorageLocator)
                .getByName("fs");

        reader = new DocumentTextReader(fileStorageLocator, new AiSettings("", true, 12_000),
                new FilesProperties(DataSize.ofMegabytes(20), MAX_TEXT_LENGTH, TIMEOUT));

        processor = new DocumentFileProcessor(fileStorageLocator, reader);
    }

    @AfterEach
    void stopTheReader() {
        reader.shutdown();
    }

    // the limit is below the length the model reads, so the limit cuts the text
    @Test
    void theModelReadsTheBeginningOfATextOverTheLimit() {
        UploadedFile file = TestFiles.file(tempDir, "Long.txt", "word ".repeat(300));
        String note = "\n\n[The text stops here: only its first %d characters are read.]".formatted(MAX_TEXT_LENGTH);

        assertThat(reader.read(new NewVersion.FromFile(file)))
                .hasValueSatisfying(text -> assertThat(text)
                        .startsWith("word word")
                        .endsWith(note)
                        .hasSizeLessThanOrEqualTo(MAX_TEXT_LENGTH + note.length()));
    }

    @Test
    void theSearchTakesNoTextOfAFileWithATextOverTheLimit(CapturedOutput output) throws Exception {
        FileRef file = stored("Long.txt", "word ".repeat(300));

        assertThat(processor.extractFileContent(file)).isEmpty();
        assertThat(output).contains("Cannot read the text of Long.txt: it is longer than 1000 characters");
    }

    @Test
    void theSearchTakesTheWholeTextUnderTheLimit() throws Exception {
        String text = "word ".repeat(120)
                .strip();

        FileRef file = stored("Short.txt", text);

        assertThat(processor.extractFileContent(file).strip()).isEqualTo(text);
    }

    // a zip bomb in small: a document of a few hundred bytes whose text runs far past the limit
    @Test
    void aDocxWhoseTextRunsPastTheLimitHasNoTextForTheSearch(CapturedOutput output) throws Exception {
        FileRef file = stored("Memo.docx", TestSamples.docx("bomb ".repeat(2000)));

        assertThat(processor.extractFileContent(file)).isEmpty();
        assertThat(output).contains("Cannot read the text of Memo.docx: it is longer than 1000 characters");
    }

    // a reader that waits for the stalled file fails the test instead of hanging the run
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aParseOverTheTimeLimitLeavesNoTextAndTheReaderAnswers(CapturedOutput output) throws Exception {
        StalledStream stalled = new StalledStream();
        FileRef file = new FileRef("fs", "2026/11/17/stalled.txt", "Stalled.txt");

        when(storage.openStream(file))
                .thenReturn(stalled);

        long start = System.nanoTime();
        String text = processor.extractFileContent(file);
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(text).isEmpty();
        assertThat(took).isLessThan(TIMEOUT.plusSeconds(5));
        assertThat(output).contains("Cannot read the text of Stalled.txt: its parse takes longer than 300 ms");
        // the reader gives the file up, which stops a parse that still reads it
        assertThat(stalled.isClosed()).isTrue();
        assertThat(processor.extractFileContent(stored("Next.txt", "The next note"))).contains("The next note");
    }

    // the add-on picks the files by their extension, the reader parses them by their content
    @Test
    void theAddOnStillDecidesWhichFilesHaveTextForTheSearch() throws Exception {
        FileRef picture = stored("Plan.png", TestSamples.png());
        FileRef memo = stored("Memo.doc", TestSamples.docx("Memo on the lease"));

        assertThatThrownBy(() -> processor.extractFileContent(picture))
                .isInstanceOf(UnsupportedFileFormatException.class);
        assertThat(processor.extractFileContent(memo)).contains("Memo on the lease");
    }

    private FileRef stored(String fileName, String text) {
        return stored(fileName, text.getBytes(StandardCharsets.UTF_8));
    }

    private FileRef stored(String fileName, byte[] content) {
        FileRef file = new FileRef("fs", "2026/11/17/" + fileName, fileName);

        when(storage.openStream(file))
                .thenAnswer(invocation -> new ByteArrayInputStream(content));

        return file;
    }

    // a file whose bytes never come, as with a parse that hangs; it ignores an interrupt and gives up when closed
    private static final class StalledStream extends InputStream {

        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            boolean interrupted = false;

            while (closed.getCount() > 0) {
                try {
                    closed.await();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }

            if (interrupted) {
                Thread.currentThread().interrupt();
            }

            throw new IOException("The file is closed");
        }

        @Override
        public void close() {
            closed.countDown();
        }

        private boolean isClosed() {
            return closed.getCount() == 0;
        }
    }
}
