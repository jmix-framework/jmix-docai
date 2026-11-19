package com.company.docai.stand;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.ui.action.UserMenuLanguageSwitchAction;
import com.company.docai.model.document.Document;
import com.company.docai.model.user.User;
import com.company.docai.security.role.EmployeeRole;
import com.company.docai.test_support.AnonymousUiTestAuthenticator;
import com.company.docai.test_support.StubAiConfiguration;
import com.company.docai.test_support.TestFiles;
import com.company.docai.view.login.LoginView;
import com.company.docai.view.main.MainView;
import com.company.docai.view.usagehelp.UsageHelpView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.internal.PendingJavaScriptInvocation;
import com.vaadin.flow.component.internal.UIInternals;
import com.vaadin.flow.component.login.AbstractLogin.LoginEvent;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.internal.CurrentInstance;
import com.vaadin.flow.internal.StateNode;
import com.vaadin.flow.internal.change.MapPutChange;
import com.vaadin.flow.internal.nodefeature.ElementListenerMap;
import com.vaadin.flow.internal.nodefeature.ElementPropertyMap;
import com.vaadin.flow.internal.nodefeature.PropertyChangeDeniedException;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.VaadinServletResponse;
import com.vaadin.flow.server.VaadinServletService;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.auth.NavigationAccessControl;
import com.vaadin.flow.shared.JsonConstants;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.core.security.SecurityContextHelper;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.core.security.event.PostAuthenticationCheckEvent;
import io.jmix.core.security.event.PreAuthenticationCheckEvent;
import io.jmix.core.usersubstitution.CurrentUserSubstitution;
import io.jmix.core.usersubstitution.UserSubstitutionManager;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.component.loginform.JmixLoginForm;
import io.jmix.flowui.component.usermenu.UserMenu;
import io.jmix.flowui.kit.component.usermenu.ActionUserMenuItem;
import io.jmix.flowui.kit.component.usermenu.UserMenuItem;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.dialog.DialogInfo;
import io.jmix.flowui.testassist.notification.NotificationInfo;
import io.jmix.flowui.view.View;
import io.jmix.security.role.assignment.RoleAssignmentRoleType;
import io.jmix.securitydata.entity.RoleAssignmentEntity;
import io.jmix.securitydata.entity.UserSubstitutionEntity;
import io.jmix.securityflowui.authentication.AuthDetails;
import io.jmix.securityflowui.authentication.AuthDetailsValidationResult;
import io.jmix.securityflowui.authentication.AuthDetailsValidator;
import io.jmix.securityflowui.authentication.LoginViewSupport;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AbstractAuthenticationEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.lang.ref.Reference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.company.docai.test_support.TestUi.dialogButton;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;

/**
 * The login on the stand and the change of the language after it, each step a request of one browser through the filter
 * of the stand.
 */
@UiTest(authenticator = AnonymousUiTestAuthenticator.class, initialView = LoginView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class, StubAiConfiguration.class},
        properties = "docai.stand.data-dir=build/test-stand-login")
@ActiveProfiles({"test", "stand"})
@ExtendWith(OutputCaptureExtension.class)
@RecordApplicationEvents
class StandLoginTest {

    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");

    @Autowired
    private StandScopeFilter filter;

    @Autowired
    private StandLogin standLogin;

    @Autowired
    private CopyRegistry registry;

    @Autowired
    private CopyFactory copyFactory;

    @Autowired
    private StandProperties properties;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private CurrentAuthentication currentAuthentication;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private Messages messages;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private SessionRegistry sessionRegistry;

    @Autowired
    private UserSubstitutionManager userSubstitutionManager;

    @Autowired
    private CurrentUserSubstitution currentUserSubstitution;

    @Autowired
    private SecurityContextRepository securityContextRepository;

    @Autowired
    private ApplicationEvents events;

    @Autowired
    private Scheduler scheduler;

    @TempDir
    private Path tempDir;

    private @Nullable String cookie;

    // the language Jmix's login keeps in the browser for the next login
    private @Nullable String lastLocale;

    private boolean disabledInBrowser;

    // Quartz, in standby in the other tests, runs the jobs that delete the copies a login or a switch replaces
    @BeforeAll
    static void startTheStand(@Autowired StandStartup startup, @Autowired Scheduler scheduler) throws Exception {
        startup.start()
                .get(3, TimeUnit.MINUTES);

        scheduler.start();
    }

    @AfterAll
    static void stopTheJobs(@Autowired Scheduler scheduler) throws SchedulerException {
        scheduler.standby();
    }

    @AfterEach
    void deleteTheCopies() {
        registry.all()
                .forEach(copy -> copyFactory.delete(copy.key()));
    }

    // the cookie, not the HTTP session, which the logout ends
    @Test
    void sameBrowserKeepsTheCopyAcrossLogins() throws Exception {
        AtomicReference<UUID> note = new AtomicReference<>();
        AtomicReference<Boolean> noteSeen = new AtomicReference<>();

        request(() -> {
            logIn("alice");
            note.set(upload("Alice note.txt"));
        });

        String key = cookie;
        logOut();

        request(() -> {
            logIn("peter");
            noteSeen.set(dataManager.load(Document.class)
                    .id(note.get())
                    .optional()
                    .isPresent());
        });

        assertThat(currentAuthentication.getUser().getUsername()).isEqualTo("peter");
        assertThat(noteSeen.get()).isTrue();
        assertThat(cookie).isEqualTo(key);
        assertThat(registry.all()).extracting(Copy::key).containsExactly(key);
    }

    @Test
    void anotherLanguageStartsAFreshCopy() throws Exception {
        AtomicReference<StandScope> scope = new AtomicReference<>();

        request(() -> logIn("alice"));

        String english = cookie;
        logOut();

        request(() -> {
            loginForm().setSelectedLocale(RUSSIAN);
            logIn("alice");
            scope.set(StandScopeHolder.current());
        });

        awaitDeleted(english);

        assertThat(cookie).isNotEqualTo(english);
        assertThat(registry.find(english)).isEmpty();
        assertThat(registry.all()).extracting(Copy::key, Copy::language).containsExactly(tuple(cookie, "ru"));
        assertThat(scope.get()).isEqualTo(StandScope.copy(cookie));
        HttpSession session = httpRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getAttribute(StandScopeHolder.SESSION_ATTRIBUTE)).isEqualTo(StandScope.copy(cookie).id());
    }

    // a template that is being built has no ready marker, see StandStartupTest
    @Test
    void loginWaitsWhileTheTemplateIsBuilding() throws Exception {
        Path marker = StandScope.template("ru")
                .folder(properties.dataDir())
                .resolve(TemplateBuilder.READY);

        Path aside = marker.resolveSibling("ready.aside");
        Files.move(marker, aside);

        try {
            request(() -> {
                loginForm().setSelectedLocale(RUSSIAN);
                logIn("alice");
            });

            NotificationInfo notice = UiTestUtils.getLastOpenedNotification();

            assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(LoginView.class);
            assertThat(loginForm().isEnabled()).isTrue();
            assertThat(notice).isNotNull();
            assertThat(notice.getText()).isEqualTo(messages.getMessage(LoginView.class, "standGettingReady", RUSSIAN));
            assertThat(notice.getType()).isEqualTo(Notifications.Type.WARNING);
            assertThat(notice.getNotification().isOpened()).isTrue();
            assertThat(registry.all()).isEmpty();
            assertThat(cookie).isNull();
            assertThatThrownBy(() -> request(() -> standLogin.prepare(RUSSIAN, "alice", "alice")))
                    .isInstanceOf(TemplateNotReadyException.class);

            request(() -> logIn("alice"));

            assertThat(notice.getNotification().isOpened()).isFalse();
            assertThat(UiTestUtils.getOpenedNotifications())
                    .extracting(NotificationInfo::getText)
                    .containsExactly(notice.getText());
        } finally {
            Files.move(aside, marker);
        }
    }

    // a logout keeps the copy, and so does the login in the language the form opens with
    @Test
    void aReturningVisitorSeesTheFormInTheLanguageOfTheirCopy() throws Exception {
        request(() -> {
            loginForm().setSelectedLocale(RUSSIAN);
            logIn("alice");
        });

        String russian = cookie;
        logOut();

        Locale formLocale = loginForm().getSelectedLocale();
        boolean warning = languageWarning().isVisible();
        request(() -> logIn("alice"));

        assertThat(formLocale).isEqualTo(RUSSIAN);
        assertThat(warning).isFalse();
        assertThat(cookie).isEqualTo(russian);
        assertThat(registry.all()).extracting(Copy::key).containsExactly(russian);
    }

    // another tab of the browser sends the cookie it had while the login replaces the copy
    @Test
    void aLoginInAnotherLanguageKeepsItsSessionFromTheOtherTabs() throws Exception {
        request(() -> logIn("alice"));

        String english = cookie;
        logOut();

        request(() -> {
            loginForm().setSelectedLocale(RUSSIAN);
            logIn("alice");
            requestOfAnotherTab(english);
        });

        HttpSession session = httpRequest().getSession(false);

        assertThat(session).isNotNull();
        assertThat(session.getAttribute(StandScopeHolder.SESSION_ATTRIBUTE)).isEqualTo(StandScope.copy(cookie).id());
    }

    // the cleaner deletes a copy nobody uses for a while, also while its browser keeps a signed-in session
    @Test
    void aSignedInBrowserWhoseCopyIsGoneReturnsToTheLoginForm() throws Exception {
        AtomicReference<StandScope> scope = new AtomicReference<>(StandScope.template("en"));

        request(() -> logIn("alice"));

        String deleted = cookie;
        MockHttpSession session = (MockHttpSession) httpRequest().getSession(false);
        assertThat(session).isNotNull();
        copyFactory.delete(deleted);

        request(() -> scope.set(StandScopeHolder.current()));

        boolean sessionOver = session.isInvalid();
        // Vaadin answers a request without a session as an expired one, and the browser opens the login form
        openTheLoginFormAsNobody();
        Locale formLocale = loginForm().getSelectedLocale();
        request(() -> logIn("alice"));

        assertThat(sessionOver).isTrue();
        assertThat(scope.get()).isNull();
        assertThat(formLocale).isEqualTo(Locale.ENGLISH);
        assertThat(cookie).isNotEqualTo(deleted);
        assertThat(registry.all()).extracting(Copy::key).containsExactly(cookie);
    }

    @Test
    void theFormOpensInEnglishOnTheStand() throws Exception {
        VaadinSession.getCurrent().setLocale(RUSSIAN);

        request(this::openLoginForm);

        assertThat(loginForm().getSelectedLocale()).isEqualTo(Locale.ENGLISH);
        assertThat(VaadinSession.getCurrent().getLocale()).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void theFormWarnsThatAnotherLanguageStartsTheDemoAnew() throws Exception {
        request(() -> loginForm().setSelectedLocale(RUSSIAN));

        boolean withoutACopy = languageWarning().isVisible();

        request(() -> {
            loginForm().setSelectedLocale(Locale.ENGLISH);
            logIn("alice");
        });

        logOut();

        boolean english = languageWarning().isVisible();
        request(() -> loginForm().setSelectedLocale(RUSSIAN));
        boolean russian = languageWarning().isVisible();
        request(() -> loginForm().setSelectedLocale(Locale.ENGLISH));

        assertThat(withoutACopy).isFalse();
        assertThat(english).isFalse();
        assertThat(russian).isTrue();
        assertThat(languageWarning().isVisible()).isFalse();
    }

    // the scenarios of the login form say that the copy of the visitor is the visitor's own; the stand offers them
    // though its start loads no demo set
    @Test
    void theUsageScenariosSayThatTheChangesArePrivate() throws Exception {
        assertThat(usageHelpButton().isVisible()).isTrue();

        request(() -> usageHelpButton().click());

        UsageHelpView help = UiTestUtils.getLastOpenedViewDialog();

        assertThat(UiTestUtils.<Paragraph>getComponent(help, "privateNote").isVisible()).isTrue();
    }

    @Test
    void theLanguageItemIsVisibleOnTheStand() throws Exception {
        request(() -> logIn("alice"));

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(MainView.class);
        assertThat(userMenu().getItem("languageMenuItem").isVisible()).isTrue();
        assertThat(userMenu().getItem("themeMenuItem").isVisible()).isTrue();
    }

    @Test
    void theLanguageOfTheCopyChangesNothing() throws Exception {
        request(() -> logIn("alice"));

        String english = cookie;
        String session = sessionId();
        Authentication login = SecurityContextHelper.getAuthentication();

        request(() -> languageAction().switchTo(Locale.ENGLISH));

        assertThat(UiTestUtils.getOpenedDialogs()).isEmpty();
        assertThat(pageLoads()).isEmpty();
        assertThat(cookie).isEqualTo(english);
        assertThat(registry.all()).extracting(Copy::key).containsExactly(english);
        assertThat(sessionId()).isEqualTo(session);
        assertThat(SecurityContextHelper.getAuthentication()).isSameAs(login);
    }

    @Test
    void anotherLanguageAsksFirstAndCancelChangesNothing() throws Exception {
        request(() -> logIn("alice"));

        String english = cookie;
        String session = sessionId();
        Authentication login = SecurityContextHelper.getAuthentication();
        UserMenuItem russian = languageItem("ru");
        // a click on an item toggles its check mark
        russian.setChecked(true);

        request(() -> languageAction().switchTo(RUSSIAN));

        DialogInfo question = UiTestUtils.getLastOpenedDialog();
        request(() -> dialogButton(DialogAction.Type.CANCEL).click());

        assertThat(question).isNotNull();
        assertThat(question.getDialog().getHeaderTitle()).isEqualTo("Change the language?");
        assertThat(((Paragraph) question.getContent()).getText())
                .isEqualTo("Changing the language discards your changes");
        assertThat(pageLoads()).isEmpty();
        assertThat(cookie).isEqualTo(english);
        assertThat(registry.all()).extracting(Copy::key, Copy::language).containsExactly(tuple(english, "en"));
        assertThat(sessionId()).isEqualTo(session);
        assertThat(SecurityContextHelper.getAuthentication()).isSameAs(login);
        assertThat(currentAuthentication.getLocale()).isEqualTo(Locale.ENGLISH);
        assertThat(languageItem("en").isChecked()).isTrue();
        assertThat(russian.isChecked()).isFalse();
    }

    // the same user, from the new copy, in a new session; nothing of the request names the user or the new copy
    @Test
    void confirmingMovesTheVisitorToAFreshCopyInTheLanguage() throws Exception {
        request(() -> logIn("alice"));

        String english = cookie;
        User englishAlice = (User) currentAuthentication.getUser();
        Authentication login = SecurityContextHelper.getAuthentication();
        MockHttpSession before = (MockHttpSession) httpRequest().getSession(false);
        assertThat(before).isNotNull();

        request(() -> languageAction().switchTo(RUSSIAN));
        events.clear();
        request(() -> dialogButton(DialogAction.Type.OK).click());
        awaitDeleted(english);

        Authentication switched = SecurityContextHelper.getAuthentication();
        assertThat(switched).isNotNull();
        User alice = (User) switched.getPrincipal();
        User russianAlice = userOf(cookie, "alice");
        HttpSession session = httpRequest().getSession(false);
        assertThat(session).isNotNull();
        SecurityContext stored = (SecurityContext) session.getAttribute(SPRING_SECURITY_CONTEXT_KEY);
        SessionInformation information = sessionRegistry.getSessionInformation(session.getId());

        assertThat(cookie).isNotEqualTo(english);
        assertThat(registry.find(english)).isEmpty();
        assertThat(registry.all()).extracting(Copy::key, Copy::language).containsExactly(tuple(cookie, "ru"));
        assertThat(session.getAttribute(StandScopeHolder.SESSION_ATTRIBUTE)).isEqualTo(StandScope.copy(cookie).id());
        assertThat(switched.getName()).isEqualTo("alice");
        assertThat(alice).isNotNull();
        assertThat(alice.getDisplayName()).isEqualTo(russianAlice.getDisplayName());
        assertThat(alice.getDisplayName()).isNotEqualTo(englishAlice.getDisplayName());
        assertThat(login).isNotNull();
        assertThat(factors(switched)).isEqualTo(factors(login));
        // a new session against fixation, kept in the registry of sessions with the new context
        assertThat(before.isInvalid()).isTrue();
        assertThat(session.getId()).isNotEqualTo(before.getId());
        assertThat(stored.getAuthentication()).isSameAs(switched);
        assertThat(information).isNotNull();
        assertThat(information.getPrincipal()).isSameAs(alice);
        // the account is checked in the control database before the copy is made, then in the copy
        assertThat(events.stream(PreAuthenticationCheckEvent.class))
                .extracting(PreAuthenticationCheckEvent::getUser)
                .containsExactly(alice, alice)
                .last()
                .isSameAs(alice);
        assertThat(events.stream(PostAuthenticationCheckEvent.class))
                .extracting(PostAuthenticationCheckEvent::getUser)
                .containsExactly(alice, alice)
                .last()
                .isSameAs(alice);
        assertThat(events.stream(InteractiveAuthenticationSuccessEvent.class))
                .extracting(AbstractAuthenticationEvent::getAuthentication)
                .containsExactly(switched);
        // no password is checked again; the system's authentications of the copy's work may come
        assertThat(events.stream(AuthenticationSuccessEvent.class))
                .extracting(AbstractAuthenticationEvent::getAuthentication)
                .noneMatch(UsernamePasswordAuthenticationToken.class::isInstance);
        assertThat(currentAuthentication.getLocale()).isEqualTo(RUSSIAN);
        assertThat(VaadinSession.getCurrent().getLocale()).isEqualTo(RUSSIAN);
        assertThat(pageLoads()).containsExactly(List.of("/", "_self"));
    }

    // once the stand has checked the user, Jmix's own login takes the steps of the sign-in through the login support of
    // the switch; the context the threads of the session share stays as it was
    @Test
    void theSwitchSignsInThroughTheLoginOfJmix() throws Exception {
        request(() -> logIn("alice"));

        HttpSession before = httpRequest().getSession(false);
        assertThat(before).isNotNull();
        SecurityContext shared = (SecurityContext) before.getAttribute(SPRING_SECURITY_CONTEXT_KEY);
        Authentication login = shared.getAuthentication();
        String formLanguage = lastLocale;
        request(() -> languageAction().switchTo(RUSSIAN));
        events.clear();
        request(() -> dialogButton(DialogAction.Type.OK).click());

        Authentication switched = SecurityContextHelper.getAuthentication();

        SecurityContext saved = securityContextRepository.loadDeferredContext(httpRequest())
                .get();

        assertThat(sessionId()).isNotEqualTo(before.getId());
        assertThat(saved.getAuthentication()).isSameAs(switched);
        assertThat(saved).isNotSameAs(shared);
        assertThat(shared.getAuthentication()).isSameAs(login);
        assertThat(formLanguage).isEqualTo("en");
        assertThat(lastLocale).isEqualTo("ru");
        assertThat(events.stream(InteractiveAuthenticationSuccessEvent.class))
                .extracting(InteractiveAuthenticationSuccessEvent::getGeneratedBy)
                .containsExactly(StandLoginViewSupport.class);
    }

    // Vaadin keeps in the session the address a visitor opened before signing in, and nothing removes it: Jmix's login
    // would open it in the tab of the switch, a view of the new copy at an address of the old one
    @Test
    void anAddressKeptBeforeTheLoginDoesNotOpenAtTheSwitch() throws Exception {
        request(() -> logIn("alice"));

        HttpSession session = httpRequest().getSession(false);
        assertThat(session).isNotNull();
        session.setAttribute(NavigationAccessControl.SESSION_STORED_REDIRECT, "repository");

        switchLanguage(RUSSIAN);

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(MainView.class);
        assertThat(pageLoads()).containsExactly(List.of("/", "_self"));
    }

    // the login support of the switch is a bean no injection takes by its type: the login form keeps Jmix's
    @Test
    void theLoginFormKeepsTheLoginSupportOfJmix() throws Exception {
        request(() -> logIn("alice"));

        assertThat(applicationContext.getBeansOfType(LoginViewSupport.class))
                .extractingFromEntries(Map.Entry::getKey, entry -> entry.getValue().getClass())
                .containsExactlyInAnyOrder(tuple("flowui_LoginViewSupport", LoginViewSupport.class),
                        tuple(StandLoginViewSupport.NAME, StandLoginViewSupport.class));
        assertThat(applicationContext.getBean(LoginViewSupport.class)).isExactlyInstanceOf(LoginViewSupport.class);
        assertThat(events.stream(InteractiveAuthenticationSuccessEvent.class))
                .extracting(InteractiveAuthenticationSuccessEvent::getGeneratedBy)
                .containsExactly(LoginViewSupport.class);
    }

    // a validator of the details of a login, which Jmix's login runs before it checks the credentials, decides on a
    // switch too, with the details of the switch, before a copy is made
    @Test
    void aValidatorOfTheLoginRefusesTheSwitchBeforeACopyIsMade(CapturedOutput output) throws Exception {
        StandLoginViewSupport support = applicationContext.getBean(StandLoginViewSupport.NAME,
                StandLoginViewSupport.class);

        List<AuthDetailsValidator> validators = applicationContext.getBeanProvider(AuthDetailsValidator.class)
                .orderedStream()
                .toList();

        List<AuthDetails> validated = new ArrayList<>();
        request(() -> logIn("alice"));

        String english = cookie;
        String session = sessionId();
        Authentication login = SecurityContextHelper.getAuthentication();

        support.setAuthDetailsValidators(List.of(details -> {
            validated.add(details);

            return AuthDetailsValidationResult.createInvalid("The stand is closed");
        }));

        try {
            switchLanguage(RUSSIAN);
        } finally {
            support.setAuthDetailsValidators(validators);
        }

        NotificationInfo notice = UiTestUtils.getLastOpenedNotification();

        assertThat(validated).extracting(AuthDetails::getUsername, AuthDetails::getPassword, AuthDetails::getLocale)
                .containsExactly(tuple("alice", "", RUSSIAN));
        assertThat(notice).isNotNull();
        assertThat(notice.getText()).isEqualTo("The language isn't changed: your account is unavailable in it");
        assertThat(output).contains("DisabledException: The stand is closed");
        assertThat(copiesMade(output, "ru")).isZero();
        assertThat(pageLoads()).isEmpty();
        assertThat(cookie).isEqualTo(english);
        assertThat(registry.all()).extracting(Copy::key, Copy::language).containsExactly(tuple(english, "en"));
        assertThat(sessionId()).isEqualTo(session);
        assertThat(SecurityContextHelper.getAuthentication()).isSameAs(login);
    }

    @Test
    void theLoginSupportOfTheSwitchSignsInNoOtherUser() throws Exception {
        StandLoginViewSupport support = applicationContext.getBean(StandLoginViewSupport.NAME,
                StandLoginViewSupport.class);

        request(() -> logIn("alice"));

        String session = sessionId();
        Authentication login = SecurityContextHelper.getAuthentication();
        User peter = userOf(cookie, "peter");
        Authentication other = UsernamePasswordAuthenticationToken.authenticated(peter, null, List.of());

        assertThatThrownBy(() -> request(() -> support.signIn(other, RUSSIAN)))
                .isInstanceOf(InsufficientAuthenticationException.class);

        assertThat(sessionId()).isEqualTo(session);
        assertThat(SecurityContextHelper.getAuthentication()).isSameAs(login);
        assertThat(lastLocale).isEqualTo("en");
    }

    // a listener of the event of the login that fails once the session works as the new user, through Jmix's own call:
    // the switch completes as one that succeeds, and the log keeps the failure
    @Test
    void aFailureAfterTheSessionWorksAsTheNewUserCompletesTheSwitch(CapturedOutput output) throws Exception {
        ConfigurableApplicationContext context = (ConfigurableApplicationContext) applicationContext;

        ApplicationListener<ApplicationEvent> failing = event -> {
            if (event instanceof InteractiveAuthenticationSuccessEvent signedIn
                    && signedIn.getGeneratedBy() == StandLoginViewSupport.class) {
                throw new IllegalStateException("A listener of the login failed");
            }
        };

        request(() -> logIn("alice"));

        String english = cookie;
        String session = sessionId();
        context.addApplicationListener(failing);

        try {
            switchLanguage(RUSSIAN);
        } finally {
            context.removeApplicationListener(failing);
        }

        awaitDeleted(english);

        assertThat(output).contains("A step of the sign-in failed once the session worked as the user of the new copy")
                .contains("A listener of the login failed");
        assertThat(UiTestUtils.getOpenedNotifications()).isEmpty();
        assertThat(cookie).isNotEqualTo(english);
        assertThat(registry.all()).extracting(Copy::key, Copy::language).containsExactly(tuple(cookie, "ru"));
        assertThat(sessionId()).isNotEqualTo(session);
        assertThat(lastLocale).isEqualTo("ru");
        assertThat(currentAuthentication.getLocale()).isEqualTo(RUSSIAN);
        assertThat(VaadinSession.getCurrent().getLocale()).isEqualTo(RUSSIAN);
        assertThat(pageLoads()).containsExactly(List.of("/", "_self"));
    }

    @Test
    void theMainViewAfterTheSwitchNamesTheUserInTheLanguage() throws Exception {
        request(() -> logIn("alice"));

        String englishName = userMenuName();
        switchLanguage(RUSSIAN);

        request(() -> {
            openLoginForm();
            viewNavigators.view(UiTestUtils.getCurrentView(), MainView.class)
                    .navigate();
        });

        User russianAlice = userOf(cookie, "alice");

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(MainView.class);
        assertThat(userMenuName()).isEqualTo(russianAlice.getFirstName() + " " + russianAlice.getLastName());
        assertThat(userMenuName()).isNotEqualTo(englishName);
    }

    @Test
    void theVisitorSwitchesBackToAFreshCopy() throws Exception {
        request(() -> logIn("alice"));

        String english = cookie;
        switchLanguage(RUSSIAN);

        String russian = cookie;
        switchLanguage(Locale.ENGLISH);
        awaitDeleted(english, russian);

        assertThat(cookie).isNotIn(english, russian);
        assertThat(registry.all()).extracting(Copy::key, Copy::language).containsExactly(tuple(cookie, "en"));
        assertThat(currentAuthentication.getUser().getUsername()).isEqualTo("alice");
        assertThat(currentAuthentication.getLocale()).isEqualTo(Locale.ENGLISH);
    }

    // the template is built at the start, or built anew while the visitor reads the question
    @Test
    void aLanguageWhoseTemplateIsNotReadyGivesTheNoticeAndNoChange() throws Exception {
        Path marker = StandScope.template("ru")
                .folder(properties.dataDir())
                .resolve(TemplateBuilder.READY);

        Path aside = marker.resolveSibling("ready.aside");
        request(() -> logIn("alice"));

        String english = cookie;
        String session = sessionId();
        Authentication login = SecurityContextHelper.getAuthentication();
        Files.move(marker, aside);

        try {
            // not ready when the visitor chooses the language: no question
            request(() -> languageAction().switchTo(RUSSIAN));

            NotificationInfo atTheChoice = UiTestUtils.getLastOpenedNotification();
            List<DialogInfo> questions = UiTestUtils.getOpenedDialogs();
            // ready then, and built anew before the answer
            Files.move(aside, marker);
            request(() -> languageAction().switchTo(RUSSIAN));
            Files.move(marker, aside);
            request(() -> dialogButton(DialogAction.Type.OK).click());

            NotificationInfo atTheAnswer = UiTestUtils.getLastOpenedNotification();

            assertThat(atTheChoice).isNotNull();
            assertThat(atTheChoice.getText())
                    .isEqualTo(messages.getMessage(LoginView.class, "standGettingReady", Locale.ENGLISH));
            assertThat(atTheChoice.getType()).isEqualTo(Notifications.Type.WARNING);
            assertThat(questions).isEmpty();
            assertThat(atTheAnswer).isNotNull();
            assertThat(atTheAnswer).isNotSameAs(atTheChoice);
            assertThat(atTheAnswer.getText()).isEqualTo(atTheChoice.getText());
            assertThat(pageLoads()).isEmpty();
            assertThat(cookie).isEqualTo(english);
            assertThat(registry.all()).extracting(Copy::key, Copy::language).containsExactly(tuple(english, "en"));
            assertThat(sessionId()).isEqualTo(session);
            assertThat(SecurityContextHelper.getAuthentication()).isSameAs(login);
            assertThat(currentAuthentication.getLocale()).isEqualTo(Locale.ENGLISH);
        } finally {
            if (Files.exists(aside)) {
                Files.move(aside, marker);
            }
        }
    }

    // the template cannot be read, as when its folder is gone: the visitor keeps working in their copy
    @Test
    void aCopyThatCannotBeMadeLeavesTheVisitorInTheirCopy(CapturedOutput output) throws Exception {
        Path script = StandScope.template("ru")
                .folder(properties.dataDir())
                .resolve(TemplateBuilder.SCRIPT);

        Path aside = script.resolveSibling("db.script.aside");
        request(() -> logIn("alice"));

        String english = cookie;
        String session = sessionId();
        Authentication login = SecurityContextHelper.getAuthentication();
        Files.move(script, aside);

        try {
            switchLanguage(RUSSIAN);

            NotificationInfo notice = UiTestUtils.getLastOpenedNotification();

            assertThat(notice).isNotNull();
            assertThat(notice.getText()).isEqualTo("Couldn't change the language. Try again later");
            assertThat(notice.getType()).isEqualTo(Notifications.Type.ERROR);
            assertThat(pageLoads()).isEmpty();
            assertThat(cookie).isEqualTo(english);
            assertThat(registry.all()).extracting(Copy::key, Copy::language).containsExactly(tuple(english, "en"));
            assertThat(sessionId()).isEqualTo(session);
            assertThat(SecurityContextHelper.getAuthentication()).isSameAs(login);
            assertThat(output).contains("A copy of the demo data is not made at a change of the language");
        } finally {
            Files.move(aside, script);
        }
    }

    // the templates lack a user the visitor made in their copy, under a name of their choice with line breaks: the
    // switch makes no copy for them, and the log keeps the name on its line
    @Test
    void aUserTheNewCopyLacksStaysSignedInToTheirCopy(CapturedOutput output) throws Exception {
        String carol = "carol\r\nINFO forged";
        request(() -> logIn("alice"));

        String english = cookie;

        StandScopeHolder.call(StandScope.copy(english), () -> {
            systemAuthenticator.runWithSystem(() -> addEmployee(carol, carol));

            return null;
        });

        logOut();
        request(() -> logIn(carol));

        String session = sessionId();
        Authentication login = SecurityContextHelper.getAuthentication();
        switchLanguage(RUSSIAN);

        NotificationInfo notice = UiTestUtils.getLastOpenedNotification();

        assertThat(notice).isNotNull();
        assertThat(notice.getText()).isEqualTo("The language isn't changed: your account is unavailable in it");
        assertThat(notice.getType()).isEqualTo(Notifications.Type.ERROR);
        assertThat(copiesMade(output, "ru")).isZero();
        assertThat(output).contains("The language is not changed for user \"carol<EOL><EOL>INFO forged\"");
        assertThat(output).doesNotContain("\nINFO forged");
        assertThat(pageLoads()).isEmpty();
        assertThat(cookie).isEqualTo(english);
        assertThat(registry.all()).extracting(Copy::key, Copy::language).containsExactly(tuple(english, "en"));
        assertThat(sessionId()).isEqualTo(session);
        assertThat(SecurityContextHelper.getAuthentication()).isSameAs(login);
        assertThat(currentAuthentication.getUser().getUsername()).isEqualTo(carol);
        assertThat(currentAuthentication.getLocale()).isEqualTo(Locale.ENGLISH);
    }

    // the visitor may make a user of their own in their copy under the username of a demo user, with a password of
    // their choice; the switch signs in only the user of the session, the same row in the copy
    @Test
    void aUserTheVisitorMadeDoesNotBecomeTheDemoUserOfTheSameName(CapturedOutput output) throws Exception {
        request(() -> logIn("alice"));

        String english = cookie;

        StandScopeHolder.call(StandScope.copy(english), () -> {
            systemAuthenticator.runWithSystem(() -> {
                rename("peter", "peter.demo");
                addEmployee("peter", "own");
            });

            return null;
        });

        logOut();
        request(() -> logIn("peter", "own"));

        String session = sessionId();
        Authentication login = SecurityContextHelper.getAuthentication();
        switchLanguage(RUSSIAN);

        NotificationInfo notice = UiTestUtils.getLastOpenedNotification();

        assertThat(notice).isNotNull();
        assertThat(notice.getText()).isEqualTo("The language isn't changed: your account is unavailable in it");
        assertThat(copiesMade(output, "ru")).isZero();
        assertThat(pageLoads()).isEmpty();
        assertThat(cookie).isEqualTo(english);
        assertThat(registry.all()).extracting(Copy::key, Copy::language).containsExactly(tuple(english, "en"));
        assertThat(sessionId()).isEqualTo(session);
        assertThat(SecurityContextHelper.getAuthentication()).isSameAs(login);
    }

    // a user substitution of Jmix: the session works as another user than the one who proved the password
    @Test
    void aSubstitutedUserIsRefusedBeforeACopyIsMade(CapturedOutput output) throws Exception {
        request(() -> logIn("admin"));

        String english = cookie;

        StandScopeHolder.call(StandScope.copy(english), () -> {
            systemAuthenticator.runWithSystem(() -> allowSubstitution("admin", "alice"));

            return null;
        });

        request(() -> userSubstitutionManager.substituteUser("alice"));

        String session = sessionId();
        Authentication substituted = SecurityContextHelper.getAuthentication();
        switchLanguage(RUSSIAN);

        NotificationInfo notice = UiTestUtils.getLastOpenedNotification();

        assertThat(substituted).isNotNull();
        assertThat(substituted.getName()).isEqualTo("admin");
        assertThat(notice).isNotNull();
        assertThat(notice.getText()).isEqualTo("The language isn't changed: your account is unavailable in it");
        assertThat(copiesMade(output, "ru")).isZero();
        assertThat(pageLoads()).isEmpty();
        assertThat(cookie).isEqualTo(english);
        assertThat(registry.all()).extracting(Copy::key, Copy::language).containsExactly(tuple(english, "en"));
        assertThat(sessionId()).isEqualTo(session);
        assertThat(SecurityContextHelper.getAuthentication()).isSameAs(substituted);
        assertThat(currentUserSubstitution.getEffectiveUser().getUsername()).isEqualTo("alice");
    }

    // a user may be disabled in the control database while the copy they signed in to keeps them active
    @Test
    void aDisabledUserIsRefusedBeforeACopyIsMade(CapturedOutput output) throws Exception {
        request(() -> logIn("peter"));

        String english = cookie;
        String session = sessionId();
        Authentication login = SecurityContextHelper.getAuthentication();
        activateInControl("peter", false);

        try {
            switchLanguage(RUSSIAN);
        } finally {
            activateInControl("peter", true);
        }

        NotificationInfo notice = UiTestUtils.getLastOpenedNotification();

        assertThat(notice).isNotNull();
        assertThat(notice.getText()).isEqualTo("The language isn't changed: your account is unavailable in it");
        assertThat(output).contains("DisabledException");
        assertThat(copiesMade(output, "ru")).isZero();
        assertThat(pageLoads()).isEmpty();
        assertThat(cookie).isEqualTo(english);
        assertThat(registry.all()).extracting(Copy::key, Copy::language).containsExactly(tuple(english, "en"));
        assertThat(sessionId()).isEqualTo(session);
        assertThat(SecurityContextHelper.getAuthentication()).isSameAs(login);
    }

    // a request of another tab may wait for the session while a switch in the first tab replaces it
    @Test
    void aSwitchWhoseSessionIsOverChangesNothing(CapturedOutput output) throws Exception {
        request(() -> logIn("alice"));

        String english = cookie;
        Authentication login = SecurityContextHelper.getAuthentication();
        request(() -> languageAction().switchTo(RUSSIAN));

        HttpSession session = httpRequest().getSession(false);
        assertThat(session).isNotNull();
        session.invalidate();

        request(() -> dialogButton(DialogAction.Type.OK).click());

        NotificationInfo notice = UiTestUtils.getLastOpenedNotification();

        assertThat(notice).isNotNull();
        assertThat(notice.getText()).isEqualTo("The language isn't changed: your account is unavailable in it");
        assertThat(copiesMade(output, "ru")).isZero();
        assertThat(httpRequest().getSession(false)).isNull();
        assertThat(pageLoads()).isEmpty();
        assertThat(cookie).isEqualTo(english);
        assertThat(registry.all()).extracting(Copy::key, Copy::language).containsExactly(tuple(english, "en"));
        assertThat(SecurityContextHelper.getAuthentication()).isSameAs(login);
    }

    // a browser may send a command without the cookie, and the request works in the copy its session keeps
    @Test
    void aSwitchWithoutTheCookieReplacesTheCopyOfTheSession() throws Exception {
        request(() -> logIn("alice"));

        String english = cookie;
        request(() -> languageAction().switchTo(RUSSIAN));
        cookie = null;

        request(() -> {
            // what StandScopeHolder.resolve() finds in the HTTP session of a browser, which the Vaadin session of the
            // UI tests does not have
            StandScopeHolder.set(StandScope.copy(english));
            dialogButton(DialogAction.Type.OK).click();
        });

        awaitDeleted(english);

        assertThat(cookie).isNotNull();
        assertThat(cookie).isNotEqualTo(english);
        assertThat(registry.all()).extracting(Copy::key, Copy::language).containsExactly(tuple(cookie, "ru"));
        HttpSession session = httpRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getAttribute(StandScopeHolder.SESSION_ATTRIBUTE)).isEqualTo(StandScope.copy(cookie).id());
    }

    // a crafted client may send two clicks on OK in one request: the second finds the new copy in the language
    @Test
    void aSecondOkInTheSameRequestMakesNoSecondCopy(CapturedOutput output) throws Exception {
        request(() -> logIn("alice"));

        String english = cookie;
        request(() -> languageAction().switchTo(RUSSIAN));

        Button ok = dialogButton(DialogAction.Type.OK);

        request(() -> {
            ok.click();
            ok.click();
        });

        awaitDeleted(english);

        assertThat(copiesMade(output, "ru")).isEqualTo(1);
        assertThat(registry.all()).extracting(Copy::key, Copy::language).containsExactly(tuple(cookie, "ru"));
        assertThat(currentAuthentication.getUser().getUsername()).isEqualTo("alice");
        assertThat(currentAuthentication.getLocale()).isEqualTo(RUSSIAN);
    }

    // another tab of the browser shows a view of the old copy; push brings it the main view of the new one
    @Test
    void theOtherTabsOfTheBrowserLoadTheMainViewToo() throws Exception {
        request(() -> logIn("alice"));

        VaadinSession session = VaadinSession.getCurrent();
        UI otherTab = new UI();
        otherTab.getInternals().setSession(session);
        otherTab.doInit(VaadinRequest.getCurrent(), 2, "otherTab");
        session.addUI(otherTab);
        switchLanguage(RUSSIAN);
        // what Vaadin runs once the request of the click lets the session go
        session.getService().runPendingAccessTasks(session);

        assertThat(pageLoads()).containsExactly(List.of("/", "_self"));
        assertThat(pageLoads(otherTab)).containsExactly(List.of("/", "_self"));
    }

    // a deletion that takes long, as one that waits for a document of the old copy being indexed, holds no click: the
    // old copy goes in a job of the scheduler after the response
    @Test
    void theClickReturnsBeforeTheOldCopyIsDeleted() throws Exception {
        request(() -> logIn("alice"));

        String english = cookie;
        boolean goneWhenTheClickReturned;
        List<String> deletionsHeld;

        // the scheduler holds its jobs, so a deletion there runs only once the test lets it
        scheduler.standby();

        try {
            switchLanguage(RUSSIAN);

            goneWhenTheClickReturned = registry.find(english)
                    .isEmpty();

            deletionsHeld = deletionsOnTheScheduler();
        } finally {
            scheduler.start();
        }

        awaitDeleted(english);

        assertThat(goneWhenTheClickReturned).isFalse();
        assertThat(deletionsHeld).containsExactly(english);
        assertThat(pageLoads()).containsExactly(List.of("/", "_self"));
        assertThat(registry.all()).extracting(Copy::key, Copy::language).containsExactly(tuple(cookie, "ru"));
    }

    // the server tells this listener of every HTTP session that ends, and the registry of sessions forgets it: the old
    // session of a switch counts against no limit of sessions per user
    @Test
    void theOldSessionOfASwitchLeavesTheRegistryOfSessions() throws Exception {
        request(() -> logIn("alice"));

        MockHttpSession before = (MockHttpSession) httpRequest().getSession(false);
        assertThat(before).isNotNull();
        switchLanguage(RUSSIAN);

        boolean registered = sessionRegistry.getSessionInformation(before.getId()) != null;

        HttpSessionListener listener = applicationContext.getBean("sec_HttpSessionEventPublisher",
                HttpSessionListener.class);

        ServletContext servletContext = ((VaadinServletService) VaadinService.getCurrent()).getServlet()
                .getServletContext();

        // the sessions of the test end without the server, so the test tells the listener as the server would
        listener.sessionDestroyed(new HttpSessionEvent(new MockHttpSession(servletContext, before.getId())));

        assertThat(before.isInvalid()).isTrue();
        assertThat(registered).isTrue();
        assertThat(sessionRegistry.getSessionInformation(before.getId())).isNull();
    }

    // a copy costs memory and indexes, so the credentials come before it
    @Test
    void aWrongPasswordOrAnUnknownUserMakesNoCopy() throws Exception {
        request(() -> logIn("alice", "peter"));

        boolean wrongPassword = loginForm().isError();
        loginForm().setError(false);
        request(() -> logIn("nobody", "nobody"));

        assertThat(wrongPassword).isTrue();
        assertThat(loginForm().isError()).isTrue();
        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(LoginView.class);
        assertThat(registry.all()).isEmpty();
        assertThat(cookie).isNull();
    }

    @Test
    void theRightPasswordMakesACopyAndSignsIn() throws Exception {
        request(() -> logIn("alice"));

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(MainView.class);
        assertThat(currentAuthentication.getUser().getUsername()).isEqualTo("alice");
        assertThat(registry.all()).extracting(Copy::key).containsExactly(cookie);
    }

    // the visitor changes the users of their copy at will, so a login that makes a copy checks the credentials
    // against the demo users of the control database, not against the copy of the browser in the other language
    @Test
    void aLoginThatMakesACopyChecksTheCredentialsAgainstTheControlDatabase() throws Exception {
        request(() -> logIn("alice"));

        String english = cookie;

        StandScopeHolder.call(StandScope.copy(english), () -> {
            systemAuthenticator.runWithSystem(() -> changePassword("alice", "changed"));

            return null;
        });

        logOut();

        request(() -> {
            loginForm().setSelectedLocale(RUSSIAN);
            logIn("alice", "changed");
        });

        boolean refused = loginForm().isError();
        List<Copy> afterTheRefusal = List.copyOf(registry.all());
        request(() -> logIn("alice"));
        awaitDeleted(english);

        assertThat(refused).isTrue();
        assertThat(afterTheRefusal).extracting(Copy::key, Copy::language).containsExactly(tuple(english, "en"));
        assertThat(registry.all()).extracting(Copy::key, Copy::language).containsExactly(tuple(cookie, "ru"));
        assertThat(currentAuthentication.getUser().getUsername()).isEqualTo("alice");
    }

    // another tab still shows the form after a login in the first one, or the visitor opens the form while signed in
    @Test
    void aSignedInBrowserLogsInAgainInTheOtherLanguage() throws Exception {
        request(() -> logIn("alice"));

        String english = cookie;
        request(this::openLoginForm);

        request(() -> {
            loginForm().setSelectedLocale(RUSSIAN);
            logIn("alice");
        });

        awaitDeleted(english);

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(MainView.class);
        assertThat(currentAuthentication.getUser().getUsername()).isEqualTo("alice");
        assertThat(cookie).isNotEqualTo(english);
        assertThat(registry.all()).extracting(Copy::key, Copy::language).containsExactly(tuple(cookie, "ru"));
    }

    // the check of the credentials runs as the system and gives the thread back the visitor the session has
    @Test
    void theCheckOfTheCredentialsLeavesTheSignedInVisitorAsTheyWere() throws Exception {
        AtomicReference<Authentication> before = new AtomicReference<>();
        AtomicReference<Authentication> after = new AtomicReference<>();

        request(() -> logIn("alice"));
        request(this::openLoginForm);

        request(() -> {
            before.set(SecurityContextHelper.getAuthentication());
            standLogin.prepare(RUSSIAN, "alice", "alice");
            after.set(SecurityContextHelper.getAuthentication());
        });

        assertThat(before.get().getName()).isEqualTo("alice");
        assertThat(after.get()).isSameAs(before.get());
    }

    // the template cannot be read, as when its folder is gone: the visitor learns nothing of the server
    @Test
    void aCopyThatCannotBeMadeLeavesTheFormWithANotice(CapturedOutput output) throws Exception {
        Path script = StandScope.template("ru")
                .folder(properties.dataDir())
                .resolve(TemplateBuilder.SCRIPT);

        Path aside = script.resolveSibling("db.script.aside");
        Files.move(script, aside);

        try {
            request(() -> {
                loginForm().setSelectedLocale(RUSSIAN);
                logIn("alice");
            });

            NotificationInfo notice = UiTestUtils.getLastOpenedNotification();

            assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(LoginView.class);
            assertThat(loginForm().isEnabled()).isTrue();
            assertThat(notice).isNotNull();
            assertThat(notice.getText()).isEqualTo(messages.getMessage(LoginView.class, "standUnavailable", RUSSIAN));
            assertThat(notice.getType()).isEqualTo(Notifications.Type.ERROR);
            assertThat(registry.all()).isEmpty();
            assertThat(cookie).isNull();
            assertThat(output).contains("A copy of the demo data is not made at a login");
        } finally {
            Files.move(aside, script);
        }
    }

    @Test
    void theFormTakesTheNextLoginAfterEveryRefusal() throws Exception {
        Path template = StandScope.template("en")
                .folder(properties.dataDir());

        Path marker = template.resolve(TemplateBuilder.READY);
        Path markerAside = marker.resolveSibling("ready.aside");
        Path script = template.resolve(TemplateBuilder.SCRIPT);
        Path scriptAside = script.resolveSibling("db.script.aside");
        List<Boolean> enabledAfterRefusals = new ArrayList<>();
        // two logins while the template is being built, as it has no ready marker then, and two while it cannot be read
        Files.move(marker, markerAside);

        try {
            for (int refusal = 0; refusal < 2; refusal++) {
                request(() -> submitInBrowser("alice"));
                enabledAfterRefusals.add(!disabledInBrowser);
            }
        } finally {
            Files.move(markerAside, marker);
        }

        Files.move(script, scriptAside);

        try {
            for (int refusal = 0; refusal < 2; refusal++) {
                request(() -> submitInBrowser("alice"));
                enabledAfterRefusals.add(!disabledInBrowser);
            }
        } finally {
            Files.move(scriptAside, script);
        }

        request(() -> submitInBrowser("alice"));

        assertThat(enabledAfterRefusals).containsExactly(true, true, true, true);
        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(MainView.class);
        assertThat(registry.all()).extracting(Copy::key, Copy::language).containsExactly(tuple(cookie, "en"));
    }

    @Test
    void anUnknownCookieGetsNoCopy() throws Exception {
        String forged = CopyRegistry.newKey();
        AtomicReference<StandScope> scope = new AtomicReference<>(StandScope.template("en"));
        cookie = forged;

        request(() -> scope.set(StandScopeHolder.current()));
        request(() -> logIn("alice"));

        assertThat(scope.get()).isNull();
        assertThat(cookie).isNotEqualTo(forged);
        assertThat(registry.all()).extracting(Copy::key).containsExactly(cookie);
    }

    private void request(Runnable work) throws Exception {
        MockHttpServletRequest httpRequest = httpRequest();
        MockHttpServletResponse httpResponse = new MockHttpServletResponse();

        if (cookie == null) {
            httpRequest.setCookies();
        } else {
            httpRequest.setCookies(new Cookie(CopyCookie.NAME, cookie));
        }

        VaadinResponse response = new VaadinServletResponse(httpResponse,
                (VaadinServletService) VaadinService.getCurrent());

        CurrentInstance.set(VaadinResponse.class, response);
        // the server's context of a request has the response too, where Jmix's login writes its cookies
        RequestAttributes requestContext = RequestContextHolder.getRequestAttributes();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(httpRequest, httpResponse));

        try {
            filter.doFilter(httpRequest, httpResponse, (servletRequest, servletResponse) -> work.run());
        } finally {
            RequestContextHolder.setRequestAttributes(requestContext);
            CurrentInstance.set(VaadinResponse.class, null);
            // Vaadin holds its current instances weakly: a collected response would lose the cookie of a login
            Reference.reachabilityFence(response);
        }

        Cookie set = httpResponse.getCookie(CopyCookie.NAME);

        if (set != null) {
            cookie = set.getValue();
        }

        Cookie language = httpResponse.getCookie("LAST_LOCALE");

        if (language != null) {
            lastLocale = language.getValue();
        }
    }

    private void logOut() throws Exception {
        httpRequest().getSession()
                .invalidate();

        openTheLoginFormAsNobody();
    }

    private void openTheLoginFormAsNobody() throws Exception {
        new AnonymousUiTestAuthenticator().setupAuthentication(applicationContext);
        httpRequest().getSession(true);
        request(this::openLoginForm);
    }

    private void requestOfAnotherTab(String tabCookie) {
        MockHttpServletRequest tabRequest = new MockHttpServletRequest();
        tabRequest.setSession(Objects.requireNonNull(httpRequest().getSession(false)));
        tabRequest.setCookies(new Cookie(CopyCookie.NAME, tabCookie));

        try {
            filter.doFilter(tabRequest, new MockHttpServletResponse(), (servletRequest, servletResponse) -> {
            });
        } catch (ServletException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void openLoginForm() {
        viewNavigators.view(UiTestUtils.getCurrentView(), LoginView.class)
                .navigate();
    }

    private void switchLanguage(Locale locale) throws Exception {
        request(() -> languageAction().switchTo(locale));
        request(() -> dialogButton(DialogAction.Type.OK).click());
    }

    private UserMenu userMenu() {
        return UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "userMenu");
    }

    private UserMenuLanguageSwitchAction languageAction() {
        ActionUserMenuItem item = (ActionUserMenuItem) userMenu().getItem("languageMenuItem");

        return (UserMenuLanguageSwitchAction) item.getAction();
    }

    private UserMenuItem languageItem(String language) {
        UserMenuItem.HasSubMenu item = (UserMenuItem.HasSubMenu) userMenu().getItem("languageMenuItem");

        return item.getSubMenu()
                .getItem("userMenu_languageSwitch_%sUserMenuItem".formatted(language));
    }

    private static List<List<Object>> pageLoads() {
        return pageLoads(UI.getCurrent());
    }

    private static List<List<Object>> pageLoads(UI ui) {
        return ui.getInternals()
                .dumpPendingJavaScriptInvocations()
                .stream()
                .map(PendingJavaScriptInvocation::getInvocation)
                .filter(invocation -> invocation.getExpression().contains("window.open(")
                        || invocation.getExpression().contains("window.location.reload("))
                .map(UIInternals.JavaScriptInvocation::getParameters)
                .toList();
    }

    private static long copiesMade(CapturedOutput output, String language) {
        return output.getAll()
                .lines()
                .filter(line -> line.contains("A copy of the template " + language + " is made"))
                .count();
    }

    private List<String> deletionsOnTheScheduler() throws SchedulerException {
        List<String> copies = new ArrayList<>();

        for (JobKey job : scheduler.getJobKeys(GroupMatcher.anyJobGroup())) {
            JobDetail detail = scheduler.getJobDetail(job);

            if (detail.getJobClass() == CopyDeletionJob.class) {
                copies.add(detail.getJobDataMap()
                        .getString(CopyDeletionJob.COPY));
            }
        }

        return copies;
    }

    private void awaitDeleted(String... keys) {
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(registry.all()).extracting(Copy::key).doesNotContain(keys));
    }

    private String userMenuName() {
        MenuBar bar = (MenuBar) userMenu().getChildren()
                .findFirst()
                .orElseThrow();

        MenuItem button = bar.getItems()
                .getFirst();

        return withDescendants(button)
                .filter(component -> component.hasClassName("user-menu-text"))
                .map(component -> component.getElement().getText())
                .findFirst()
                .orElseThrow();
    }

    private static Stream<Component> withDescendants(Component component) {
        return Stream.concat(Stream.of(component), component.getChildren()
                .flatMap(StandLoginTest::withDescendants));
    }

    private User userOf(String key, String username) throws Exception {
        return StandScopeHolder.call(StandScope.copy(key), () -> systemAuthenticator.withSystem(() ->
                userNamed(username)));
    }

    private void addEmployee(String username, String password) {
        User user = dataManager.create(User.class);
        user.setUsername(username);
        user.setPassword("{noop}" + password);
        user.setActive(true);

        RoleAssignmentEntity assignment = dataManager.create(RoleAssignmentEntity.class);
        assignment.setUsername(username);
        assignment.setRoleCode(EmployeeRole.CODE);
        assignment.setRoleType(RoleAssignmentRoleType.RESOURCE);

        dataManager.save(user, assignment);
    }

    private static List<GrantedAuthority> factors(Authentication authentication) {
        return authentication.getAuthorities()
                .stream()
                .filter(FactorGrantedAuthority.class::isInstance)
                .map(GrantedAuthority.class::cast)
                .toList();
    }

    private static String sessionId() {
        return Objects.requireNonNull(httpRequest().getSession(false))
                .getId();
    }

    // the demo password of a demo user is the username
    private void logIn(String username) {
        logIn(username, username);
    }

    private void logIn(String username, String password) {
        JmixLoginForm form = loginForm();
        ComponentUtil.fireEvent(form, new LoginEvent(form, true, username, password));
    }

    private void submitInBrowser(String username) {
        // the form disables itself in the browser at a submit and ignores Enter and Log in until a response enables it
        if (disabledInBrowser) {
            return;
        }

        JmixLoginForm form = loginForm();
        StateNode node = form.getElement()
                .getNode();

        receiveResponse(node);
        disabledInBrowser = true;

        // the browser sends the login together with the properties the server synchronizes on it
        boolean synchronizesDisabled = node.getFeature(ElementListenerMap.class)
                .getExpressions("login")
                .contains(JsonConstants.SYNCHRONIZE_PROPERTY_TOKEN + "disabled");

        if (synchronizesDisabled) {
            try {
                node.getFeature(ElementPropertyMap.class)
                        .deferredUpdateFromClient("disabled", true)
                        .run();
            } catch (PropertyChangeDeniedException e) {
                throw new IllegalStateException(e);
            }
        }

        ComponentUtil.fireEvent(form, new LoginEvent(form, true, username, username));
        receiveResponse(node);
    }

    private void receiveResponse(StateNode form) {
        UI.getCurrent()
                .getInternals()
                .getStateTree()
                .collectChanges(change -> {
                    if (change instanceof MapPutChange put && put.getNode() == form
                            && put.getFeature() == ElementPropertyMap.class && put.getKey().equals("disabled")) {
                        disabledInBrowser = (Boolean) put.getValue();
                    }
                });
    }

    // the demo users keep their passwords unencoded, and so does this change
    private void changePassword(String username, String password) {
        User user = userNamed(username);
        user.setPassword("{noop}" + password);
        dataManager.save(user);
    }

    private void rename(String username, String newUsername) {
        User user = userNamed(username);
        user.setUsername(newUsername);
        dataManager.save(user);
    }

    // the control database keeps the demo users of every template
    private void activateInControl(String username, boolean active) {
        StandScopeHolder.runInControl(() -> systemAuthenticator.runWithSystem(() -> {
            User user = userNamed(username);
            user.setActive(active);
            dataManager.save(user);
        }));
    }

    private User userNamed(String username) {
        return dataManager.load(User.class)
                .query("select u from User u where u.username = :username")
                .parameter("username", username)
                .one();
    }

    private void allowSubstitution(String username, String substitutedUsername) {
        UserSubstitutionEntity substitution = dataManager.create(UserSubstitutionEntity.class);
        substitution.setUsername(username);
        substitution.setSubstitutedUsername(substitutedUsername);
        dataManager.save(substitution);
    }

    private UUID upload(String fileName) {
        return documentService.upload(folderService.getRoot(), List.of(TestFiles.file(tempDir, fileName, "text")))
                .created()
                .getFirst()
                .getId();
    }

    private JmixLoginForm loginForm() {
        return UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "login");
    }

    private Span languageWarning() {
        return UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "languageWarning");
    }

    private Button usageHelpButton() {
        return UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "usageHelpButton");
    }

    private static MockHttpServletRequest httpRequest() {
        return (MockHttpServletRequest) ((VaadinServletRequest) VaadinRequest.getCurrent()).getRequest();
    }
}
