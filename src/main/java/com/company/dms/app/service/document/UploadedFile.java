package com.company.dms.app.service.document;

import java.nio.file.Path;

/**
 * A file received from the user and kept in a temporary location until a service stores it.
 */
public record UploadedFile(String fileName, Path content) {
}
