package com.company.docai.stand;

import com.company.docai.app.service.OperationRejectedException;
import io.jmix.core.FileRef;
import io.jmix.localfs.LocalFileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The stand's file storage under the name of Jmix's local storage, so the file references in the rows stay valid.
 */
public class StandFileStorage extends LocalFileStorage {

    /**
     * The name of the folder of the files in the folder of a scope.
     */
    public static final String FILES = "files";

    private static final Logger log = LoggerFactory.getLogger(StandFileStorage.class);

    private static final String COPIES = "copies";
    private static final String TEMPLATES = "templates";

    private final StandProperties properties;
    private final StandUploadGuard uploadGuard;

    public StandFileStorage(StandProperties properties, StandUploadGuard uploadGuard) {
        this.properties = properties;
        this.uploadGuard = uploadGuard;
    }

    /**
     * Stores the file in the folder of the thread's scope if the disk, the type of its bytes and the copy's quota allow
     * it: every writer of a copy comes here, not every writer asks the guard first. A template takes any file, and a
     * thread without a scope none.
     *
     * @throws OperationRejectedException when the copy does not take the file, which is gone then
     */
    @Override
    public long saveStream(FileRef fileRef, InputStream inputStream) {
        StandScope scope = StandScopeHolder.resolve();

        if (scope == null || !scope.isCopy()) {
            return super.saveStream(fileRef, inputStream);
        }

        uploadGuard.checkDisk(fileRef.getFileName());
        long size = super.saveStream(fileRef, inputStream);

        Path stored = getStorageRoots()[0]
                .resolve(getRelativePath(fileRef.getPath()));

        try {
            uploadGuard.checkStored(scope, stored, fileRef.getFileName());
        } catch (RuntimeException e) {
            try {
                removeFile(fileRef);
            } catch (RuntimeException removal) {
                e.addSuppressed(removal);
            }

            throw e;
        }

        return size;
    }

    /**
     * The folder of the files of the thread's scope, used for every file operation; a thread without a scope fails.
     */
    @Override
    protected Path[] getStorageRoots() {
        StandScope scope = StandScopeHolder.resolve();

        if (scope == null) {
            throw new IllegalStateException("A thread without a scope of the stand has no files");
        }

        return new Path[]{filesOf(scope)};
    }

    /**
     * Gives a new copy the files of the template of the language: a hard link to each file, so that the copy takes no
     * disk space until its visitor uploads; a file that cannot be linked, as on another file system, is copied.
     */
    public void linkTemplate(String language, StandScope copy) throws IOException {
        Path template = filesOf(StandScope.template(language));
        Path files = filesOf(copy);
        Files.createDirectories(files);

        if (!Files.isDirectory(template)) {
            return;
        }

        Files.walkFileTree(template, new SimpleFileVisitor<>() {

            private boolean copied;

            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                Files.createDirectories(files.resolve(template.relativize(directory)));

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isRegularFile()) {
                    link(file, files.resolve(template.relativize(file)));
                }

                return FileVisitResult.CONTINUE;
            }

            private void link(Path file, Path link) throws IOException {
                try {
                    Files.createLink(link, file);
                } catch (IOException | UnsupportedOperationException e) {
                    Files.copy(file, link);

                    if (!copied) {
                        log.warn("The files of the template {} are copied into {}, not linked: {}", language,
                                copy.tag(), StandScope.withTags(e.toString()));
                    }

                    copied = true;
                }
            }
        });
    }

    /**
     * Deletes the folder of the copy with everything in it: its links go, the files of its template stay. It takes
     * nothing but the folder of a well-formed key right in the folder of the copies.
     *
     * @throws IllegalArgumentException for a template, or for a key that is not well-formed
     */
    public void deleteFolder(StandScope copy) throws IOException {
        if (!copy.isCopy()) {
            throw new IllegalArgumentException("Only the folder of a copy is deleted: " + copy.tag());
        }

        deleteChild(COPIES, StandScope.copy(copy.key()));
    }

    /**
     * Deletes the folder of the template before the template is built again; the copies made of that build keep their
     * files, which are their own links. It takes nothing but the folder of a well-formed language right in the folder
     * of the templates.
     *
     * @throws IllegalArgumentException for a copy, or for a language that is not well-formed
     */
    public void deleteTemplateFolder(StandScope template) throws IOException {
        if (!template.isTemplate()) {
            throw new IllegalArgumentException("Only the folder of a template is cleared: " + template.id());
        }

        deleteChild(TEMPLATES, StandScope.template(template.language()));
    }

    /**
     * Deletes the folders of the copies an earlier run of the application left; anything else in the folder of the
     * copies stays.
     *
     * @param live whether a key belongs to a copy that exists now
     * @return how many folders are deleted
     */
    public int deleteFoldersOfGoneCopies(Predicate<String> live) throws IOException {
        Path copies = properties.dataDir()
                .resolve(COPIES);

        if (!Files.isDirectory(copies)) {
            return 0;
        }

        List<StandScope> gone = new ArrayList<>();

        try (DirectoryStream<Path> folders = Files.newDirectoryStream(copies,
                folder -> Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS))) {
            for (Path folder : folders) {
                String key = folder.getFileName()
                        .toString();

                if (StandScope.isKey(key) && !live.test(key)) {
                    gone.add(StandScope.copy(key));
                }
            }
        }

        for (StandScope copy : gone) {
            deleteFolder(copy);
        }

        return gone.size();
    }

    // the only recursive deletes of the stand: the folder of a scope whose key or language is checked again, right in
    // the folder of its kind
    private void deleteChild(String kind, StandScope scope) throws IOException {
        Path root = properties.dataDir()
                .resolve(kind)
                .toAbsolutePath()
                .normalize();

        Path folder = scope.folder(properties.dataDir())
                .toAbsolutePath()
                .normalize();

        // unreachable while StandScope checks its ids: kept as the last fence of a recursive delete
        if (!root.equals(folder.getParent())) {
            throw new IllegalArgumentException("Not a folder of the " + kind + ": " + scope.tag());
        }

        FileSystemUtils.deleteRecursively(folder);
    }

    // a scope also comes from an attribute of the HTTP session, so its folder is made from its key or language again
    private Path filesOf(StandScope scope) {
        StandScope checked = scope.isCopy()
                ? StandScope.copy(scope.key())
                : StandScope.template(scope.language());

        return checked.folder(properties.dataDir())
                .resolve(FILES);
    }
}
