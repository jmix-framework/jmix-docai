package com.company.docai.stand;

import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.WrappedSession;
import io.jmix.core.security.SystemAuthenticator;
import org.jspecify.annotations.Nullable;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.session.SessionAuthenticationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Chooses the visitor's copy of the demo data on the stand by its language, at the login and at a change of the
 * language after it. A copy that is replaced goes after the response, in a job of the scheduler, see
 * {@link CopyDeletionJob}.
 */
@Component
@Profile("stand")
public class StandLogin {

    private static final Logger log = LoggerFactory.getLogger(StandLogin.class);

    // the demo set of a browser without a copy, whatever the language of the browser
    private static final String DEFAULT_LANGUAGE = "en";

    private final CopyRegistry registry;
    private final CopyFactory copyFactory;
    private final AuthenticationManager authenticationManager;
    private final SystemAuthenticator systemAuthenticator;
    private final StandSignIn signIn;
    private final Scheduler scheduler;

    public StandLogin(CopyRegistry registry,
                      CopyFactory copyFactory,
                      AuthenticationManager authenticationManager,
                      SystemAuthenticator systemAuthenticator,
                      StandSignIn signIn,
                      Scheduler scheduler) {
        this.registry = registry;
        this.copyFactory = copyFactory;
        this.authenticationManager = authenticationManager;
        this.systemAuthenticator = systemAuthenticator;
        this.signIn = signIn;
        this.scheduler = scheduler;
    }

    /**
     * The language the login form opens with: the one of the browser's copy, or of the copy deleted for idleness, so
     * the next login keeps it, else English.
     */
    public Locale formLocale(List<Locale> locales) {
        String language = browserCopy()
                .map(Copy::language)
                .or(this::expiredLanguage)
                .orElse(DEFAULT_LANGUAGE);

        return locales.stream()
                .filter(locale -> locale.getLanguage().equals(language))
                .findFirst()
                .orElse(locales.getFirst());
    }

    /**
     * Whether a login in the language starts the demo anew, replacing the browser's copy in another language.
     */
    public boolean startsAnew(Locale locale) {
        return browserCopy()
                .filter(copy -> !copy.language().equals(locale.getLanguage()))
                .isPresent();
    }

    /**
     * Whether the browser's copy was deleted because nobody used it for {@code docai.stand.copy-idle-timeout}, so the
     * login form tells the visitor why their changes are gone; a login gives the browser a new copy.
     */
    public boolean isCopyExpired() {
        VaadinRequest request = VaadinRequest.getCurrent();

        return request != null && registry.isExpired(CopyCookie.keyOf(request.getCookies()));
    }

    /**
     * Whether a copy of the template of the language can be made now, as a change of the language needs.
     */
    public boolean isReady(Locale locale) {
        return copyFactory.isReady(locale.getLanguage());
    }

    /**
     * Chooses the copy for a login in the language and binds its scope to the rest of the request. A login that needs a
     * new copy checks the credentials against the demo users of the control database first, so that no copy is made for
     * them in vain.
     *
     * @throws TemplateNotReadyException when the template of the language is not ready yet, before any change
     * @throws CopyNotMadeException      when the new copy cannot be made, which leaves the browser's copy as it is
     * @throws org.springframework.security.core.AuthenticationException when a new copy is due and the control database
     *         does not take the credentials, before any change
     */
    public Copy prepare(Locale locale, String username, String password) {
        String language = locale.getLanguage();

        Copy current = browserCopy()
                .orElse(null);

        Copy copy = current != null && current.language().equals(language)
                ? current
                : replace(current, language, username, password);

        StandScopeHolder.set(StandScope.copy(copy.key()));

        return copy;
    }

    /**
     * Moves the signed-in visitor of the current request to a new copy of the template of the language without asking
     * for the password, see {@link StandSignIn}; the copy the request works in goes after the response. A failure
     * before the session works as the new authentication puts the old copy back and deletes the new one.
     *
     * @throws SessionAuthenticationException when the HTTP session of the request is over or the request works in no
     *                                        copy, before any change
     * @throws TemplateNotReadyException      when the template of the language is not ready yet, before any change
     * @throws CopyNotMadeException           when the new copy cannot be made, before any change
     * @throws org.springframework.security.core.AuthenticationException when the session has no login of the
     *         application or the user may not sign in, before any change; when the new copy refuses the user, the copy
     *         is deleted
     * @throws io.jmix.core.security.AccessDeniedException when the user may not use the UI in the new copy, likewise
     */
    public Copy switchLanguage(Locale locale) {
        checkBrowserRequest();
        checkSession();

        String language = locale.getLanguage();
        Copy current = workingCopy();

        if (current.language().equals(language)) {
            return current;
        }

        checkReady(language);
        UserDetails visitor = signIn.visitor();
        // a validator of Jmix's login decides on a switch too, before it costs a copy
        signIn.validate(visitor, locale);

        // the templates have the demo users of the control database, so a user it does not take gets no copy
        StandScopeHolder.runInControl(() -> systemAuthenticator.runWithSystem(() -> signIn.check(visitor)));

        Copy copy = create(language);
        StandScope previous = StandScopeHolder.current();
        // the user and their rights come from the new copy
        StandScopeHolder.set(StandScope.copy(copy.key()));

        Authentication authentication;

        try {
            authentication = signIn.authenticate(locale);
        } catch (RuntimeException e) {
            StandScopeHolder.set(previous);
            deleteAfterFailure(copy, e);

            throw e;
        }

        try {
            keepInBrowser(copy);
            signIn.signIn(authentication, locale);
        } catch (RuntimeException e) {
            // until the session works as the new authentication, the visitor works in the old copy as before
            StandScopeHolder.set(previous);
            keepInBrowserAfterFailure(current, e);
            deleteAfterFailure(copy, e);

            throw e;
        }

        deleteLater(current);

        return copy;
    }

    private Copy replace(@Nullable Copy current, String language, String username, String password) {
        checkReady(language);
        checkBrowserRequest();

        // the demo users of the control database are those of every template, while the browser's copy may have changed
        // its own; they are read as the system, as the visitor of a signed-in session reaches no control database
        Authentication credentials = UsernamePasswordAuthenticationToken.unauthenticated(username, password);

        StandScopeHolder.runInControl(() -> systemAuthenticator.runWithSystem(() ->
                authenticationManager.authenticate(credentials)));

        Copy copy = create(language);
        keepInBrowser(copy);

        if (current != null) {
            deleteLater(current);
        }

        return copy;
    }

    private void checkReady(String language) {
        if (!copyFactory.isReady(language)) {
            throw new TemplateNotReadyException(language);
        }
    }

    private static void checkBrowserRequest() {
        if (VaadinRequest.getCurrent() == null || VaadinResponse.getCurrent() == null) {
            throw new IllegalStateException("A copy is made only in a request of the browser, whose cookie keeps it");
        }
    }

    // a request of another tab may wait for the Vaadin session while a switch replaces the HTTP session: its own one
    // is over, and going on would start a session of its own with a copy the browser's session does not know
    private static void checkSession() {
        if (VaadinRequest.getCurrent().getWrappedSession(false) == null) {
            throw new SessionAuthenticationException("The HTTP session of the request is over");
        }
    }

    // the cookie's copy, else the one of the session, and the new copy after a switch earlier in the request
    private Copy workingCopy() {
        StandScope scope = StandScopeHolder.resolve();

        return Optional.ofNullable(scope)
                .filter(StandScope::isCopy)
                .flatMap(copyScope -> registry.find(copyScope.key()))
                .orElseThrow(() -> new SessionAuthenticationException("The request works in no copy"));
    }

    private Copy create(String language) {
        try {
            return copyFactory.create(language);
        } catch (RuntimeException e) {
            throw new CopyNotMadeException(language, e);
        }
    }

    // the other tabs of the browser find the new copy in the session before the old one is gone, so none of their
    // requests takes the session for one whose copy is gone
    private static void keepInBrowser(Copy copy) {
        VaadinRequest request = VaadinRequest.getCurrent();
        VaadinResponse response = VaadinResponse.getCurrent();

        response.addCookie(CopyCookie.of(copy.key(), request.isSecure(), request.getContextPath()));

        WrappedSession session = request.getWrappedSession(false);

        if (session != null) {
            session.setAttribute(StandScopeHolder.SESSION_ATTRIBUTE, StandScope.copy(copy.key()).id());
        }
    }

    // the last cookie of the response wins in the browser
    private static void keepInBrowserAfterFailure(Copy copy, RuntimeException failure) {
        try {
            keepInBrowser(copy);
        } catch (RuntimeException e) {
            failure.addSuppressed(e);
        }
    }

    private void deleteAfterFailure(Copy copy, RuntimeException failure) {
        try {
            copyFactory.delete(copy.key());
        } catch (RuntimeException e) {
            failure.addSuppressed(e);
        }
    }

    // the old copy goes in a scheduler job, so no tab waits for its deletion, which waits for a document being indexed
    private void deleteLater(Copy copy) {
        JobDetail deletion = JobBuilder.newJob(CopyDeletionJob.class)
                .usingJobData(CopyDeletionJob.COPY, copy.key())
                .build();

        try {
            scheduler.scheduleJob(deletion, TriggerBuilder.newTrigger()
                    .startNow()
                    .build());
        } catch (SchedulerException e) {
            // unused from now on, the copy goes with the cleaner once it is idle
            log.warn("A replaced copy waits for the cleaner: {}", StandScope.withTags(e.toString()));
        }
    }

    private Optional<String> expiredLanguage() {
        VaadinRequest request = VaadinRequest.getCurrent();

        return request == null
                ? Optional.empty()
                : registry.expiredLanguage(CopyCookie.keyOf(request.getCookies()));
    }

    private Optional<Copy> browserCopy() {
        VaadinRequest request = VaadinRequest.getCurrent();

        return request == null
                ? Optional.empty()
                : registry.find(CopyCookie.keyOf(request.getCookies()));
    }
}
