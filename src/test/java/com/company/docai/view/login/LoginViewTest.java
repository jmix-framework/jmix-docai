package com.company.docai.view.login;

import com.company.docai.DocAiApplication;
import com.company.docai.test_support.AnonymousUiTestAuthenticator;
import com.company.docai.test_support.TestDashboards;
import com.company.docai.view.dashboard.DashboardFragment;
import com.company.docai.view.main.MainView;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.icon.SvgIcon;
import com.vaadin.flow.component.login.AbstractLogin.LoginEvent;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.component.loginform.JmixLoginForm;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.view.View;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@UiTest(authenticator = AnonymousUiTestAuthenticator.class, initialView = LoginView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class LoginViewTest {

    @Autowired
    private CurrentAuthentication currentAuthentication;

    @Test
    void anEmployeeSignsInWithTheDemoPasswordAndLandsOnTheDashboard() {
        login("peter", "peter");

        View<?> view = UiTestUtils.getCurrentView();
        DashboardFragment dashboard = UiTestUtils.getComponent(view, "dashboard");

        assertThat(view).isInstanceOf(MainView.class);
        assertThat(currentAuthentication.getUser().getUsername()).isEqualTo("peter");
        // the main view reads the blocks of the dashboard as it shows it after the sign-in
        assertThat(TestDashboards.<H2>component(dashboard, "greeting").getText()).isNotBlank();
    }

    @Test
    void aWrongPasswordKeepsTheLoginForm() {
        JmixLoginForm form = login("peter", "alice");

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(LoginView.class);
        assertThat(form.isError()).isTrue();
    }

    @Test
    void theMarkAndTheNameStandAboveTheForm() {
        View<?> view = UiTestUtils.getCurrentView();
        SvgIcon logo = UiTestUtils.getComponent(view, "brandLogo");
        H1 name = UiTestUtils.getComponent(view, "brandName");

        assertThat(logo.getSrc()).isEqualTo("themes/jmix-docai-aura/images/logo.svg");
        assertThat(name.getText()).isEqualTo("Jmix DocAI");
    }

    private JmixLoginForm login(String username, String password) {
        JmixLoginForm form = UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "login");
        ComponentUtil.fireEvent(form, new LoginEvent(form, true, username, password));

        return form;
    }
}
