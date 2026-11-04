package com.company.docai.view.document;

import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentRevision;
import com.company.docai.view.favorite.FavoriteListAction;
import com.company.docai.view.folder.FolderRenderers;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.LitRenderer;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.data.renderer.TextRenderer;
import com.vaadin.flow.spring.annotation.SpringComponent;
import io.jmix.core.Messages;
import io.jmix.core.metamodel.datatype.DatatypeFormatter;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Renderers of documents and their versions in grids, and dates and times in the user's time zone.
 * A view keeps the state its cells show, such as the marks and the names of the users, and passes lookups that read
 * that state when a cell is drawn.
 */
@SpringComponent
public class DocumentRenderers {

    private final UiComponents uiComponents;
    private final Icons icons;
    private final Messages messages;
    private final FolderRenderers folderRenderers;
    private final FileSizeFormatter fileSizeFormatter;
    private final DatatypeFormatter datatypeFormatter;
    private final CurrentAuthentication currentAuthentication;

    public DocumentRenderers(UiComponents uiComponents, Icons icons, Messages messages, FolderRenderers folderRenderers,
                             FileSizeFormatter fileSizeFormatter, DatatypeFormatter datatypeFormatter,
                             CurrentAuthentication currentAuthentication) {
        this.uiComponents = uiComponents;
        this.icons = icons;
        this.messages = messages;
        this.folderRenderers = folderRenderers;
        this.fileSizeFormatter = fileSizeFormatter;
        this.datatypeFormatter = datatypeFormatter;
        this.currentAuthentication = currentAuthentication;
    }

    /**
     * The renderer of the name of a document: the icon of its file, the name, the star when the user has marked the
     * document and the key when it has its own access.
     */
    public Renderer<Document> nameRenderer(Predicate<Document> marked, Predicate<Document> ownAccess) {
        String starLabel = messages.getMessage(FavoriteListAction.class, "markedStar.label");

        return new ComponentRenderer<>(document -> {
            Span name = uiComponents.create(Span.class);
            name.addClassName("name-with-icon");
            Span text = uiComponents.create(Span.class);
            text.addClassName("name-text");
            text.setText(document.getName());
            name.add(icons.get(fileIcon(document)), text);

            // after the name: a long name ends with an ellipsis, and the star stays in sight; a screen reader names
            // it, as the key of an own access
            if (marked.test(document)) {
                Component star = FavoriteListAction.star(icons, true);
                star.getElement().setAttribute("role", "img");
                star.getElement().setAttribute("aria-label", starLabel);
                name.add(star);
            }

            if (ownAccess.test(document)) {
                folderRenderers.markOwnAccess(name);
            }

            return name;
        });
    }

    /**
     * The renderer of who changed the document last and when. Who and when share one column, as in the card, in the
     * user's time zone: the date in the cell, the time in its title; a tooltip generator of a grid column throws in
     * the browser of Vaadin 25.3 when a tooltip has no row.
     */
    public Renderer<Document> changedRenderer(Function<String, String> userName) {
        return LitRenderer.<Document>of("<span title=\"${item.time}\">${item.text}</span>")
                .withProperty("text", document -> {
                    if (document.getChangedDate() == null) {
                        return "";
                    }

                    String date = userDate(document.getChangedDate());

                    return document.getChangedBy() == null
                            ? date
                            : userName.apply(document.getChangedBy()) + " · " + date;
                })
                .withProperty("time", document -> document.getChangedDate() == null
                        ? ""
                        : userDateTime(document.getChangedDate()));
    }

    /**
     * The renderer of the size of the current version of a document.
     */
    public Renderer<Document> fileSizeRenderer() {
        return new TextRenderer<>(document -> document.getCurrentRevision() == null
                ? ""
                : fileSizeFormatter.format(document.getCurrentRevision().getFileSize()));
    }

    /**
     * The renderer of the size of a version.
     */
    public Renderer<DocumentRevision> revisionSizeRenderer() {
        return new TextRenderer<>(revision -> fileSizeFormatter.format(revision.getFileSize()));
    }

    /**
     * The renderer of who uploaded a version.
     */
    public Renderer<DocumentRevision> revisionAuthorRenderer(Function<String, String> userName) {
        return new TextRenderer<>(revision -> revision.getCreatedBy() == null
                ? ""
                : userName.apply(revision.getCreatedBy()));
    }

    /**
     * The renderer of a date and a time in the user's time zone, an empty text for no date.
     */
    public <T> Renderer<T> userDateTimeRenderer(Function<T, @Nullable OffsetDateTime> date) {
        return new TextRenderer<>(item -> {
            OffsetDateTime value = date.apply(item);

            return value == null ? "" : userDateTime(value);
        });
    }

    /**
     * Returns the number, the file name and the size of the version, or an empty text for no version.
     */
    public String versionText(@Nullable DocumentRevision revision) {
        return revision == null
                ? ""
                : "%d · %s · %s".formatted(revision.getNumber(), revision.getFile().getFileName(),
                        fileSizeFormatter.format(revision.getFileSize()));
    }

    /**
     * Returns the date of the moment in the user's time zone.
     */
    public String userDate(OffsetDateTime date) {
        return datatypeFormatter.formatLocalDate(inUserZone(date).toLocalDate());
    }

    /**
     * Returns the date and the time of the moment in the user's time zone.
     */
    public String userDateTime(OffsetDateTime date) {
        return datatypeFormatter.formatLocalDateTime(inUserZone(date));
    }

    private LocalDateTime inUserZone(OffsetDateTime date) {
        return date.atZoneSameInstant(currentAuthentication.getTimeZone().toZoneId()).toLocalDateTime();
    }

    private static JmixFontIcon fileIcon(Document document) {
        DocumentRevision revision = document.getCurrentRevision();

        return switch (PreviewType.of(revision == null ? null : revision.getContentType())) {
            case IMAGE -> JmixFontIcon.FILE_PICTURE;
            case PDF, TEXT -> JmixFontIcon.FILE_TEXT;
            case NONE -> JmixFontIcon.FILE;
        };
    }
}
