package com.company.docai.stand;

import com.vaadin.flow.internal.CurrentInstance;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.VaadinServletResponse;
import com.vaadin.flow.server.VaadinServletService;
import io.jmix.core.TimeSource;
import io.jmix.core.security.SystemAuthenticator;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;

import java.lang.ref.Reference;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static java.time.ZoneOffset.UTC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A change of the language around its failures, and the old copy going in a job of the scheduler after the response.
 */
class StandLoginSwitchTest {

    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");

    private final CopyRegistry registry = new CopyRegistry(timeSource());

    private final CopyFactory copyFactory = mock(CopyFactory.class);

    private final StandSignIn signIn = mock(StandSignIn.class);

    private final SystemAuthenticator systemAuthenticator = mock(SystemAuthenticator.class);

    private final Scheduler scheduler = mock(Scheduler.class);

    // the jobs the scheduler of the stand is given, run by the test
    private final List<JobDetail> scheduled = new ArrayList<>();

    private final StandLogin standLogin = new StandLogin(registry, copyFactory, mock(AuthenticationManager.class),
            systemAuthenticator, signIn, scheduler);

    private final MockHttpSession session = new MockHttpSession();

    private final MockHttpServletResponse httpResponse = new MockHttpServletResponse();

    private final Authentication authentication = mock(Authentication.class);

    private final String newKey = CopyRegistry.newKey();

    private Copy english;

    private VaadinRequest request;

    private VaadinResponse response;

    @BeforeEach
    void aVisitorWorksInTheirEnglishCopy() throws SchedulerException {
        english = registry.register(CopyRegistry.newKey(), "en");
        session.setAttribute(StandScopeHolder.SESSION_ATTRIBUTE, StandScope.copy(english.key()).id());

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setSession(session);
        VaadinServletService service = mock(VaadinServletService.class);
        request = new VaadinServletRequest(httpRequest, service);
        response = new VaadinServletResponse(httpResponse, service);
        CurrentInstance.set(VaadinRequest.class, request);
        CurrentInstance.set(VaadinResponse.class, response);
        StandScopeHolder.set(StandScope.copy(english.key()));

        UserDetails visitor = mock(UserDetails.class);
        when(signIn.visitor()).thenReturn(visitor);
        when(signIn.authenticate(RUSSIAN)).thenReturn(authentication);
        when(copyFactory.isReady("ru")).thenReturn(true);
        when(copyFactory.create("ru")).thenAnswer(invocation -> registry.register(newKey, "ru"));

        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0)
                    .run();

            return null;
        }).when(systemAuthenticator).runWithSystem(any(Runnable.class));

        when(scheduler.scheduleJob(any(JobDetail.class), any(Trigger.class))).thenAnswer(invocation -> {
            scheduled.add(invocation.getArgument(0));

            return new Date();
        });
    }

    @AfterEach
    void forgetTheRequest() {
        CurrentInstance.clearAll();
        StandScopeHolder.set(null);
        // Vaadin holds its current instances weakly: the request and the response must outlive the test
        Reference.reachabilityFence(request);
        Reference.reachabilityFence(response);
    }

    // a failure of the deletion goes with the refusal, which the visitor is told
    @Test
    void aRefusalOfTheUserDeletesTheNewCopy() {
        DisabledException refusal = new DisabledException("User is disabled");
        when(signIn.authenticate(RUSSIAN)).thenThrow(refusal);
        doThrow(new IllegalStateException("The indexes are not deleted")).when(copyFactory).delete(newKey);

        assertThatThrownBy(() -> standLogin.switchLanguage(RUSSIAN)).isSameAs(refusal);

        assertThat(refusal.getSuppressed()).extracting(Throwable::getMessage)
                .containsExactly("The indexes are not deleted");
        assertThat(httpResponse.getCookies()).isEmpty();
        assertThat(session.getAttribute(StandScopeHolder.SESSION_ATTRIBUTE))
                .isEqualTo(StandScope.copy(english.key()).id());
        assertThat(StandScopeHolder.current()).isEqualTo(StandScope.copy(english.key()));
        assertThat(scheduled).isEmpty();
        verify(copyFactory).delete(newKey);
    }

    // as when another request invalidates the session of the request meanwhile
    @Test
    void aFailureBeforeTheSessionWorksAsTheNewUserPutsTheOldCopyBack() {
        IllegalStateException failure = new IllegalStateException("The session is invalidated");
        doThrow(failure).when(signIn).signIn(authentication, RUSSIAN);
        doThrow(new IllegalStateException("The indexes are not deleted")).when(copyFactory).delete(newKey);

        assertThatThrownBy(() -> standLogin.switchLanguage(RUSSIAN)).isSameAs(failure);

        assertThat(failure.getSuppressed()).extracting(Throwable::getMessage)
                .containsExactly("The indexes are not deleted");
        // the browser keeps the last cookie of the response
        assertThat(httpResponse.getCookies()).extracting(Cookie::getValue).containsExactly(newKey, english.key());
        assertThat(session.getAttribute(StandScopeHolder.SESSION_ATTRIBUTE))
                .isEqualTo(StandScope.copy(english.key()).id());
        assertThat(StandScopeHolder.current()).isEqualTo(StandScope.copy(english.key()));
        assertThat(scheduled).isEmpty();
        verify(copyFactory).delete(newKey);
    }

    @Test
    void theOldCopyGoesOnTheSchedulerOnceTheSessionWorksAsTheNewUser() {
        Copy copy = standLogin.switchLanguage(RUSSIAN);

        verify(copyFactory, never()).delete(any());
        runScheduled();

        assertThat(copy.key()).isEqualTo(newKey);
        assertThat(scheduled).hasSize(1);
        assertThat(httpResponse.getCookies()).extracting(Cookie::getValue).containsExactly(newKey);
        assertThat(StandScopeHolder.current()).isEqualTo(StandScope.copy(newKey));
        verify(signIn).signIn(authentication, RUSSIAN);
        verify(copyFactory).delete(english.key());
        verify(copyFactory, never()).delete(newKey);
    }

    private void runScheduled() {
        for (JobDetail job : scheduled) {
            JobExecutionContext context = mock(JobExecutionContext.class);
            when(context.getMergedJobDataMap()).thenReturn(job.getJobDataMap());

            assertThat(job.getJobClass()).isEqualTo(CopyDeletionJob.class);
            new CopyDeletionJob(copyFactory).execute(context);
        }
    }

    private static TimeSource timeSource() {
        TimeSource timeSource = mock(TimeSource.class);
        when(timeSource.now()).thenReturn(ZonedDateTime.ofInstant(Instant.parse("2026-11-17T10:00:00Z"), UTC));

        return timeSource;
    }
}
