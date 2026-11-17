package com.company.docai.stand;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Binds the visitor's copy to every request of the browser, before the security of the application reads any user:
 * the cookie names the copy, the copy counts as used, the thread of the request works in its scope, and the HTTP
 * session keeps the scope for the commands Vaadin runs outside the requests. A request with no cookie, a forged one or
 * the one of a deleted copy binds no scope; when its session still keeps a deleted copy, the session is over, so the
 * visitor gets back to the login form instead of a session that reaches no data.
 */
@Component
@Profile("stand")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StandScopeFilter extends OncePerRequestFilter {

    private final CopyRegistry registry;

    public StandScopeFilter(CopyRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // found and marked as used in one step, so the cleaner does not take the copy from under the request
        StandScope scope = registry.touch(CopyCookie.keyOf(request.getCookies()))
                .map(copy -> StandScope.copy(copy.key()))
                .orElse(null);

        keepInSession(request.getSession(false), scope);

        StandScope previous = StandScopeHolder.current();
        StandScopeHolder.set(scope);

        try {
            chain.doFilter(request, response);
        } finally {
            StandScope last = StandScopeHolder.current();
            StandScopeHolder.set(previous);

            // only a login changes the scope of its request, and it replaces the session against session fixation;
            // another request writes nothing back, so one that started with an older cookie cannot undo a newer login
            if (last != null && !last.equals(scope)) {
                remember(request.getSession(false), last);
            }
        }
    }

    // a session takes the cookie's copy only when it has none, since the copy it has may come from a login newer than
    // the cookie of this request
    private void keepInSession(@Nullable HttpSession session, @Nullable StandScope scope) {
        if (session == null) {
            return;
        }

        try {
            Object kept = session.getAttribute(StandScopeHolder.SESSION_ATTRIBUTE);

            if (kept == null && scope != null) {
                session.setAttribute(StandScopeHolder.SESSION_ATTRIBUTE, scope.id());
            } else if (kept != null && scope == null && isGone(kept, session)) {
                session.invalidate();
            }
        } catch (IllegalStateException e) {
            // invalidated meanwhile, as by a logout in another tab
        }
    }

    // a login in another language puts its new copy into the session before it deletes the old one, so the session is
    // read once more after the copy is found gone: a login under way never counts as a gone copy
    private boolean isGone(Object kept, HttpSession session) {
        StandScope scope = kept instanceof String id ? StandScope.fromId(id) : null;

        boolean live = scope != null
                && scope.isCopy()
                && registry.find(scope.key()).isPresent();

        return !live && kept.equals(session.getAttribute(StandScopeHolder.SESSION_ATTRIBUTE));
    }

    private static void remember(@Nullable HttpSession session, StandScope scope) {
        if (session == null) {
            return;
        }

        try {
            session.setAttribute(StandScopeHolder.SESSION_ATTRIBUTE, scope.id());
        } catch (IllegalStateException e) {
            // invalidated by this request, as by a logout
        }
    }
}
