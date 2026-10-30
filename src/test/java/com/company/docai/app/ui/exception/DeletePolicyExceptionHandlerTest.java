package com.company.docai.app.ui.exception;

import com.company.docai.DocAiApplication;
import io.jmix.core.DeletePolicyException;
import io.jmix.core.Messages;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.notification.NotificationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@UiTest
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class DeletePolicyExceptionHandlerTest {

    @Autowired
    private DeletePolicyExceptionHandler handler;

    @Autowired
    private Messages messages;

    @Test
    void aTypeThatDocumentsAreMarkedWithIsExplained() {
        boolean handled = handler.handle(new IllegalStateException(
                new DeletePolicyException("dynat_Category", "Document")));

        NotificationInfo notification = UiTestUtils.getLastOpenedNotification();
        assertThat(handled).isTrue();
        assertThat(notification).isNotNull();
        assertThat(notification.getText())
                .isEqualTo(messages.getMessage(DeletePolicyExceptionHandler.class, "documentTypeInUse"));
        assertThat(notification.getType()).isEqualTo(Notifications.Type.WARNING);
    }

    @Test
    void anyOtherForbiddenDeleteGetsAGeneralExplanation() {
        boolean handled = handler.handle(new DeletePolicyException("Folder", "Document"));

        NotificationInfo notification = UiTestUtils.getLastOpenedNotification();
        assertThat(handled).isTrue();
        assertThat(notification).isNotNull();
        assertThat(notification.getText())
                .isEqualTo(messages.getMessage(DeletePolicyExceptionHandler.class, "deleteDenied"));
        assertThat(notification.getType()).isEqualTo(Notifications.Type.WARNING);
    }
}
