package com.company.docai.stand;

import jakarta.servlet.http.Cookie;
import org.jspecify.annotations.Nullable;

/**
 * The cookie with the key of the visitor's copy, which outlives a logout until the browser closes.
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
