package com.company.docai.view.document;

import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.vaadin.flow.server.VaadinServletRequest;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.view.DialogWindow;
import io.jmix.flowui.view.View;
import io.jmix.flowui.view.ViewRegistry;
import io.jmix.flowui.view.navigation.RouteSupport;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Opens the document card in a dialog over a view, and gives the route that opens the card as a page.
 */
@Component
public class DocumentCards {

    private final DialogWindows dialogWindows;
    private final DataManager dataManager;
    private final Notifications notifications;
    private final Messages messages;
    private final ViewRegistry viewRegistry;
    private final RouteSupport routeSupport;

    public DocumentCards(DialogWindows dialogWindows, DataManager dataManager, Notifications notifications,
                         Messages messages, ViewRegistry viewRegistry, RouteSupport routeSupport) {
        this.dialogWindows = dialogWindows;
        this.dataManager = dataManager;
        this.notifications = notifications;
        this.messages = messages;
        this.viewRegistry = viewRegistry;
        this.routeSupport = routeSupport;
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
     * Opens the card of the document over the view when the document is still there for the user.
     */
    public void openIfAvailable(View<?> origin, Document document,
                                Consumer<DialogWindow.AfterCloseEvent<DocumentDetailView>> afterClose) {
        if (checkAvailable(document)) {
            open(origin, document, afterClose);
        }
    }

    /**
     * Opens the card of the document when it is still there for the user, and starts Fill with AI in it.
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
     * Checks that the user may still read the document, giving a notice when not.
     */
    public boolean checkAvailable(Document document) {
        return checkAvailable(document.getId());
    }

    /**
     * Checks that the user may read the document with the id, giving a notice when not or when no id is given.
     */
    public boolean checkAvailable(@Nullable UUID id) {
        if (id == null || find(id).isEmpty()) {
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

    /**
     * Returns the full address of the card of the document, with the host and the context path the browser uses.
     */
    public String addressOf(Document document) {
        String route = viewRegistry.getRouteConfiguration()
                .getUrl(DocumentDetailView.class, routeSupport.createRouteParameters("id", document.getId()));

        return ServletUriComponentsBuilder.fromContextPath(VaadinServletRequest.getCurrent())
                .path("/" + route)
                .toUriString();
    }
}
