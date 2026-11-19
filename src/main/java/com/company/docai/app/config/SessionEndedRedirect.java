package com.company.docai.app.config;

import com.company.docai.view.sessionended.SessionEndedView;
import com.vaadin.flow.spring.security.RequestUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.session.SessionInformationExpiredEvent;
import org.springframework.security.web.session.SessionInformationExpiredStrategy;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.time.Duration;

/**
 * Takes a browser whose session the limit of sessions per user ended to {@link SessionEndedView}: a page request by a
 * redirect, a request of a page already open by the address its Vaadin client goes to. Such a request also marks the
 * browser for two minutes, since the client of a heartbeat reads no answer and the other tabs learn of the end only as
 * they load a view signed out: each of them then shows the page instead of the login. A login clears the mark.
 */
public class SessionEndedRedirect implements SessionInformationExpiredStrategy, AuthenticationEntryPoint {

    private static final String MARK = "DOCAI_SESSION_ENDED";
    private static final Duration MARK_AGE = Duration.ofMinutes(2);

    private final RequestUtil requestUtil;

    public SessionEndedRedirect(RequestUtil requestUtil) {
        this.requestUtil = requestUtil;
    }

    @Override
    public void onExpiredSessionDetected(SessionInformationExpiredEvent event) throws IOException {
        HttpServletRequest request = event.getRequest();
        HttpServletResponse response = event.getResponse();

        response.addCookie(mark(request, MARK_AGE));

        if (!requestUtil.isFrameworkInternalRequest(request)) {
            response.sendRedirect(page(request));
            return;
        }

        // the client of an open page follows no redirect; it loads the address after this token of Vaadin
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("Vaadin-Refresh: " + page(request));
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.sendRedirect(page(request));
    }

    /**
     * Whether the browser of the request carries the mark of an ended session.
     */
    public static boolean isMarked(HttpServletRequest request) {
        return WebUtils.getCookie(request, MARK) != null;
    }

    /**
     * Clears the mark of an ended session from the browser of the request, as a login does, so that a later logout
     * leads to the login.
     */
    public static void unmark(HttpServletRequest request, HttpServletResponse response) {
        if (isMarked(request)) {
            response.addCookie(mark(request, Duration.ZERO));
        }
    }

    private static String page(HttpServletRequest request) {
        return request.getContextPath() + "/" + SessionEndedView.ROUTE;
    }

    private static Cookie mark(HttpServletRequest request, Duration age) {
        Cookie cookie = new Cookie(MARK, "1");
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        cookie.setPath(request.getContextPath().isEmpty() ? "/" : request.getContextPath());
        cookie.setMaxAge((int) age.toSeconds());
        cookie.setAttribute("SameSite", "Lax");

        return cookie;
    }
}
