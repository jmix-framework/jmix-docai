package com.company.dms.test_support;

import com.company.dms.app.service.document.UploadedFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Writes test files into a temporary directory and hands them over as uploaded files.
 */
public final class TestFiles {

    private TestFiles() {
    }

    public static UploadedFile file(Path directory, String fileName, String text) {
        return file(directory, fileName, text.getBytes(StandardCharsets.UTF_8));
    }

    public static UploadedFile file(Path directory, String fileName, byte[] content) {
        try {
            Path path = Files.write(directory.resolve(UUID.randomUUID().toString()), content);
            return new UploadedFile(fileName, path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
