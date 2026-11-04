package com.company.docai.view.access;

import com.company.docai.app.service.access.AccessService;
import com.company.docai.model.access.AccessDraft;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.vaadin.flow.component.Component;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.action.ActionType;
import io.jmix.flowui.action.list.SecuredListDataComponentAction;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.view.StandardOutcome;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.function.Consumer;

/**
 * Opens the access dialog of the folder or the document selected in a table over the view of the table. The table
 * learns of a saved dialog through {@link #setAfterSaveHandler(Consumer)}. The descriptor that declares the action
 * gives it its text and icon, and the table decides when it is enabled and shown.
 *
 * @param <E> the folders or the documents of the table
 */
@ActionType(AccessListAction.ID)
public class AccessListAction<E> extends SecuredListDataComponentAction<AccessListAction<E>, E> {

    /**
     * The type of the action in the descriptors.
     */
    public static final String ID = "docai_accessList";

    private AccessService accessService;
    private DialogWindows dialogWindows;

    private Consumer<E> afterSaveHandler = item -> {
    };

    /**
     * Creates the action with the id of its type.
     */
    public AccessListAction() {
        this(ID);
    }

    /**
     * Creates the action with the id a descriptor gives it.
     */
    public AccessListAction(String id) {
        super(id);
    }

    @Autowired
    protected void setAccessService(AccessService accessService) {
        this.accessService = accessService;
    }

    @Autowired
    protected void setDialogWindows(DialogWindows dialogWindows) {
        this.dialogWindows = dialogWindows;
    }

    /**
     * Sets what runs after the dialog has saved the access of the item; it gets the item.
     */
    public void setAfterSaveHandler(Consumer<E> afterSaveHandler) {
        this.afterSaveHandler = afterSaveHandler;
    }

    @Override
    public void execute() {
        checkTarget();
        E item = target.getSingleSelectedItem();

        AccessDraft draft = switch (item) {
            case Folder folder -> accessService.describe(folder);
            case Document document -> accessService.describe(document);
            default -> throw new IllegalStateException("The access dialog takes a folder or a document, not " + item);
        };

        dialogWindows.detail(UiComponentUtils.getView((Component) target), AccessDraft.class)
                .withViewClass(AccessDetailView.class)
                .editEntity(draft)
                .withAfterCloseListener(closeEvent -> {
                    if (closeEvent.closedWith(StandardOutcome.SAVE)) {
                        afterSaveHandler.accept(item);
                    }
                })
                .open();
    }
}
