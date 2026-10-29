package com.company.dms.demo;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * The description of a demo set, as its {@code demo-data.json} gives it: the folders under the root with their own
 * access and their documents, some of them in the trash. Users, roles and document types are not described: they come
 * from the changelogs and the code, and the description refers to them by login, role code and the English name of the
 * type.
 *
 * @param folders the folders under the root
 */
public record DemoSet(List<Folder> folders) {

    // a list the description leaves out is empty
    public DemoSet {
        folders = folders == null ? List.of() : folders;
    }

    /**
     * A folder of the set.
     *
     * @param name      the name of the folder
     * @param access    the own access of the folder, or null when it inherits the access of its parent
     * @param folders   the subfolders
     * @param documents the documents of the folder
     * @param deleted   who deleted the folder and when, or null for a live folder
     */
    public record Folder(String name, @Nullable List<Access> access, List<Folder> folders, List<Document> documents,
                         @Nullable Deletion deleted) {

        public Folder {
            folders = folders == null ? List.of() : folders;
            documents = documents == null ? List.of() : documents;
        }
    }

    /**
     * An entry of an own access: a user or a role, and its level.
     *
     * @param user  the login of the user, or null for a role
     * @param role  the code of the role, or null for a user
     * @param level {@code VIEW}, {@code EDIT} or {@code MANAGE}
     */
    public record Access(@Nullable String user, @Nullable String role, String level) {
    }

    /**
     * A document of the set.
     *
     * @param name     the name of the document
     * @param date     the date of the document
     * @param type     the English name of the document type, or null for a document without a type
     * @param fields   the values of the fields of the type by the code of the field, as text
     * @param status   {@code DRAFT} (when left out), {@code ACTIVE} or {@code ARCHIVED}
     * @param access   the own access of the document, or null when it inherits the access of its folder
     * @param versions the versions, the first one first
     * @param deleted  who deleted the document and when, or null for a live document
     */
    public record Document(String name, @Nullable LocalDate date, @Nullable String type, Map<String, String> fields,
                           @Nullable String status, @Nullable List<Access> access, List<Version> versions,
                           @Nullable Deletion deleted) {

        public Document {
            fields = fields == null ? Map.of() : fields;
            versions = versions == null ? List.of() : versions;
        }
    }

    /**
     * A version of a document.
     *
     * @param file     the name of the file in the {@code files} folder of the set
     * @param author   the login of the user who uploads the version
     * @param uploaded when the version was uploaded, in the time zone of the server
     */
    public record Version(String file, String author, LocalDateTime uploaded) {
    }

    /**
     * The deletion of a folder or a document that lies in the trash.
     *
     * @param by the login of the user who deleted it
     * @param at when it was deleted, in the time zone of the server
     */
    public record Deletion(String by, LocalDateTime at) {
    }
}
