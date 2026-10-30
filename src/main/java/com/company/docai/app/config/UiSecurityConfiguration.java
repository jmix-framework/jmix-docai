package com.company.docai.app.config;

import io.jmix.securityflowui.security.FlowuiVaadinWebSecurity;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

/**
 * The security of the views of the application: the one Jmix sets up, and a browser that loads pictures, media,
 * styles and fonts from this application only.
 */
@EnableWebSecurity
@Configuration
public class UiSecurityConfiguration extends FlowuiVaadinWebSecurity {

    /**
     * Where a page of the application may load pictures, media, styles and fonts from.
     */
    public static final String CONTENT_SECURITY_POLICY = "img-src 'self' data: blob:; media-src 'self' data: blob:; "
            + "style-src 'self' 'unsafe-inline'; font-src 'self' data:";

    // the AI answers in Markdown, and a picture, a sound, a video, a style or a font that an answer loads from
    // another site would carry to that site whatever the text of a document told the model to put into its
    // address; Vaadin styles its components inline
    @Override
    protected void configureCustomSpecifics(HttpSecurity http) {
        http.headers(headers -> headers.contentSecurityPolicy(policy -> policy.policyDirectives(
                CONTENT_SECURITY_POLICY)));
    }
}
