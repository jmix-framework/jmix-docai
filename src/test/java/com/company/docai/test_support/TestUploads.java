package com.company.docai.test_support;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.upload.AllFinishedEvent;
import com.vaadin.flow.component.upload.FailedEvent;
import com.vaadin.flow.component.upload.StartedEvent;
import io.jmix.flowui.component.upload.JmixUpload;
import io.jmix.flowui.component.upload.UploadSucceededEvent;
import io.jmix.flowui.upload.TemporaryStorage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Plays the server side of browser uploads: the events the upload component fires and the files
 * it puts into the temporary storage.
 */
public final class TestUploads {

    private static final String CONTENT_TYPE = "application/octet-stream";

    private TestUploads() {
    }

    /**
     * A whole successful upload: the browser starts to send the file, and the file arrives.
     */
    public static TemporaryStorage.FileInfo succeed(JmixUpload<TemporaryStorage.FileInfo> upload,
                                                    TemporaryStorage temporaryStorage,
                                                    String fileName, String text) {
        start(upload, fileName);
        return receive(upload, temporaryStorage, fileName, text);
    }

    /**
     * Fires the event that comes when the browser starts to send the file.
     */
    public static void start(JmixUpload<?> upload, String fileName) {
        ComponentUtil.fireEvent(upload, new StartedEvent(upload, fileName, CONTENT_TYPE, 0));
    }

    /**
     * Stores the text as a new temporary file and fires the success event for it under the given name.
     */
    public static TemporaryStorage.FileInfo receive(JmixUpload<TemporaryStorage.FileInfo> upload,
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
                CONTENT_TYPE, content.length));

        return fileInfo;
    }

    /**
     * Fires the event of a transfer that broke off.
     */
    public static void fail(JmixUpload<?> upload, String fileName) {
        ComponentUtil.fireEvent(upload, new FailedEvent(upload, fileName, CONTENT_TYPE, 0,
                new IOException("The connection was reset")));
    }

    /**
     * Fires the event that follows the last file of a batch.
     */
    public static void finishAll(JmixUpload<?> upload) {
        ComponentUtil.fireEvent(upload, new AllFinishedEvent(upload));
    }
}
