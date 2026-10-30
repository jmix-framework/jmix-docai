package com.company.docai.app.ui.exception;

import com.company.docai.app.service.OperationRejectedException;
import io.jmix.core.JmixOrder;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.exception.AbstractUiExceptionHandler;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Shows a business-rule rejection as a warning, so that views can call services without
 * catching their exceptions.
 */
@Component
@Order(JmixOrder.HIGHEST_PRECEDENCE + 40)
public class OperationRejectedExceptionHandler extends AbstractUiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(OperationRejectedExceptionHandler.class);

    private final Notifications notifications;

    public OperationRejectedExceptionHandler(Notifications notifications) {
        super(OperationRejectedException.class.getName());
        this.notifications = notifications;
    }

    @Override
    protected void doHandle(String className, String message, @Nullable Throwable throwable) {
        // a rejection by a rule is not an application error
        log.debug("Operation rejected: {}", message);

        notifications.create(message)
                .withType(Notifications.Type.WARNING)
                .show();
    }
}
