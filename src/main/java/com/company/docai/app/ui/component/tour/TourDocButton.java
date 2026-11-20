package com.company.docai.app.ui.component.tour;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.Focusable;
import com.vaadin.flow.component.HasAriaLabel;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;

/**
 * Doc in the corner: his picture with the progress badge, a button that opens his panel.
 */
public class TourDocButton extends Composite<NativeButton> implements Focusable<TourDocButton>, HasAriaLabel {

    private final Span badge = createBadge();

    private Runnable clickHandler = () -> {
    };

    @Override
    protected NativeButton initContent() {
        NativeButton button = super.initContent();

        button.addClassName("tour-doc");
        button.add(TourPanel.createDoc(), badge);
        button.getElement().setAttribute("aria-expanded", "false");
        button.addClickListener(click -> clickHandler.run());

        return button;
    }

    /**
     * Shows the progress on the badge.
     */
    public void setProgressText(String progressText) {
        badge.setText(progressText);
    }

    /**
     * Tells screen readers whether the panel that Doc opens is open.
     */
    public void setExpanded(boolean expanded) {
        getElement().setAttribute("aria-expanded", String.valueOf(expanded));
    }

    /**
     * Sets what a click on Doc does.
     */
    public void setClickHandler(Runnable clickHandler) {
        this.clickHandler = clickHandler;
    }

    private Span createBadge() {
        Span badge = new Span();
        badge.addClassName("tour-doc-badge");

        return badge;
    }
}
