package com.company.docai.view.login;

import com.company.docai.DocAiApplication;
import com.company.docai.demo.DemoDataLoader;
import com.company.docai.test_support.AnonymousUiTestAuthenticator;
import com.company.docai.test_support.TestDashboards;
import com.company.docai.view.dashboard.DashboardFragment;
import com.company.docai.view.main.MainView;
import com.company.docai.view.usagehelp.UsageHelpView;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.SvgIcon;
import com.vaadin.flow.component.login.AbstractLogin.LoginEvent;
import com.vaadin.flow.internal.CurrentInstance;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.VaadinServletResponse;
import com.vaadin.flow.server.VaadinServletService;
import io.jmix.core.Messages;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.loginform.JmixLoginForm;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.view.View;
import io.jmix.securityflowui.authentication.LoginViewSupport;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.lang.ref.Reference;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.doReturn;

@UiTest(authenticator = AnonymousUiTestAuthenticator.class, initialView = LoginView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class LoginViewTest {

    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");
    private static final String MARK = "DOCAI_SESSION_ENDED";

    @Autowired
    private CurrentAuthentication currentAuthentication;

    @Autowired
    private Messages messages;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private FilterChainProxy securityFilters;

    // the tests start without a demo set, and the one of the scenarios is given where they need it
    @MockitoSpyBean
    private DemoDataLoader demoDataLoader;

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
    void aLoginClearsTheMarkOfAnEndedSessionSoThatALaterLogoutEndsOnTheLogin() throws ServletException, IOException {
        // the browser comes from the page of an ended session
        Cookie[] browser = {new Cookie(MARK, "1")};
        ((MockHttpServletRequest) VaadinServletRequest.getCurrent().getRequest()).setCookies(browser);
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();

        VaadinResponse response = new VaadinServletResponse(loginResponse,
                (VaadinServletService) VaadinService.getCurrent());

        CurrentInstance.set(VaadinResponse.class, response);

        try {
            login("peter", "peter");
        } finally {
            CurrentInstance.set(VaadinResponse.class, null);
            // Vaadin holds its current instances weakly: a collected response would lose the cookie of the login
            Reference.reachabilityFence(response);
        }

        AuthenticationEntryPoint entryPoint = Objects.requireNonNull(securityFilters.getFilters("/"))
                .stream()
                .filter(ExceptionTranslationFilter.class::isInstance)
                .map(filter -> ((ExceptionTranslationFilter) filter).getAuthenticationEntryPoint())
                .findFirst()
                .orElseThrow();

        // Jmix's logout ends the session and loads the context root, whose view a browser signed out may not open
        MockHttpServletRequest landing = new MockHttpServletRequest("GET", "/");
        landing.setCookies(kept(browser, loginResponse));
        MockHttpServletResponse landingResponse = new MockHttpServletResponse();
        entryPoint.commence(landing, landingResponse, new InsufficientAuthenticationException("signed out"));

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(MainView.class);
        Cookie mark = loginResponse.getCookie(MARK);
        assertThat(mark).isNotNull();
        assertThat(mark.getMaxAge()).isZero();
        assertThat(landingResponse.getRedirectedUrl()).endsWith("/login");
    }

    // a username typed into the form may carry line breaks, which would forge lines of the log
    @Test
    void aFailedLoginLogsTheUsernameOnItsLine(CapturedOutput output) {
        login("peter\r\nINFO forged", "peter");

        assertThat(output).contains("Login failed for user \"peter<EOL><EOL>INFO forged\"");
        assertThat(output).doesNotContain("\nINFO forged");
    }

    // the stand's language switch has a login support of its own, which lives in the profile of the stand
    @Test
    void outsideTheStandTheLoginSupportOfJmixIsTheOnlyOne() {
        assertThat(applicationContext.getBeansOfType(LoginViewSupport.class))
                .extractingFromEntries(Map.Entry::getKey, entry -> entry.getValue().getClass())
                .containsExactly(tuple("flowui_LoginViewSupport", LoginViewSupport.class));
    }

    @Test
    void theMarkAndTheNameStandAboveTheForm() {
        View<?> view = UiTestUtils.getCurrentView();
        SvgIcon logo = UiTestUtils.getComponent(view, "brandLogo");
        H1 name = UiTestUtils.getComponent(view, "brandName");

        assertThat(logo.getSrc()).isEqualTo("themes/jmix-docai-aura/images/logo.svg");
        assertThat(name.getText()).isEqualTo("Jmix DocAI");
    }

    // outside the stand the scenarios tell the demo set of the database
    @Test
    void withoutADemoSetTheFormOffersNoUsageScenarios() {
        assertThat(usageHelpButton().isVisible()).isFalse();
    }

    @Test
    void outsideTheStandTheScenariosFollowTheLanguageOfTheForm() {
        doReturn(true).when(demoDataLoader).isDemoSetChosen();

        // the form asks for the set as it opens
        viewNavigators.view(UiTestUtils.getCurrentView(), LoginView.class)
                .navigate();

        assertThat(usageHelpButton().isVisible()).isTrue();

        chooseLanguage(Locale.ENGLISH);
        String englishButton = usageHelpButton().getText();
        UsageHelpView english = openUsageHelp();
        String englishTitle = title(english);
        String englishContent = content(english);
        english.closeWithDefaultAction();

        chooseLanguage(RUSSIAN);
        UsageHelpView russian = openUsageHelp();

        assertThat(englishButton).isEqualTo(bundleButton(Locale.ENGLISH));
        assertThat(englishTitle).isEqualTo(bundleTitle(Locale.ENGLISH));
        assertThat(englishContent).isEqualTo(bundleContent(Locale.ENGLISH));
        assertThat(usageHelpButton().getText()).isEqualTo(bundleButton(RUSSIAN));
        assertThat(title(russian)).isEqualTo(bundleTitle(RUSSIAN));
        assertThat(content(russian)).isEqualTo(bundleContent(RUSSIAN));
        // everybody works in the same data outside the stand
        assertThat(UiTestUtils.<Paragraph>getComponent(russian, "privateNote").isVisible()).isFalse();
    }

    // the form passes its language to the session and the session to its pages, among which a page of a test is not,
    // so the test gives the page the language itself
    private static void chooseLanguage(Locale locale) {
        UiTestUtils.<JmixLoginForm>getComponent(UiTestUtils.getCurrentView(), "login")
                .setSelectedLocale(locale);

        UI.getCurrent().setLocale(locale);
    }

    private UsageHelpView openUsageHelp() {
        usageHelpButton().click();

        return UiTestUtils.getLastOpenedViewDialog();
    }

    private static JmixButton usageHelpButton() {
        return UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "usageHelpButton");
    }

    private static String title(UsageHelpView help) {
        return help.findAncestor(Dialog.class).getHeaderTitle();
    }

    private static String content(UsageHelpView help) {
        return UiTestUtils.<Html>getComponent(help, "content").getInnerHtml();
    }

    private String bundleButton(Locale locale) {
        return messages.getMessage(LoginView.class, "usageHelpButton.text", locale);
    }

    private String bundleContent(Locale locale) {
        return new Html(messages.getMessage(UsageHelpView.class, "usageHelpView.content", locale)).getInnerHtml();
    }

    private String bundleTitle(Locale locale) {
        return messages.getMessage(UsageHelpView.class, "usageHelpView.title", locale);
    }

    private static Cookie[] kept(Cookie[] sent, MockHttpServletResponse answer) {
        Map<String, Cookie> browser = new LinkedHashMap<>();

        for (Cookie cookie : sent) {
            browser.put(cookie.getName(), cookie);
        }

        for (Cookie cookie : answer.getCookies()) {
            if (cookie.getMaxAge() == 0) {
                browser.remove(cookie.getName());
            } else {
                browser.put(cookie.getName(), cookie);
            }
        }

        return browser.values().toArray(Cookie[]::new);
    }

    private JmixLoginForm login(String username, String password) {
        JmixLoginForm form = UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "login");
        ComponentUtil.fireEvent(form, new LoginEvent(form, true, username, password));

        return form;
    }
}
