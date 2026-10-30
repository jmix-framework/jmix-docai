package com.company.docai.app.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.FilterChainProxy;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pages of the application tell the browser to load pictures, media, styles and fonts from the application only.
 */
@SpringBootTest
class UiSecurityConfigurationTest {

    @Autowired
    private FilterChainProxy securityFilters;

    @Test
    void aPageLoadsPicturesMediaStylesAndFontsFromTheApplicationOnly() throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        securityFilters.doFilter(new MockHttpServletRequest("GET", "/login"), response, new MockFilterChain());

        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo("img-src 'self' data: blob:; "
                + "media-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; font-src 'self' data:");
    }
}
