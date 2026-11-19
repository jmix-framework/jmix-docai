package com.company.docai.view.sessionended;

import com.company.docai.DocAiApplication;
import com.company.docai.test_support.AnonymousUiTestAuthenticator;
import com.company.docai.view.login.LoginView;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.auth.NavigationAccessControl;
import io.jmix.core.Messages;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.view.View;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@UiTest(authenticator = AnonymousUiTestAuthenticator.class, initialView = LoginView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class SessionEndedViewTest {

    @Autowired
    private Messages messages;

    @Autowired
    private NavigationAccessControl navigationAccessControl;

    @BeforeEach
    void controlNavigationAsTheApplicationDoes() {
        UI.getCurrent().addBeforeEnterListener(navigationAccessControl);
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "ru"})
    void theVisitorReadsWhyTheSessionEndedInTheLanguageOfTheSessionAndSignsInAgain(String language) {
        Locale locale = Locale.forLanguageTag(language);
        VaadinSession.getCurrent().setLocale(locale);

        UI.getCurrent().navigate("session-ended");

        View<?> view = UiTestUtils.getCurrentView();
        JmixButton loginButton = UiTestUtils.getComponent(view, "loginButton");

        assertThat(UiTestUtils.<H2>getComponent(view, "heading").getText())
                .isEqualTo(message("sessionEnded.heading", locale));
        assertThat(UiTestUtils.<Paragraph>getComponent(view, "explanation").getText())
                .isEqualTo(message("sessionEnded.text", locale));
        assertThat(loginButton.getText()).isEqualTo(message("loginButton.text", locale));
        assertThat(UI.getCurrent().getInternals().getTitle()).isEqualTo(message("sessionEndedView.title", locale));

        loginButton.click();

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(LoginView.class);
    }

    private String message(String key, Locale locale) {
        return messages.getMessage(SessionEndedView.class, key, locale);
    }
}
