package com.company.docai.stand;

import io.jmix.core.TimeSource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter of the stand, which binds the copy of the cookie to the request and to the HTTP session.
 */
class StandScopeFilterTest {

    private final AtomicReference<Instant> clock = new AtomicReference<>(Instant.parse("2026-11-17T10:00:00Z"));

    private final CopyRegistry registry = new CopyRegistry(timeSource());

    private final StandScopeFilter filter = new StandScopeFilter(registry);

    private final MockHttpServletRequest request = new MockHttpServletRequest();

    private final MockHttpSession session = new MockHttpSession();

    private final AtomicReference<StandScope> seen = new AtomicReference<>();

    @Test
    void theCookieOfACopyBindsTheCopyToTheRequestAndTheSession() throws Exception {
        Copy copy = registry.register(CopyRegistry.newKey(), "en");
        clock.set(clock.get().plus(Duration.ofMinutes(5)));
        request.setCookies(new Cookie(CopyCookie.NAME, copy.key()));
        request.setSession(session);

        filter.doFilter(request, new MockHttpServletResponse(), see());

        assertThat(seen.get()).isEqualTo(StandScope.copy(copy.key()));
        assertThat(session.getAttribute(StandScopeHolder.SESSION_ATTRIBUTE)).isEqualTo("copy_" + copy.key());
        assertThat(copy.lastAccess().get()).isEqualTo(clock.get());
        assertThat(StandScopeHolder.current()).isNull();
    }

    // the cleaner deleted the copy while the session of its browser lived; the browser sends its cookie or none
    @Test
    void aSessionWhoseCopyIsGoneIsOver() throws Exception {
        String deleted = CopyRegistry.newKey();

        for (Cookie[] cookies : new Cookie[][]{{new Cookie(CopyCookie.NAME, deleted)}, {}}) {
            MockHttpSession signedIn = new MockHttpSession();
            signedIn.setAttribute(StandScopeHolder.SESSION_ATTRIBUTE, "copy_" + deleted);
            request.setSession(signedIn);
            request.setCookies(cookies);
            seen.set(StandScope.template("en"));

            filter.doFilter(request, new MockHttpServletResponse(), see());

            assertThat(seen.get()).isNull();
            assertThat(signedIn.isInvalid()).isTrue();
            assertThat(request.getSession(false)).isNull();
        }
    }

    @Test
    void aForgedOrMalformedCookieBindsNothing() throws Exception {
        request.setSession(session);

        for (String key : new String[]{CopyRegistry.newKey(), "../copy"}) {
            request.setCookies(new Cookie(CopyCookie.NAME, key));
            seen.set(StandScope.template("en"));

            filter.doFilter(request, new MockHttpServletResponse(), see());

            assertThat(seen.get()).as(key).isNull();
            assertThat(session.isInvalid()).as(key).isFalse();
            assertThat(session.getAttribute(StandScopeHolder.SESSION_ATTRIBUTE)).as(key).isNull();
        }

        assertThat(registry.all()).isEmpty();
    }

    // a session keeps only a scope the stand put there, so a malformed one names no copy and ends the session
    @Test
    void aMalformedScopeInTheSessionIsNone() throws Exception {
        session.setAttribute(StandScopeHolder.SESSION_ATTRIBUTE, "copy_../templates/en");
        request.setSession(session);
        seen.set(StandScope.template("en"));

        filter.doFilter(request, new MockHttpServletResponse(), see());

        assertThat(seen.get()).isNull();
        assertThat(session.isInvalid()).isTrue();
    }

    // another tab of the browser sends the cookie it had before a login in another language replaced the copy
    @Test
    void aRequestWithAnOlderCookieLeavesTheCopyOfANewerLogin() throws Exception {
        Copy older = registry.register(CopyRegistry.newKey(), "en");
        Copy newer = registry.register(CopyRegistry.newKey(), "ru");
        session.setAttribute(StandScopeHolder.SESSION_ATTRIBUTE, "copy_" + newer.key());
        request.setSession(session);
        request.setCookies(new Cookie(CopyCookie.NAME, older.key()));

        filter.doFilter(request, new MockHttpServletResponse(), see());

        StandScope whileTheOlderLives = seen.get();
        registry.remove(older.key());
        filter.doFilter(request, new MockHttpServletResponse(), see());

        assertThat(whileTheOlderLives).isEqualTo(StandScope.copy(older.key()));
        assertThat(seen.get()).isNull();
        assertThat(session.isInvalid()).isFalse();
        assertThat(session.getAttribute(StandScopeHolder.SESSION_ATTRIBUTE)).isEqualTo("copy_" + newer.key());
    }

    // the login binds the copy it chose and replaces the session against session fixation
    @Test
    void theSessionThatReplacesTheOldOneKeepsTheCopyTheRequestEndsWith() throws Exception {
        StandScope chosen = StandScope.copy(CopyRegistry.newKey());
        request.setSession(session);

        filter.doFilter(request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> {
            StandScopeHolder.set(chosen);
            session.invalidate();
            request.getSession(true);
        });

        HttpSession renewed = request.getSession(false);

        assertThat(renewed).isNotNull();
        assertThat(renewed).isNotSameAs(session);
        assertThat(renewed.getAttribute(StandScopeHolder.SESSION_ATTRIBUTE)).isEqualTo(chosen.id());
        assertThat(StandScopeHolder.current()).isNull();
    }

    // the cleaner removes a copy only while it is idle, in one step with the check, see CopyRegistry
    @Test
    void theCopyARequestFindsIsNotRemovedUnderIt() throws Exception {
        CopyRegistry cleaned = Mockito.spy(new CopyRegistry(timeSource()));
        Copy copy = cleaned.register(CopyRegistry.newKey(), "en");
        clock.set(clock.get().plus(Duration.ofMinutes(31)));
        AtomicBoolean cleanerRan = new AtomicBoolean();

        // the cleaner runs right after the filter has looked the copy of the cookie up
        Answer<Object> thenTheCleaner = invocation -> {
            Object found = invocation.callRealMethod();

            if (cleanerRan.compareAndSet(false, true)) {
                cleaned.removeIfIdleLongerThan(copy.key(), Duration.ofMinutes(30));
            }

            return found;
        };

        Mockito.doAnswer(thenTheCleaner).when(cleaned).find(ArgumentMatchers.any());
        Mockito.doAnswer(thenTheCleaner).when(cleaned).touch(ArgumentMatchers.any());
        request.setCookies(new Cookie(CopyCookie.NAME, copy.key()));
        request.setSession(session);

        new StandScopeFilter(cleaned).doFilter(request, new MockHttpServletResponse(), see());

        assertThat(cleanerRan).isTrue();
        assertThat(seen.get()).isEqualTo(StandScope.copy(copy.key()));
        assertThat(cleaned.all()).containsExactly(copy);
    }

    private FilterChain see() {
        return (servletRequest, servletResponse) -> seen.set(StandScopeHolder.current());
    }

    private TimeSource timeSource() {
        TimeSource timeSource = Mockito.mock(TimeSource.class);
        Mockito.when(timeSource.now()).thenAnswer(invocation -> ZonedDateTime.ofInstant(clock.get(), ZoneOffset.UTC));

        return timeSource;
    }
}
