package com.company.docai.view.tour;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasAriaLabel;
import com.vaadin.flow.component.dnd.DragSource;
import com.vaadin.flow.component.dnd.EffectAllowed;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

/**
 * The sample invoice in the hint of the upload: a card with the file name to drag into the table of the folder.
 */
public class TourSampleCard extends Div implements DragSource<TourSampleCard>, HasAriaLabel {

    private final Span nameLabel = new Span();

    private Runnable dragStartHandler = () -> {
    };
    private Runnable dragEndHandler = () -> {
    };

    /**
     * Creates the card with the icon of its file.
     */
    public TourSampleCard(Component icon) {
        addClassName("tour-sample");
        add(icon, nameLabel);
        getElement().setAttribute("role", "img");

        // the button beside the card serves the keyboard, the phone and the screen reader, so the card takes no focus
        setDraggable(true);
        setEffectAllowed(EffectAllowed.COPY);
        addDragStartListener(start -> dragStartHandler.run());
        addDragEndListener(end -> dragEndHandler.run());
    }

    /**
     * Sets the file name the sample gets in the folder.
     */
    public void setName(String name) {
        nameLabel.setText(name);
    }

    /**
     * Sets what the start of a drag of the card does.
     */
    public void setDragStartHandler(Runnable dragStartHandler) {
        this.dragStartHandler = dragStartHandler;
    }

    /**
     * Sets what the end of a drag of the card does, a drop included.
     */
    public void setDragEndHandler(Runnable dragEndHandler) {
        this.dragEndHandler = dragEndHandler;
    }
}
