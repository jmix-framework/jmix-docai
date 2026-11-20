package com.company.docai.app.ui.component.tour;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.HasAriaLabel;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Aside;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.OrderedList;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.progressbar.ProgressBar;
import io.jmix.flowui.kit.icon.JmixFontIcon;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * Doc's panel: the header with the progress, the progress bar, his last lines, the chapters and End tour; Esc in the
 * panel folds it.
 */
public class TourChapterPanel extends Composite<Aside> implements HasAriaLabel {

    private static final int CHAPTERS = 5;

    private final Span title = createTitle();
    private final Span progressText = createProgressText();
    private final Button collapseButton = createCollapseButton();
    private final ProgressBar progressBar = createProgressBar();
    private final Div line = createLine();
    private final OrderedList chapters = createChapters();
    private final Button endButton = createEndButton();

    private Runnable collapseHandler = () -> {
    };
    private Runnable endHandler = () -> {
    };
    private IntConsumer showHandler = number -> {
    };

    @Override
    protected Aside initContent() {
        Aside panel = super.initContent();

        panel.addClassName("tour-panel");
        panel.add(createHeader(), progressBar, line, chapters, createFooter());

        panel.getElement()
                .addEventListener("keydown", keyDown -> collapseHandler.run())
                .setFilter("event.key === 'Escape'");

        return panel;
    }

    /**
     * Sets the title in the header.
     */
    public void setTitle(String title) {
        this.title.setText(title);
    }

    /**
     * Sets the name of the collapse button, for screen readers and its tooltip.
     */
    public void setCollapseLabel(String collapseLabel) {
        collapseButton.setAriaLabel(collapseLabel);
        collapseButton.setTooltipText(collapseLabel);
    }

    /**
     * Sets the text of End tour.
     */
    public void setEndText(String endText) {
        endButton.setText(endText);
    }

    /**
     * Shows how many chapters are done: the text in the header and the bar.
     */
    public void setProgress(int done, String progressText) {
        this.progressText.setText(progressText);
        progressBar.setValue(done);
    }

    /**
     * Shows Doc's last words, a line each; no lines hide their place.
     */
    public void setLines(List<String> lines) {
        line.removeAll();
        lines.forEach(text -> line.add(new Div(text)));
        line.setVisible(!lines.isEmpty());
    }

    /**
     * Shows the rows of the chapters in their order; Show of each goes to the show handler.
     */
    public void setRows(List<TourChapterRow> rows) {
        chapters.removeAll();

        for (TourChapterRow row : rows) {
            // the handler is read on the click, as it may be set after the rows
            row.setShowHandler(number -> showHandler.accept(number));
            chapters.add(row);
        }
    }

    /**
     * Sets what folding the panel does: its button and Esc in it.
     */
    public void setCollapseHandler(Runnable collapseHandler) {
        this.collapseHandler = collapseHandler;
    }

    /**
     * Sets what End tour does.
     */
    public void setEndHandler(Runnable endHandler) {
        this.endHandler = endHandler;
    }

    /**
     * Sets what Show of a chapter does, given the number of the chapter.
     */
    public void setShowHandler(IntConsumer showHandler) {
        this.showHandler = showHandler;
    }

    private Div createHeader() {
        Div heading = new Div(title, progressText);
        heading.addClassName("tour-panel-heading");

        Div header = new Div(TourPanel.createDoc(), heading, collapseButton);
        header.addClassName("tour-panel-header");

        return header;
    }

    private Div createFooter() {
        Div footer = new Div(endButton);
        footer.addClassName("tour-panel-footer");

        return footer;
    }

    private Span createTitle() {
        Span title = new Span();
        title.addClassName("tour-panel-title");

        return title;
    }

    private Span createProgressText() {
        Span progressText = new Span();
        progressText.addClassName("tour-panel-progress");

        return progressText;
    }

    private Button createCollapseButton() {
        Button collapseButton = new Button(JmixFontIcon.CHEVRON_DOWN.create());
        collapseButton.addClassName("tour-panel-collapse");
        collapseButton.addThemeVariants(ButtonVariant.TERTIARY);
        collapseButton.addClickListener(click -> collapseHandler.run());

        return collapseButton;
    }

    private ProgressBar createProgressBar() {
        ProgressBar progressBar = new ProgressBar(0, CHAPTERS);
        progressBar.addClassName("tour-progress");

        return progressBar;
    }

    private Div createLine() {
        Div line = new Div();
        line.addClassName("tour-line");
        line.setVisible(false);

        return line;
    }

    private OrderedList createChapters() {
        OrderedList chapters = new OrderedList();
        chapters.addClassName("tour-chapters");

        return chapters;
    }

    private Button createEndButton() {
        Button endButton = new Button();
        endButton.addClassName("tour-panel-end");
        endButton.addThemeVariants(ButtonVariant.TERTIARY);
        endButton.addClickListener(click -> endHandler.run());

        return endButton;
    }
}
