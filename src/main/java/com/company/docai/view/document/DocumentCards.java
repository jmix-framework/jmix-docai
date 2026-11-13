package com.company.docai.view.document;

import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.view.DialogWindow;
import io.jmix.flowui.view.View;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Opens the document card in a dialog over a view, and checks that a document is still there for the user before
 * a card or a link shows it. Each caller passes what happens after the card closes.
 */
@Component
public class DocumentCards {

    private final DialogWindows dialogWindows;
    private final DataManager dataManager;
    private final Notifications notifications;
    private final Messages messages;

    public DocumentCards(DialogWindows dialogWindows, DataManager dataManager, Notifications notifications,
                         Messages messages) {
        this.dialogWindows = dialogWindows;
        this.dataManager = dataManager;
        this.notifications = notifications;
        this.messages = messages;
    }

    /**
     * Opens the card of the document over the view.
     */
    public void open(View<?> origin, Document document,
                     Consumer<DialogWindow.AfterCloseEvent<DocumentDetailView>> afterClose) {
        dialogWindows.detail(origin, Document.class)
                .withViewClass(DocumentDetailView.class)
                .editEntity(document)
                .withAfterCloseListener(afterClose)
                .open();
    }

    /**
     * Opens the card of the document over the view when the document is still there for the user, see
     * {@link #checkAvailable(Document)}.
     */
    public void openIfAvailable(View<?> origin, Document document,
                                Consumer<DialogWindow.AfterCloseEvent<DocumentDetailView>> afterClose) {
        if (checkAvailable(document)) {
            open(origin, document, afterClose);
        }
    }

    /**
     * Opens the card of the document over the view when the document is still there for the user, see
     * {@link #checkAvailable(Document)}, and starts Fill with AI in it, as the button of the card does.
     */
    public void fillWithAiIfAvailable(View<?> origin, Document document,
                                      Consumer<DialogWindow.AfterCloseEvent<DocumentDetailView>> afterClose) {
        if (checkAvailable(document)) {
            dialogWindows.detail(origin, Document.class)
                    .withViewClass(DocumentDetailView.class)
                    .editEntity(document)
                    .withAfterOpenListener(openEvent -> openEvent.getView().fillWithAi())
                    .withAfterCloseListener(afterClose)
                    .open();
        }
    }

    /**
     * Opens the card of a new document over the view; the document goes into the folder.
     */
    public void create(View<?> origin, @Nullable Folder folder,
                       Consumer<DialogWindow.AfterCloseEvent<DocumentDetailView>> afterClose) {
        dialogWindows.detail(origin, Document.class)
                .withViewClass(DocumentDetailView.class)
                .newEntity()
                .withInitializer(document -> document.setFolder(folder))
                .withAfterCloseListener(afterClose)
                .open();
    }

    /**
     * Checks that the user may still read the document. A document deleted or hidden from the user since it was
     * shown gives a notice and false.
     */
    public boolean checkAvailable(Document document) {
        if (dataManager.load(Id.of(document)).optional().isEmpty()) {
            notifications.create(messages.getMessage(getClass(), "documentUnavailable"))
                    .withType(Notifications.Type.WARNING)
                    .show();

            return false;
        }

        return true;
    }

    /**
     * Finds the document by its id as the user may read it, without a notice.
     */
    public Optional<Document> find(UUID id) {
        return dataManager.load(Document.class)
                .id(id)
                .optional();
    }
}
