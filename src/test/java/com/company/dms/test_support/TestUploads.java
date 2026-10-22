package com.company.dms.test_support;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.upload.AllFinishedEvent;
import io.jmix.flowui.component.upload.JmixUpload;
import io.jmix.flowui.component.upload.UploadSucceededEvent;
import io.jmix.flowui.upload.TemporaryStorage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Plays the server side of a finished browser upload: a file in the temporary storage and the
 * events the upload component fires for it.
 */
public final class TestUploads {

    private TestUploads() {
    }

    /**
     * Stores the text as a new temporary file and fires the success event for it under the given name.
     */
    public static TemporaryStorage.FileInfo succeed(JmixUpload<TemporaryStorage.FileInfo> upload,
                                                    TemporaryStorage temporaryStorage,
                                                    String fileName, String text) {
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        TemporaryStorage.FileInfo fileInfo = temporaryStorage.createFile();
        try {
            Files.write(fileInfo.getFile().toPath(), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        ComponentUtil.fireEvent(upload, new UploadSucceededEvent<>(upload, fileInfo, fileName,
                "application/octet-stream", content.length));

        return fileInfo;
    }

    /**
     * Fires the event that follows the last file of a batch.
     */
    public static void finishAll(JmixUpload<?> upload) {
        ComponentUtil.fireEvent(upload, new AllFinishedEvent(upload));
    }
}
