package com.company.docai.stand;

import jakarta.servlet.http.Cookie;
import org.jspecify.annotations.Nullable;

/**
 * The cookie that keeps the key of the visitor's copy in the browser until the browser closes. It is not tied to the
 * HTTP session, so a logout and the next login keep the copy.
 */
public final class CopyCookie {

    /**
     * The name of the cookie.
     */
    public static final String NAME = "DOCAI_COPY";

    private CopyCookie() {
    }

    /**
     * The key in the cookie of a request, or null when the request has none.
     */
    public static @Nullable String keyOf(Cookie @Nullable [] cookies) {
        if (cookies == null) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }

        return null;
    }

    /**
     * Makes the cookie with the key of a copy: HttpOnly, SameSite=Lax, Secure over HTTPS, without an expiry.
     *
     * @param secure      whether the request came over HTTPS
     * @param contextPath the path of the application, whose requests carry the cookie
     */
    public static Cookie of(String key, boolean secure, String contextPath) {
        Cookie cookie = new Cookie(NAME, key);
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        cookie.setPath(contextPath.isEmpty() ? "/" : contextPath);
        cookie.setAttribute("SameSite", "Lax");

        return cookie;
    }
}
