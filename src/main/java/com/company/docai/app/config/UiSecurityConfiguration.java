package com.company.docai.app.config;

import com.vaadin.flow.spring.security.RequestUtil;
import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;
import io.jmix.securityflowui.security.FlowuiVaadinWebSecurity;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer.AuthorizedUrl;

/**
 * The security of the views: Jmix's own; pictures, media, styles and fonts from this application only; pages of the
 * application for an address of no view, shown to a signed-in user, and for a session the limit of sessions per user
 * ended.
 */
@EnableWebSecurity
@Configuration
public class UiSecurityConfiguration extends FlowuiVaadinWebSecurity {

    /**
     * Where a page of the application may load pictures, media, styles and fonts from.
     */
    public static final String CONTENT_SECURITY_POLICY = "img-src 'self' data: blob:; media-src 'self' data: blob:; "
            + "style-src 'self' 'unsafe-inline'; font-src 'self' data:";

    private final RequestUtil requestUtil;

    public UiSecurityConfiguration(RequestUtil requestUtil) {
        this.requestUtil = requestUtil;
    }

    @Override
    protected void configureVaadinSpecifics(HttpSecurity http) {
        super.configureVaadinSpecifics(http);

        // Vaadin denies an address of no view before its router can answer it: a signed-in user gets PageNotFoundError
        // there, and an anonymous visitor the login; an endpoint outside Vaadin needs a filter chain of its own
        http.getConfigurer(VaadinSecurityConfigurer.class)
                .anyRequest(AuthorizedUrl::authenticated);
    }

    @Override
    protected void configureCustomSpecifics(HttpSecurity http) {
        // the AI answers in Markdown, and a resource an answer loads from another site would carry there whatever a
        // document told the model to put into its address; Vaadin styles its components inline
        http.headers(headers -> headers.contentSecurityPolicy(policy -> policy.policyDirectives(
                CONTENT_SECURITY_POLICY)));

        SessionEndedRedirect sessionEndedRedirect = new SessionEndedRedirect(requestUtil);

        http.sessionManagement(sessionManagement -> sessionManagement.sessionConcurrency(concurrency ->
                concurrency.expiredSessionStrategy(sessionEndedRedirect)));

        // Vaadin adds its entry points of the login later, as the chain is built, so a marked browser comes here first
        http.exceptionHandling(exceptionHandling -> exceptionHandling.defaultAuthenticationEntryPointFor(
                sessionEndedRedirect, SessionEndedRedirect::isMarked));
    }
}
