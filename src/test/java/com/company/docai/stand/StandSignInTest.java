package com.company.docai.stand;

import com.vaadin.flow.internal.CurrentInstance;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.VaadinServletService;
import com.vaadin.flow.server.VaadinSession;
import io.jmix.core.AccessManager;
import io.jmix.core.security.PostAuthenticationChecks;
import io.jmix.core.security.PreAuthenticationChecks;
import io.jmix.core.security.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.lang.ref.Reference;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;

/**
 * The sign-in of a change of the language around a failure of Jmix's steps: before the session works as the new
 * authentication the failure goes on, so that the switch puts the old copy back; after it the sign-in completes and
 * the log keeps the failure. Jmix's steps are a stand-in; the request and its session are real.
 */
@ExtendWith(OutputCaptureExtension.class)
class StandSignInTest {

    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");

    private final StandLoginViewSupport loginViewSupport = mock(StandLoginViewSupport.class);

    private final StandSignIn signIn = new StandSignIn(mock(UserRepository.class), mock(PreAuthenticationChecks.class),
            mock(PostAuthenticationChecks.class), mock(AccessManager.class), loginViewSupport);

    private final Authentication authentication = mock(Authentication.class);

    private final MockHttpServletRequest httpRequest = new MockHttpServletRequest();

    private final VaadinRequest request = new VaadinServletRequest(httpRequest, mock(VaadinServletService.class));

    private final VaadinSession vaadinSession = mock(VaadinSession.class);

    @BeforeEach
    void aRequestOfTheBrowser() {
        CurrentInstance.set(VaadinRequest.class, request);
        VaadinSession.setCurrent(vaadinSession);
    }

    @AfterEach
    void forgetTheRequest() {
        CurrentInstance.clearAll();
        SecurityContextHolder.clearContext();
        // Vaadin holds its current instances weakly: the request and the session must outlive the test
        Reference.reachabilityFence(request);
        Reference.reachabilityFence(vaadinSession);
    }

    // as a listener of the event of the login that fails: Jmix has saved the context of the new authentication by then
    @Test
    void aFailureOnceTheSessionWorksAsTheNewUserCompletesTheSignIn(CapturedOutput output) {
        MockHttpSession session = new MockHttpSession();
        httpRequest.setSession(session);
        SecurityContext saved = new SecurityContextImpl(authentication);

        doAnswer(invocation -> {
            session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, saved);

            throw new IllegalStateException("A listener of the login failed");
        }).when(loginViewSupport).signIn(authentication, RUSSIAN);

        signIn.signIn(authentication, RUSSIAN);

        assertThat(SecurityContextHolder.getContext()).isSameAs(saved);
        assertThat(output).contains("A step of the sign-in failed once the session worked as the user of the new copy")
                .contains("A listener of the login failed");
        verify(vaadinSession).setLocale(RUSSIAN);
    }

    // as the strategy of sessions or the save of the context failing: the session keeps the login
    @Test
    void aFailureBeforeTheSessionWorksAsTheNewUserGoesOn() {
        httpRequest.setSession(new MockHttpSession());
        IllegalStateException failure = new IllegalStateException("The context is not saved");
        doThrow(failure).when(loginViewSupport).signIn(authentication, RUSSIAN);

        assertThatThrownBy(() -> signIn.signIn(authentication, RUSSIAN)).isSameAs(failure);

        verify(vaadinSession, never()).setLocale(any());
    }

    // another request of the visitor may end the session between the reads of the check
    @Test
    void aSessionThatEndsMeanwhileWorksAsNoNewUser() {
        httpRequest.setSession(new MockHttpSession() {
            @Override
            public Object getAttribute(String name) {
                throw new IllegalStateException("The session is invalidated");
            }
        });

        IllegalStateException failure = new IllegalStateException("A listener of the login failed");
        doThrow(failure).when(loginViewSupport).signIn(authentication, RUSSIAN);

        assertThatThrownBy(() -> signIn.signIn(authentication, RUSSIAN)).isSameAs(failure);

        verify(vaadinSession, never()).setLocale(any());
    }
}
