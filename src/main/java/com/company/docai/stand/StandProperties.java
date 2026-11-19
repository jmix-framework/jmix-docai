package com.company.docai.stand;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Settings of the public stand, {@code docai.stand.*}; set only by the {@code stand} profile.
 *
 * @param copyIdleTimeout       how long a copy of the demo data may stay unused before it is deleted
 * @param copyUploadQuota       the total size of the files a visitor can upload into their copy
 * @param templateLanguages     the languages of the demo sets that are built into templates at the start, in that order
 * @param uploadTypes           the media types a visitor can upload, told by the bytes of a file; the file pickers
 *                              offer the extensions {@link StandUploadGuard} gives each of them
 * @param minFreeDisk           the free space on the disk of the data folder below which no visitor can upload
 * @param dataDir               the folder of the templates and the copies, with their files
 * @param templateRetryPause    how long the start waits before it tries again a template that failed for the first
 *                              time; the pause doubles with each failure that follows
 * @param templateRetryPauseMax the longest pause between two tries of a template, see {@link StandStartup}
 * @param autoStartup           whether the stand starts by itself once the application is ready; the tests start it
 *                              themselves
 */
@ConfigurationProperties("docai.stand")
public record StandProperties(Duration copyIdleTimeout,
                              DataSize copyUploadQuota,
                              List<String> templateLanguages,
                              List<String> uploadTypes,
                              DataSize minFreeDisk,
                              Path dataDir,
                              Duration templateRetryPause,
                              Duration templateRetryPauseMax,
                              @DefaultValue("true") boolean autoStartup) {
}
