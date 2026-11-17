package com.company.docai.app.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Settings of document files, {@code docai.files.*}.
 *
 * @param maxFileSize   the largest file a user can upload: the upload components check it in the browser,
 *                      and the document service checks it again
 * @param maxTextLength the most characters of the text of a file that are read, none when not set: the full-text
 *                      search of the stand takes no text of a file with a longer one, and the AI reads at most its
 *                      beginning; the stand sets it
 * @param textTimeout   the longest time the text of a file may take to read, none when not set; a file that takes
 *                      longer has no text; the stand sets it
 */
@ConfigurationProperties(prefix = "docai.files")
public record FilesProperties(@DefaultValue("20MB") DataSize maxFileSize,
                              @Nullable Integer maxTextLength,
                              @Nullable Duration textTimeout) {
}
