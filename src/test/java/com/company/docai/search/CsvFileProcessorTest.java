package com.company.docai.search;

import com.company.docai.model.document.DocumentRevision;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import com.fasterxml.jackson.databind.JsonNode;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorage;
import io.jmix.core.FileStorageLocator;
import io.jmix.core.Metadata;
import io.jmix.core.metamodel.model.MetaPropertyPath;
import io.jmix.search.index.mapping.propertyvalue.impl.FilePropertyValueExtractor;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The text that the search add-on takes from a file for the index, outside the stand; no search engine is needed.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class CsvFileProcessorTest {

    @Autowired
    private FilePropertyValueExtractor extractor;

    @Autowired
    private CsvFileProcessor processor;

    @Autowired
    private FileStorageLocator fileStorageLocator;

    @Autowired
    private Metadata metadata;

    private final List<FileRef> stored = new ArrayList<>();

    @AfterEach
    void removeTheFiles() {
        FileStorage storage = fileStorageLocator.getDefault();

        stored.forEach(storage::removeFile);
    }

    @Test
    void theTextOfACsvFileIsIndexedAsTheTextOfATxtFile() {
        byte[] text = "item,price\nlease,12\n".getBytes(StandardCharsets.UTF_8);

        JsonNode table = indexed(store("Prices.csv", text));
        JsonNode note = indexed(store("Prices.txt", text));

        assertThat(table.path("_content").asText()).contains("lease");
        assertThat(note.path("_content").asText()).contains("lease");
    }

    @Test
    void aFileOfAnotherTypeNamedCsvGivesNoTextAndMeetsOnlyTheParserOfText() throws IOException {
        // the parser of archives would read the name and the text of the entry
        FileRef archive = store("Prices.csv", zipOf("lease.txt", "lease of the office"));

        JsonNode indexed = indexed(archive);

        Parser parser = processor.getParserOpt(archive)
                .orElseThrow();

        assertThat(indexed.path("_content").asText()).isEmpty();
        assertThat(parser.getSupportedTypes(new ParseContext())).isNotEmpty()
                .extracting(MediaType::getType)
                .containsOnly("text");
    }

    private FileRef store(String fileName, byte[] content) {
        FileRef file = fileStorageLocator.getDefault()
                .saveStream(fileName, new ByteArrayInputStream(content));

        stored.add(file);

        return file;
    }

    private JsonNode indexed(FileRef file) {
        DocumentRevision revision = metadata.create(DocumentRevision.class);
        revision.setFile(file);

        MetaPropertyPath path = Objects.requireNonNull(metadata.getClass(DocumentRevision.class)
                .getPropertyPath("file"));

        return extractor.getValue(revision, path, Map.of());
    }

    private static byte[] zipOf(String entry, String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(text.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        return bytes.toByteArray();
    }
}
