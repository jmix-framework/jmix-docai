package com.company.docai.stand;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CopyCookieTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";

    @Test
    void theCookieKeepsTheKeyForTheBrowserSessionAwayFromScriptsAndOtherSites() {
        Cookie cookie = CopyCookie.of(KEY, true, "/docai");

        assertThat(cookie.getName()).isEqualTo("DOCAI_COPY");
        assertThat(cookie.getValue()).isEqualTo(KEY);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/docai");
        assertThat(cookie.getMaxAge()).isEqualTo(-1);
    }

    @Test
    void overPlainHttpAtTheRootTheCookieIsNotSecureAndCoversEveryPath() {
        Cookie cookie = CopyCookie.of(KEY, false, "");

        assertThat(cookie.getSecure()).isFalse();
        assertThat(cookie.getPath()).isEqualTo("/");
    }

    @Test
    void theKeyIsReadFromTheCookieOfTheCopyOnly() {
        Cookie session = new Cookie("JSESSIONID", "session");

        assertThat(CopyCookie.keyOf(null)).isNull();
        assertThat(CopyCookie.keyOf(new Cookie[]{session})).isNull();
        assertThat(CopyCookie.keyOf(new Cookie[]{session, new Cookie(CopyCookie.NAME, KEY)})).isEqualTo(KEY);
    }
}
