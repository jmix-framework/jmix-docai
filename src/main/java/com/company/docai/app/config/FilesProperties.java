package com.company.docai.app.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Settings of document files, {@code docai.files.*}.
 *
 * @param maxFileSize   the largest file a user can upload, checked in the browser and again by the document service
 * @param maxTextLength the most characters of the text of a file that are read, none when not set
 * @param textTimeout   the longest time the text of a file may take to read, none when not set
 */
@ConfigurationProperties(prefix = "docai.files")
public record FilesProperties(@DefaultValue("20MB") DataSize maxFileSize,
                              @Nullable Integer maxTextLength,
                              @Nullable Duration textTimeout) {
}
