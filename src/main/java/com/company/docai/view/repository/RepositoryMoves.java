package com.company.docai.view.repository;

import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.access.AccessService;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.service.trash.TrashService;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.view.folder.FolderLookupView;
import com.company.docai.view.trash.TrashView;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.kit.action.ActionVariant;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.view.DialogWindow;
import io.jmix.flowui.view.View;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Moves of documents and folders in the repository, from the menus and by drag and drop, and their moves to the trash,
 * which a notice offers to undo.
 */
@Component
public class RepositoryMoves {

    private static final int TRASH_NOTICE_DURATION = 8000;
    private static final String UNDO_NOTICE_KEY = "docai.undoNotice";

    private final DialogWindows dialogWindows;
    private final Dialogs dialogs;
    private final Notifications notifications;
    private final Messages messages;
    private final MetadataTools metadataTools;
    private final AccessService accessService;
    private final DocumentService documentService;
    private final FolderService folderService;
    private final TrashService trashService;
    private final UiComponents uiComponents;
    private final ViewNavigators viewNavigators;

    public RepositoryMoves(DialogWindows dialogWindows, Dialogs dialogs, Notifications notifications, Messages messages,
                           MetadataTools metadataTools, AccessService accessService, DocumentService documentService,
                           FolderService folderService, TrashService trashService, UiComponents uiComponents,
                           ViewNavigators viewNavigators) {
        this.dialogWindows = dialogWindows;
        this.dialogs = dialogs;
        this.notifications = notifications;
        this.messages = messages;
        this.metadataTools = metadataTools;
        this.accessService = accessService;
        this.documentService = documentService;
        this.folderService = folderService;
        this.trashService = trashService;
        this.uiComponents = uiComponents;
        this.viewNavigators = viewNavigators;
    }

    /**
     * Opens the folder lookup over the view without the hidden folders and passes on the folder the user picks.
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
     * Moves the documents into the target folder, asking first when the access changes; the caller's work comes before
     * the notice.
     */
    public void move(Collection<Document> documents, @Nullable Folder from, Folder target, Runnable afterMove) {
        Runnable move = () -> {
            documentService.move(documents, target);
            afterMove.run();

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
     * Moves the folder into the target folder, asking first when the access changes; the caller's work comes before the
     * notice.
     */
    public void move(Folder folder, Folder target, Runnable afterMove) {
        Runnable move = () -> {
            folderService.move(folder, target);
            afterMove.run();

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

    /**
     * Moves the documents to the trash and offers to undo it; the caller's work comes before the notice and again after
     * an undo.
     *
     * @throws OperationRejectedException if one of the documents is unavailable or only viewed by the user
     */
    public void moveToTrash(Collection<Document> documents, Runnable afterChange) {
        documentService.delete(documents);
        afterChange.run();

        List<UUID> ids = documents.stream()
                .map(Document::getId)
                .toList();

        offerUndo(ids.size(), () -> trashService.restore(List.of(), ids), afterChange);
    }

    /**
     * Moves the folder with its subfolders and documents to the trash and offers to undo it; the caller's work comes
     * before the notice, and its work after an undo once the folder is back.
     *
     * @throws OperationRejectedException if the folder is the root, is unavailable or only viewed by the user, or
     *                                    holds something the user may not change
     */
    public void moveToTrash(Folder folder, Runnable afterDelete, Runnable afterUndo) {
        boolean deleted = folderService.delete(folder);
        afterDelete.run();

        // a folder gone to the trash from another tab meanwhile is not this delete's to report or to undo
        if (deleted) {
            offerUndo(1, () -> trashService.restore(List.of(folder.getId()), List.of()), afterUndo);
        }
    }

    private void offerUndo(int count, IntSupplier undo, Runnable afterUndo) {
        UI ui = UI.getCurrent();

        // one notice at a time: a later delete takes the place of the earlier one, so an undo puts back only the last
        if (ComponentUtil.getData(ui, UNDO_NOTICE_KEY) instanceof Notification earlier) {
            earlier.close();
        }

        Span text = uiComponents.create(Span.class);
        text.setText(messages.formatMessage(getClass(), "movedToTrash", count));

        JmixButton undoButton = noticeButton("undoButton.text");
        JmixButton trashButton = noticeButton("openTrashButton.text");

        Div actions = uiComponents.create(Div.class);
        actions.addClassName("trash-notice-actions");
        actions.add(undoButton, trashButton);

        Div content = uiComponents.create(Div.class);
        content.addClassName("trash-notice-content");
        content.add(text, actions);

        Notification notice = notifications.create(content)
                .withClassName("trash-notice")
                .withPosition(Notification.Position.BOTTOM_END)
                .withDuration(TRASH_NOTICE_DURATION)
                .build();

        undoButton.addClickListener(click -> {
            notice.close();

            int restored = undo.getAsInt();
            afterUndo.run();
            warnIfNotAllCameBack(restored, count);
        });

        trashButton.addClickListener(click -> {
            notice.close();
            viewNavigators.view(UiComponentUtils.getCurrentView(), TrashView.class)
                    .navigate();
        });

        // a closed notice left on the UI would keep the handlers of its buttons, and so the view, alive
        notice.addOpenedChangeListener(change -> {
            if (!change.isOpened() && ComponentUtil.getData(ui, UNDO_NOTICE_KEY) == notice) {
                ComponentUtil.setData(ui, UNDO_NOTICE_KEY, null);
            }
        });

        ComponentUtil.setData(ui, UNDO_NOTICE_KEY, notice);
        notice.open();
    }

    private void warnIfNotAllCameBack(int restored, int deleted) {
        if (restored >= deleted) {
            return;
        }

        String text = restored == 0
                ? messages.getMessage(getClass(), "nothingToUndo")
                : messages.formatMessage(getClass(), "undoPartial", restored, deleted);

        notifications.create(text)
                .withType(Notifications.Type.WARNING)
                .show();
    }

    private JmixButton noticeButton(String key) {
        JmixButton button = uiComponents.create(JmixButton.class);
        button.setText(messages.getMessage(getClass(), key));
        button.addThemeVariants(ButtonVariant.TERTIARY);
        // the notice closes at the first click, and a second one on its way would undo or navigate again
        button.setDisableOnClick(true);

        return button;
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
