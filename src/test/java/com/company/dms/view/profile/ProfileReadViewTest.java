package com.company.dms.view.profile;

import com.company.dms.DMSApplication;
import com.company.dms.model.user.User;
import com.company.dms.test_support.EmployeeUiTestAuthenticator;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@UiTest(authenticator = EmployeeUiTestAuthenticator.class)
@SpringBootTest(classes = {DMSApplication.class, FlowuiTestAssistConfiguration.class})
class ProfileReadViewTest {

    private static final String TEST_USER_PREFIX = "profile-read-view-test-";

    @Autowired
    private DialogWindows dialogWindows;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private CurrentAuthentication currentAuthentication;

    @AfterEach
    void removeTestUsers() {
        unconstrainedDataManager.load(User.class)
                .query("e.username like ?1", TEST_USER_PREFIX + "%")
                .list()
                .forEach(unconstrainedDataManager::remove);
    }

    @Test
    void theProfileShowsTheUserReadOnly() {
        ProfileReadView view = openProfile((User) currentAuthentication.getUser());

        assertThat(field(view, "firstNameField").getValue()).isEqualTo("Peter");
        assertThat(field(view, "lastNameField").getValue()).isEqualTo("Wilson");
        assertThat(field(view, "usernameField").getValue()).isEqualTo("peter");
        assertThat(field(view, "emailField").getValue()).isEqualTo("peter.wilson@example.com");
        for (String fieldId : new String[]{"firstNameField", "lastNameField", "usernameField", "emailField",
                "timeZoneField"}) {
            assertThat(field(view, fieldId).isReadOnly()).as(fieldId).isTrue();
        }
    }

    @Test
    void anEmptyTimeZoneIsShownAsTheBrowserOne() {
        ProfileReadView view = openProfile((User) currentAuthentication.getUser());

        assertThat(field(view, "timeZoneField").getValue()).endsWith(" (browser)");
    }

    @Test
    void aSetTimeZoneIsShownAsItIs() {
        User user = unconstrainedDataManager.create(User.class);
        user.setUsername(TEST_USER_PREFIX + System.nanoTime());
        user.setTimeZoneId("Asia/Tokyo");

        ProfileReadView view = openProfile(unconstrainedDataManager.save(user));

        assertThat(field(view, "timeZoneField").getValue()).isEqualTo("Asia/Tokyo");
    }

    private ProfileReadView openProfile(User user) {
        dialogWindows.read(UiTestUtils.getCurrentView(), User.class)
                .withViewClass(ProfileReadView.class)
                .readEntity(user)
                .open();

        return UiTestUtils.getLastOpenedViewDialog();
    }

    private TypedTextField<String> field(ProfileReadView view, String id) {
        return UiTestUtils.getComponent(view, id);
    }
}
