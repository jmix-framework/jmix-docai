package com.company.docai.stand;

import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.WrappedHttpSession;
import com.vaadin.flow.server.WrappedSession;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StandScopeHolderTest {

    private static final StandScope EN = StandScope.template("en");

    private static final StandScope RU = StandScope.template("ru");

    private static final StandScope COPY = StandScope.copy("0123456789abcdef0123456789abcdef");

    @AfterEach
    void forgetTheBrowser() {
        VaadinSession.setCurrent(null);
    }

    @Test
    void aThreadHasNoScopeByDefault() {
        assertThat(StandScopeHolder.current()).isNull();
    }

    @Test
    void callBindsTheScopeAndLeavesNoneBehind() throws Exception {
        StandScope inside = StandScopeHolder.call(EN, StandScopeHolder::current);

        assertThat(inside).isEqualTo(EN);
        assertThat(StandScopeHolder.current()).isNull();
    }

    @Test
    void callRestoresTheScopeAfterAFailure() {
        assertThatThrownBy(() -> StandScopeHolder.call(EN, () -> {
            throw new IllegalStateException("failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(StandScopeHolder.current()).isNull();
    }

    @Test
    void nestedCallsRestoreTheOuterScope() throws Exception {
        StandScopeHolder.call(EN, () -> {
            StandScope inner = StandScopeHolder.call(RU, StandScopeHolder::current);

            assertThat(inner).isEqualTo(RU);
            assertThat(StandScopeHolder.current()).isEqualTo(EN);

            return null;
        });

        assertThat(StandScopeHolder.current()).isNull();
    }

    @Test
    void runBindsAndRestoresTheScope() {
        StandScope[] seen = new StandScope[1];

        StandScopeHolder.run(EN, () -> seen[0] = StandScopeHolder.current());

        assertThat(seen[0]).isEqualTo(EN);
        assertThat(StandScopeHolder.current()).isNull();
    }

    @Test
    void runRestoresTheScopeAfterAFailure() {
        assertThatThrownBy(() -> StandScopeHolder.run(EN, () -> {
            throw new IllegalStateException("failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(StandScopeHolder.current()).isNull();
    }

    @Test
    void setBindsTheScopeUntilItIsCleared() {
        StandScopeHolder.set(EN);

        try {
            assertThat(StandScopeHolder.current()).isEqualTo(EN);
        } finally {
            StandScopeHolder.set(null);
        }

        assertThat(StandScopeHolder.current()).isNull();
    }

    // push and UI.access run the commands of a browser on threads without a scope of their own
    @Test
    void aThreadWithoutAScopeWorksInTheScopeOfTheBrowserWhoseCommandItRuns() throws Exception {
        MockHttpSession httpSession = new MockHttpSession();
        httpSession.setAttribute(StandScopeHolder.SESSION_ATTRIBUTE, COPY.id());
        VaadinSession browser = browserSession(httpSession);
        VaadinSession.setCurrent(browser);

        assertThat(StandScopeHolder.resolve()).isEqualTo(COPY);
        assertThat(StandScopeHolder.call(EN, StandScopeHolder::resolve)).isEqualTo(EN);
        // Vaadin holds its current instances weakly: the session must outlive the assertions
        Reference.reachabilityFence(browser);
    }

    @Test
    void aBrowserWithoutAScopeOrWithASessionBeingInvalidatedGivesNone() {
        MockHttpSession httpSession = new MockHttpSession();

        assertThat(StandScopeHolder.resolve()).isNull();

        VaadinSession browser = browserSession(httpSession);
        VaadinSession.setCurrent(browser);

        assertThat(StandScopeHolder.resolve()).isNull();

        httpSession.setAttribute(StandScopeHolder.SESSION_ATTRIBUTE, COPY.id());
        httpSession.invalidate();

        assertThat(StandScopeHolder.resolve()).isNull();
        // a collected session would give none as well, without reaching the invalidated one
        Reference.reachabilityFence(browser);
    }

    @Test
    void aMalformedScopeInTheSessionOfTheBrowserGivesNone() {
        MockHttpSession httpSession = new MockHttpSession();
        httpSession.setAttribute(StandScopeHolder.SESSION_ATTRIBUTE, "copy_../templates/en");
        VaadinSession browser = browserSession(httpSession);
        VaadinSession.setCurrent(browser);

        assertThat(StandScopeHolder.resolve()).isNull();
        Reference.reachabilityFence(browser);
    }

    // the check of the credentials at a login that makes a copy reads the control database, whatever the thread has
    @Test
    void theControlPartHasNoScopeNotEvenTheOneOfTheBrowser() throws Exception {
        MockHttpSession httpSession = new MockHttpSession();
        httpSession.setAttribute(StandScopeHolder.SESSION_ATTRIBUTE, COPY.id());
        VaadinSession browser = browserSession(httpSession);
        VaadinSession.setCurrent(browser);
        List<StandScope> seen = new ArrayList<>();

        StandScopeHolder.run(EN, () -> StandScopeHolder.runInControl(() -> {
            seen.add(StandScopeHolder.resolve());
            seen.add(StandScopeHolder.current());
            StandScopeHolder.run(RU, () -> seen.add(StandScopeHolder.resolve()));
        }));

        assertThat(seen).containsExactly(null, null, RU);
        assertThat(StandScopeHolder.call(EN, StandScopeHolder::resolve)).isEqualTo(EN);
        assertThat(StandScopeHolder.resolve()).isEqualTo(COPY);
        Reference.reachabilityFence(browser);
    }

    @Test
    void theControlPartEndsAlsoAfterAFailure() {
        MockHttpSession httpSession = new MockHttpSession();
        httpSession.setAttribute(StandScopeHolder.SESSION_ATTRIBUTE, COPY.id());
        VaadinSession browser = browserSession(httpSession);
        VaadinSession.setCurrent(browser);

        assertThatThrownBy(() -> StandScopeHolder.runInControl(() -> {
            throw new IllegalStateException("failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(StandScopeHolder.current()).isNull();
        assertThat(StandScopeHolder.resolve()).isEqualTo(COPY);
        Reference.reachabilityFence(browser);
    }

    private static VaadinSession browserSession(HttpSession httpSession) {
        return new VaadinSession(null) {
            @Override
            public WrappedSession getSession() {
                return new WrappedHttpSession(httpSession);
            }
        };
    }
}
