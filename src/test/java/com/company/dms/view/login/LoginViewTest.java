package com.company.dms.view.login;

import com.company.dms.DMSApplication;
import com.company.dms.test_support.AnonymousUiTestAuthenticator;
import com.company.dms.view.repository.RepositoryView;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.html.H1;
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
@SpringBootTest(classes = {DMSApplication.class, FlowuiTestAssistConfiguration.class})
class LoginViewTest {

    @Autowired
    private CurrentAuthentication currentAuthentication;

    @Test
    void anEmployeeSignsInWithTheDemoPassword() {
        login("peter", "peter");

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(RepositoryView.class);
        assertThat(currentAuthentication.getUser().getUsername()).isEqualTo("peter");
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

        assertThat(logo.getSrc()).isEqualTo("themes/jmix-dms-aura/images/logo.svg");
        assertThat(name.getText()).isEqualTo("Jmix DMS");
    }

    private JmixLoginForm login(String username, String password) {
        JmixLoginForm form = UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "login");
        ComponentUtil.fireEvent(form, new LoginEvent(form, true, username, password));

        return form;
    }
}
