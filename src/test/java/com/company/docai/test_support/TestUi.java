package com.company.docai.test_support;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.server.VaadinSession;
import io.jmix.aitoolsflowui.view.chat.AiChatFragment;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.dialog.DialogInfo;
import io.jmix.flowui.view.View;

import java.time.Duration;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * What the UI tests of many views share besides their components: the buttons of the dialog opened last, and the work
 * that comes back to the page through {@code UI.access}, which a UI test runs only when asked.
 */
public final class TestUi {

    private TestUi() {
    }

    /**
     * The button of the given type in the dialog opened last.
     */
    public static Button dialogButton(DialogAction.Type type) {
        DialogInfo dialog = UiTestUtils.getLastOpenedDialog();
        assertThat(dialog).isNotNull();

        return dialog.getButtons()
                .stream()
                .filter(button -> button.getId().equals(Optional.of(type.getId())))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Runs the work that waits for the session of the current UI until the condition holds, twenty seconds at most.
     */
    public static void awaitAccess(BooleanSupplier condition) {
        VaadinSession session = VaadinSession.getCurrent();

        await().pollInSameThread()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> {
                    session.getService().runPendingAccessTasks(session);

                    return condition.getAsBoolean();
                });
    }

    /**
     * Waits until the chat of the view stops waiting for the answer of the model.
     */
    public static void awaitAnswer(View<?> chat) {
        AiChatFragment fragment = UiTestUtils.getComponent(chat, "chatFragment");

        awaitAccess(() -> !fragment.isAwaitingResponse());
    }
}
