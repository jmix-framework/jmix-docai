package com.company.docai.view.status;

import com.company.docai.model.document.DocumentStatus;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.html.Span;
import io.jmix.flowui.UiComponents;
import org.springframework.stereotype.Component;

/**
 * Shows a status in its color: as a badge in the card, the document list, the search results and the dictionary,
 * and as a color dot with the name in the lists and menus that offer statuses.
 */
@Component
public class StatusBadges {

    private final UiComponents uiComponents;

    public StatusBadges(UiComponents uiComponents) {
        this.uiComponents = uiComponents;
    }

    /**
     * Tints the badge with the color of the status. Aura derives the text and the surface of a badge from the two
     * accent properties, so any color reads in the light and in the dark scheme, as the palette classes do; it keeps
     * the hue and the chroma only, so the border takes the color itself and black, white and grays stay apart.
     */
    public static void paint(Badge badge, DocumentStatus status) {
        badge.getStyle()
                .set("--aura-accent-color-light", status.getColor())
                .set("--aura-accent-color-dark", status.getColor())
                .set("--vaadin-badge-border-color", status.getColor());
    }

    /**
     * Returns a badge with the name of the status in its color.
     */
    public Badge create(DocumentStatus status) {
        Badge badge = uiComponents.create(Badge.class);
        badge.setText(status.getName());
        paint(badge, status);
        return badge;
    }

    /**
     * Returns a color dot and the name of the status, for an item of a list or a menu.
     */
    public Span option(DocumentStatus status) {
        Span dot = uiComponents.create(Span.class);
        dot.addClassName("status-dot");
        dot.getStyle().set("background-color", status.getColor());

        Span name = uiComponents.create(Span.class);
        name.setText(status.getName());

        Span option = uiComponents.create(Span.class);
        option.addClassName("status-option");
        option.add(dot, name);
        return option;
    }
}
