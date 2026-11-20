package com.company.docai.app.ui.component.tour;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Span;

import java.util.function.IntConsumer;

/**
 * A chapter in Doc's panel: its mark, its title with the done label or the reason it is unavailable, and Show.
 */
public class TourChapterRow extends Composite<ListItem> {

    private final TourPanel.Row row;
    private final Span doneLabel;
    private final Button showButton;

    private IntConsumer showHandler = number -> {
    };

    /**
     * Creates the row of the chapter; Show is disabled while the chapter is unavailable.
     */
    public TourChapterRow(TourPanel.Row row) {
        this.row = row;
        doneLabel = createDoneLabel();
        showButton = createShowButton();
    }

    @Override
    protected ListItem initContent() {
        ListItem item = super.initContent();

        item.addClassName("tour-row");
        item.getElement().setAttribute("state", row.state().getValue());
        item.add(createMark(), createText(), showButton);

        return item;
    }

    /**
     * Sets the label that a done chapter shows beside its title.
     */
    public void setDoneText(String doneText) {
        doneLabel.setText(doneText);
    }

    /**
     * Sets the text of Show.
     */
    public void setShowText(String showText) {
        showButton.setText(showText);
    }

    /**
     * Sets the name of Show for screen readers.
     */
    public void setShowAriaLabel(String showAriaLabel) {
        showButton.setAriaLabel(showAriaLabel);
    }

    /**
     * Sets what Show does, given the number of the chapter.
     */
    public void setShowHandler(IntConsumer showHandler) {
        this.showHandler = showHandler;
    }

    private Span createMark() {
        Span mark = new Span();
        mark.addClassName("tour-row-mark");
        mark.getElement().setAttribute("aria-hidden", "true");

        return mark;
    }

    private Div createText() {
        Span title = new Span(row.title());
        title.addClassName("tour-row-title");

        Div text = new Div(title);
        text.addClassName("tour-row-text");

        if (row.state() == TourPanel.RowState.DONE) {
            text.add(doneLabel);
        } else if (row.state() == TourPanel.RowState.UNAVAILABLE && row.reason() != null) {
            text.add(createReason());
        }

        return text;
    }

    private Span createDoneLabel() {
        Span doneLabel = new Span();
        doneLabel.addClassName("tour-row-done");

        return doneLabel;
    }

    private Span createReason() {
        Span reason = new Span(row.reason());
        reason.addClassName("tour-row-reason");

        return reason;
    }

    private Button createShowButton() {
        Button showButton = new Button();
        showButton.addClassName("tour-row-show");
        showButton.setEnabled(row.state() != TourPanel.RowState.UNAVAILABLE);
        showButton.addClickListener(click -> showHandler.accept(row.number()));

        showButton.addThemeVariants(row.state() == TourPanel.RowState.NEXT
                ? ButtonVariant.PRIMARY
                : ButtonVariant.TERTIARY);

        return showButton;
    }
}
