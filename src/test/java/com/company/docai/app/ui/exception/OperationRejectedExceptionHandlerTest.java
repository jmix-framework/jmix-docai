package com.company.docai.app.ui.exception;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.OperationRejectedException;
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
class OperationRejectedExceptionHandlerTest {

    @Autowired
    private OperationRejectedExceptionHandler handler;

    @Test
    void aRejectionInsideAnotherExceptionIsShownAsAWarning() {
        boolean handled = handler.handle(new IllegalStateException(new OperationRejectedException("Name taken")));
        NotificationInfo notification = UiTestUtils.getLastOpenedNotification();

        assertThat(handled).isTrue();
        assertThat(notification).isNotNull();
        assertThat(notification.getText()).isEqualTo("Name taken");
        assertThat(notification.getType()).isEqualTo(Notifications.Type.WARNING);
    }
}
