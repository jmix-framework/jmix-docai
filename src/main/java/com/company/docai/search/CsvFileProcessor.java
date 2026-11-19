package com.company.docai.search;

import io.jmix.core.FileRef;
import io.jmix.core.FileStorageLocator;
import io.jmix.search.utils.FileProcessor;
import org.apache.commons.io.FilenameUtils;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.CompositeParser;
import org.apache.tika.parser.Parser;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Takes a file's text for the full-text search as the add-on does, and the text of a CSV file too, which the add-on's
 * fixed list of extensions lacks; a file of another type named .csv gives no text. The stand reads the texts within
 * limits, see {@link DocumentFileProcessor}.
 */
@Primary
@Component
@Profile("!stand")
public class CsvFileProcessor extends FileProcessor {

    private static final String CSV = "csv";

    private final Parser csvParser;

    public CsvFileProcessor(FileStorageLocator fileStorageLocator) {
        super(fileStorageLocator);

        TikaConfig tika = TikaConfig.getDefaultConfig();
        CompositeParser parsers = (CompositeParser) tika.getParser();
        Parser textParser = parsers.getParsers().get(MediaType.TEXT_PLAIN);

        // the add-on passes no file name, so the type comes from the bytes; a type that is not text finds no parser
        // here, and no other parser of Tika reads the file
        csvParser = new AutoDetectParser(tika.getMimeRepository(), textParser);
    }

    @Override
    protected Optional<Parser> getParserOpt(FileRef fileRef) {
        if (CSV.equalsIgnoreCase(FilenameUtils.getExtension(fileRef.getFileName()))) {
            return Optional.of(csvParser);
        }

        return super.getParserOpt(fileRef);
    }
}
