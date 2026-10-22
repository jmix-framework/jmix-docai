package com.company.dms.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Settings of document files, {@code dms.files.*}.
 *
 * @param maxFileSize the largest file a user can upload: the upload components check it in the browser,
 *                    and the document service checks it again
 */
@ConfigurationProperties(prefix = "dms.files")
public record FilesProperties(@DefaultValue("20MB") DataSize maxFileSize) {
}
