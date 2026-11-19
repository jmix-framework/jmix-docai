package com.company.docai.app.ui.exception;

import com.company.docai.DocAiApplication;
import com.company.docai.model.user.User;
import com.company.docai.view.user.UserListView;
import com.vaadin.flow.server.ErrorEvent;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.genericfilter.Configuration;
import io.jmix.flowui.component.genericfilter.FilterUtils;
import io.jmix.flowui.component.genericfilter.GenericFilter;
import io.jmix.flowui.component.jpqlfilter.JpqlFilter;
import io.jmix.flowui.exception.UiExceptionHandlers;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.notification.NotificationInfo;
import io.jmix.flowui.testassist.notification.OpenedNotifications;
import io.jmix.flowui.view.ViewControllerUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchRuntimeException;

/**
 * A condition of the generic filter that cannot run, typed in the list of users as a person types one: a notice
 * instead of the error dialog, one line in the log, and a list that keeps working.
 */
@UiTest
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class FilterConditionExceptionHandlerTest {

    private static final String LOG_LINE = "A filter condition is not applied: ";

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private UiExceptionHandlers uiExceptionHandlers;

    @Autowired
    private FilterConditionExceptionHandler handler;

    @Autowired
    private OpenedNotifications openedNotifications;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private Messages messages;

    private UserListView users;
    private GenericFilter filter;

    @BeforeEach
    void openTheUsers() {
        viewNavigators.view(UiTestUtils.getCurrentView(), UserListView.class)
                .navigate();

        users = UiTestUtils.getCurrentView();
        filter = UiTestUtils.getComponent(users, "genericFilter");
        openedNotifications.closeOpenedNotifications();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{E}.username =", "{E}.salary > 0", "{E}.username = ?1"})
    void aConditionWithAnErrorGivesANoticeAndOneLineInTheLog(String where, CapturedOutput output) {
        // the parser of Jmix refuses the first, EclipseLink the attribute of the second and the parameter of the third
        apply(where);

        assertNotice("conditionInvalid");
        assertOneLineIn(output);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SQL('NO_SUCH_FUNCTION()') = 1", "{E}.version / 0 = 1"})
    void aConditionTheDatabaseRefusesGivesANoticeAndOneLineInTheLog(String where, CapturedOutput output) {
        apply(where);

        assertNotice("conditionRefused");
        assertOneLineIn(output);
    }

    @Test
    void theListKeepsItsRowsAndLoadsAgainWithoutTheCondition() {
        CollectionContainer<User> shown = ViewControllerUtils.getViewData(users)
                .getContainer("usersDc");

        int count = shown.getItems().size();
        JpqlFilter<Boolean> condition = apply("SQL('1/0') = 1");

        assertThat(shown.getItems()).hasSize(count);

        condition.setValue(false);
        filter.apply();

        assertThat(shown.getItems()).hasSize(count)
                .isNotEmpty();
    }

    @Test
    void theLineOfTheLogFoldsTheFormatCharactersOfTheCondition(CapturedOutput output) {
        // a right-to-left override would reorder the line in a viewer of the log that honours it
        apply("{E}.username = '\u202Eevil' and");

        String line = output.getAll()
                .lines()
                .filter(text -> text.contains(LOG_LINE))
                .findFirst()
                .orElseThrow();

        assertThat(line).doesNotContain("\u202E")
                .contains("= ' evil' and");
    }

    @Test
    void anErrorOfAListenerOfTheListDuringAnApplyIsLeftToTheErrorDialog() {
        CollectionContainer<User> shown = ViewControllerUtils.getViewData(users)
                .getContainer("usersDc");

        // a defect of the view's own code, which runs under the load that the filter starts
        shown.addCollectionChangeListener(event -> {
            throw new IllegalStateException("A listener of the list failed");
        });

        RuntimeException failure = catchRuntimeException(filter::apply);

        assertThat(failure).isNotNull();
        assertThat(handler.handle(failure)).isFalse();
        assertThat(openedNotifications.getNotifications()).isEmpty();
    }

    @Test
    void aFailedQueryThatNoFilterStartedIsLeftToTheErrorDialog() {
        // the application's own query, whose failure is a defect for the error dialog and the log
        RuntimeException failure = catchRuntimeException(() -> dataManager.load(User.class)
                .query("select e from User e where SQL('1/0') = 1")
                .list());

        assertThat(failure).isNotNull();
        assertThat(handler.handle(failure)).isFalse();
        assertThat(openedNotifications.getNotifications()).isEmpty();
    }

    private JpqlFilter<Boolean> apply(String where) {
        JpqlFilter<Boolean> condition = filter.filterComponentBuilder()
                .jpqlFilter()
                .where(where)
                .defaultValue(true)
                .build();

        Configuration configuration = filter.getCurrentConfiguration();
        configuration.getRootLogicalFilterComponent().add(condition);
        FilterUtils.setCurrentConfiguration(filter, configuration, true);

        RuntimeException failure = catchRuntimeException(filter::apply);

        assertThat(failure).isNotNull();

        // a request of the browser hands its error to the session's handler
        uiExceptionHandlers.error(new ErrorEvent(failure));

        return condition;
    }

    private void assertNotice(String key) {
        NotificationInfo notification = UiTestUtils.getLastOpenedNotification();

        assertThat(notification).isNotNull();
        assertThat(notification.getText())
                .isEqualTo(messages.getMessage("com.company.docai.app.ui.exception/" + key));
        assertThat(notification.getType()).isEqualTo(Notifications.Type.WARNING);
    }

    private static void assertOneLineIn(CapturedOutput output) {
        assertThat(output.getAll().lines()
                .filter(line -> line.contains(LOG_LINE)))
                .hasSize(1);

        assertThat(output).doesNotContain("\tat ");
    }
}
