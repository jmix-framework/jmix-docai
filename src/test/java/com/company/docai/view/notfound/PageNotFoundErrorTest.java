package com.company.docai.view.notfound;

import com.company.docai.DocAiApplication;
import com.company.docai.test_support.AnonymousUiTestAuthenticator;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.view.login.LoginView;
import com.company.docai.view.main.MainView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.NavigationTrigger;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.auth.NavigationAccessControl;
import io.jmix.core.ClassManager;
import io.jmix.core.Messages;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.UiProperties;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.view.View;
import io.jmix.flowui.view.ViewRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@UiTest(authenticator = AnonymousUiTestAuthenticator.class, initialView = LoginView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class PageNotFoundErrorTest {

    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");

    @Autowired
    private Messages messages;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private CurrentAuthentication currentAuthentication;

    @Autowired
    private NavigationAccessControl navigationAccessControl;

    @Autowired
    private ClassManager classManager;

    @Autowired
    private ViewRegistry viewRegistry;

    @Autowired
    private UiProperties uiProperties;

    @BeforeEach
    void controlNavigationAsTheApplicationDoes() {
        UI.getCurrent().addBeforeEnterListener(navigationAccessControl);
    }

    @Test
    void inAnOpenPageAnAnonymousVisitorReadsThatThePageDoesNotExistAndGoesToTheLogin() {
        VaadinSession.getCurrent().setLocale(Locale.ENGLISH);

        // an address loaded signed out leads to the login before the router, a link inside an open page does not
        int status = open("no-such-view", NavigationTrigger.ROUTER_LINK);

        Button wayBack = button(page());
        assertThat(status).isEqualTo(404);
        assertThat(text(page())).contains(message("pageNotFound.heading", Locale.ENGLISH),
                message("pageNotFound.text", Locale.ENGLISH));
        assertThat(UI.getCurrent().getInternals().getTitle())
                .isEqualTo(message("pageNotFound.title", Locale.ENGLISH));
        assertThat(wayBack.getText()).isEqualTo(message("loginButton.text", Locale.ENGLISH));

        wayBack.click();

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(LoginView.class);
    }

    @Test
    void thePageSpeaksTheLanguageOfTheSession() {
        VaadinSession.getCurrent().setLocale(RUSSIAN);

        open("datatl/entity-inspector", NavigationTrigger.ROUTER_LINK);

        assertThat(text(page())).contains(message("pageNotFound.heading", RUSSIAN),
                message("pageNotFound.text", RUSSIAN));
        assertThat(UI.getCurrent().getInternals().getTitle()).isEqualTo(message("pageNotFound.title", RUSSIAN));
        assertThat(button(page()).getText()).isEqualTo(message("loginButton.text", RUSSIAN));
    }

    @Test
    void aSignedInUserGoesBackToTheStartPage() {
        systemAuthenticator.runWithUser(EmployeeUiTestAuthenticator.USERNAME, () -> {
            signInTheRequest();

            open("no-such-view", NavigationTrigger.PAGE_LOAD);

            Button wayBack = button(page());
            assertThat(text(page())).contains(message("pageNotFound.heading", Locale.ENGLISH));
            assertThat(wayBack.getText()).isEqualTo(message("homeButton.text", Locale.ENGLISH));

            wayBack.click();

            assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(MainView.class);
        });
    }

    @Test
    void aSignedInUserGoesBackToTheStartPageLoadedAnewByAHotDeploy() throws IOException {
        byte[] bytes;

        try (InputStream stream = Objects.requireNonNull(MainView.class.getResourceAsStream("MainView.class"))) {
            bytes = stream.readAllBytes();
        }

        // as Studio's hot deploy does: the registry and the router get a class of the main view that is not its literal
        Class<?> loadedAnew = classManager.loadGeneratedClass(MainView.class.getName(), bytes);

        try {
            viewRegistry.reset();
            viewRegistry.registerRoute(viewRegistry.getViewInfo(uiProperties.getMainViewId()));
            systemAuthenticator.runWithUser(EmployeeUiTestAuthenticator.USERNAME, () -> {
                signInTheRequest();
                open("no-such-view", NavigationTrigger.PAGE_LOAD);

                button(page()).click();

                assertThat(UiTestUtils.<View<?>>getCurrentView()).isExactlyInstanceOf(loadedAnew);
            });
        } finally {
            classManager.removeClass(MainView.class.getName());
            viewRegistry.reset();
            viewRegistry.registerRoute(viewRegistry.getViewInfo(uiProperties.getMainViewId()));
        }
    }

    @Test
    void aViewTheUserMayNotOpenKeepsTheAnswerOfJmix() {
        systemAuthenticator.runWithUser(EmployeeUiTestAuthenticator.USERNAME, () -> {
            signInTheRequest();

            int status = open("users", NavigationTrigger.PAGE_LOAD);

            String answer = descendants(page())
                    .filter(Html.class::isInstance)
                    .map(html -> ((Html) html).getInnerHtml())
                    .findFirst()
                    .orElseThrow();

            assertThat(status).isEqualTo(404);
            assertThat(answer).contains("Could not navigate to 'users'");
            assertThat(text(page())).doesNotContain(message("pageNotFound.heading", Locale.ENGLISH));
            assertThat(descendants(page()).filter(Button.class::isInstance)).isEmpty();
        });
    }

    private static int open(String address, NavigationTrigger trigger) {
        UI ui = UI.getCurrent();

        // as the browser does: UI.navigate leaves an address that no route takes to the router of the browser
        return ui.getInternals()
                .getRouter()
                .navigate(ui, new Location(address), trigger);
    }

    private void signInTheRequest() {
        // the access control of navigation takes the user from the request, which a test builds without one
        ((MockHttpServletRequest) VaadinServletRequest.getCurrent().getRequest())
                .setUserPrincipal(currentAuthentication.getAuthentication());
    }

    private String message(String key, Locale locale) {
        return messages.getMessage(PageNotFoundError.class, key, locale);
    }

    private static Component page() {
        return (Component) UI.getCurrent()
                .getInternals()
                .getActiveRouterTargetsChain()
                .getFirst();
    }

    private static String text(Component page) {
        return page.getElement().getTextRecursively();
    }

    private static Button button(Component page) {
        return descendants(page)
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static Stream<Component> descendants(Component component) {
        return component.getChildren()
                .flatMap(child -> Stream.concat(Stream.of(child), descendants(child)));
    }
}
