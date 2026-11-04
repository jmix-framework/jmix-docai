package com.company.docai.view.repository;

import com.company.docai.app.service.access.AccessService;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.view.folder.FolderLookupView;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.kit.action.ActionVariant;
import io.jmix.flowui.view.DialogWindow;
import io.jmix.flowui.view.View;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.function.Consumer;

/**
 * Moves of documents and folders in the repository, from the menus and by drag and drop: the choice of the target
 * folder, the question when a move changes the access, the move and its notice. The views pass what happens after
 * a move: the list shows its documents again, the tree reloads and selects the moved folder.
 */
@Component
public class RepositoryMoves {

    private final DialogWindows dialogWindows;
    private final Dialogs dialogs;
    private final Notifications notifications;
    private final Messages messages;
    private final MetadataTools metadataTools;
    private final AccessService accessService;
    private final DocumentService documentService;
    private final FolderService folderService;

    public RepositoryMoves(DialogWindows dialogWindows, Dialogs dialogs, Notifications notifications, Messages messages,
                           MetadataTools metadataTools, AccessService accessService, DocumentService documentService,
                           FolderService folderService) {
        this.dialogWindows = dialogWindows;
        this.dialogs = dialogs;
        this.notifications = notifications;
        this.messages = messages;
        this.metadataTools = metadataTools;
        this.accessService = accessService;
        this.documentService = documentService;
        this.folderService = folderService;
    }

    /**
     * Opens the folder lookup over the view and passes on the folder the user picks. The hidden folders and their
     * subfolders are not offered.
     */
    public void chooseTarget(View<?> origin, Collection<Folder> hidden, Consumer<Folder> chosen) {
        DialogWindow<FolderLookupView> window = dialogWindows.lookup(origin, Folder.class)
                .withViewClass(FolderLookupView.class)
                .withSelectHandler(targets -> chosen.accept(targets.iterator().next()))
                .build();

        window.getView().setHiddenFolders(hidden);
        window.open();
    }

    /**
     * Moves the documents into the target folder: a move that changes the access of the documents asks first. After
     * the move comes the caller's work, then a notice, unless the target is the folder the list shows.
     */
    public void move(Collection<Document> documents, @Nullable Folder from, Folder target, Runnable afterMove) {
        Runnable move = () -> {
            documentService.move(documents, target);
            afterMove.run();

            // the documents of the shown folder stay where they are when it is the target
            if (!target.equals(from)) {
                notifications.create(messages.formatMessage(getClass(), "documentsMoved",
                                metadataTools.getInstanceName(target), documents.size()))
                        .withType(Notifications.Type.SUCCESS)
                        .show();
            }
        };

        String question = messages.formatMessage(getClass(), "moveDocumentsChangeAccessDialog.text",
                metadataTools.getInstanceName(target));

        askFirst(accessService.changesOnMove(documents, target), question, move);
    }

    /**
     * Moves the folder into the target folder: a move that changes the access of the folder asks first. After the
     * move comes the caller's work, then a notice, unless the folder went back into its parent.
     */
    public void move(Folder folder, Folder target, Runnable afterMove) {
        Runnable move = () -> {
            folderService.move(folder, target);
            afterMove.run();

            // a folder put back into its parent stays where it was
            if (!target.equals(folder.getParent())) {
                notifications.create(messages.formatMessage(getClass(), "folderMoved",
                                metadataTools.getInstanceName(folder), metadataTools.getInstanceName(target)))
                        .withType(Notifications.Type.SUCCESS)
                        .show();
            }
        };

        String question = messages.formatMessage(getClass(), "moveFolderChangesAccessDialog.text",
                metadataTools.getInstanceName(folder), metadataTools.getInstanceName(target));

        askFirst(accessService.changesOnMove(folder, target), question, move);
    }

    private void askFirst(boolean changesAccess, String question, Runnable move) {
        if (!changesAccess) {
            move.run();
            return;
        }

        dialogs.createOptionDialog()
                .withHeader(messages.getMessage(getClass(), "moveChangesAccessDialog.header"))
                .withText(question)
                .withActions(
                        new DialogAction(DialogAction.Type.YES)
                                .withVariant(ActionVariant.PRIMARY)
                                .withHandler(actionEvent -> move.run()),
                        new DialogAction(DialogAction.Type.NO))
                .open();
    }
}
