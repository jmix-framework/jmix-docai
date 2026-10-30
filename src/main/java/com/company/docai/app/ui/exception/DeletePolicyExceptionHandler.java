package com.company.docai.app.ui.exception;

import io.jmix.core.DeletePolicyException;
import io.jmix.core.JmixOrder;
import io.jmix.core.Messages;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.exception.AbstractUiExceptionHandler;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Explains a delete that a reference forbids, such as the delete of a document type that documents are
 * marked with. Jmix has no handler for it and would show the technical error dialog.
 */
@Component
@Order(JmixOrder.HIGHEST_PRECEDENCE + 40)
public class DeletePolicyExceptionHandler extends AbstractUiExceptionHandler {

    private final Notifications notifications;
    private final Messages messages;

    public DeletePolicyExceptionHandler(Notifications notifications, Messages messages) {
        super(DeletePolicyException.class.getName());

        this.notifications = notifications;
        this.messages = messages;
    }

    @Override
    protected void doHandle(String className, String message, @Nullable Throwable throwable) {
        String key = throwable instanceof DeletePolicyException denied
                && "dynat_Category".equals(denied.getEntity())
                && "Document".equals(denied.getRefEntity())
                ? "documentTypeInUse"
                : "deleteDenied";

        notifications.create(messages.getMessage(getClass(), key))
                .withType(Notifications.Type.WARNING)
                .show();
    }
}
