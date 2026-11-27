package com.company.docai.app.ui.icon;

import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.IconFactory;

import java.util.Locale;

/**
 * The logos of the social networks that the About view links to; Vaadin's icons have none of them.
 */
@JsModule("./src/icons/docai-social-icons.js")
public enum SocialIcons implements IconFactory {

    YOUTUBE,
    LINKEDIN,
    FACEBOOK,
    X,
    VK,
    TELEGRAM,
    MAX;

    @Override
    public Icon create() {
        return new Icon("docai-social", name().toLowerCase(Locale.ENGLISH));
    }
}
