package com.company.docai.app.ui.component.tour;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasStyle;
import com.vaadin.flow.component.Svg;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.popover.Popover;
import io.jmix.core.Messages;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Doc on the page: the corner button with the progress, his bubble and the panel with the chapters. Not a Spring
 * bean, as the component factory would give every tab the same one.
 */
@Tag("docai-tour")
@JsModule("./src/component/tour/docai-tour.js")
public class TourPanel extends Component implements HasStyle, ApplicationContextAware, InitializingBean {

    private static final String DOC_RESOURCE = "/META-INF/resources/themes/jmix-docai-aura/images/doc.svg";
    private static final String DOC_SVG = readDoc();

    private ApplicationContext applicationContext;
    private Messages messages;

    private Span liveRegion;
    private TourDocButton docButton;
    private TourBubble bubble;
    private TourChapterPanel chapterPanel;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterPropertiesSet() {
        autowireDependencies();
        initComponent();
    }

    private void autowireDependencies() {
        messages = applicationContext.getBean(Messages.class);
    }

    private void initComponent() {
        addClassNames("tour", "aura-accent-color");
        getElement().setAttribute("dock", Dock.RIGHT.value);
        getElement().setAttribute("mood", Mood.CALM.value);

        liveRegion = createLiveRegion();
        docButton = new TourDocButton();
        bubble = createBubble();
        chapterPanel = createChapterPanel();

        // Tab goes from Doc on into what he opens
        getElement().appendChild(liveRegion.getElement(),
                docButton.getElement(),
                bubble.getElement(),
                chapterPanel.getElement());

        setProgress(0);
    }

    private Span createLiveRegion() {
        Span liveRegion = new Span();
        liveRegion.addClassName("tour-live");
        liveRegion.getElement().setAttribute("role", "status");

        return liveRegion;
    }

    private TourBubble createBubble() {
        TourBubble bubble = new TourBubble();
        bubble.setCloseAriaLabel(messages.getMessage(getClass(), "closeButton.ariaLabel"));

        return bubble;
    }

    private TourChapterPanel createChapterPanel() {
        TourChapterPanel chapterPanel = new TourChapterPanel();
        chapterPanel.setTitle(messages.getMessage(getClass(), "panel.title"));
        chapterPanel.setAriaLabel(messages.getMessage(getClass(), "panel.ariaLabel"));
        chapterPanel.setCollapseLabel(messages.getMessage(getClass(), "collapseButton.ariaLabel"));
        chapterPanel.setEndText(messages.getMessage(getClass(), "panel.endButton"));

        return chapterPanel;
    }

    private TourChapterRow createChapterRow(Row row) {
        TourChapterRow chapterRow = new TourChapterRow(row);
        chapterRow.setDoneText(messages.getMessage(getClass(), "chapter.doneLabel"));
        chapterRow.setShowText(messages.getMessage(getClass(), "chapter.showButton"));
        chapterRow.setShowAriaLabel(messages.formatMessage(getClass(), "showButton.ariaLabel", row.title()));

        return chapterRow;
    }

    /**
     * Creates the picture of Doc, a square of the logo with eyes, for the panel, the bubble or a hint.
     */
    public static Svg createDoc() {
        Svg picture = new Svg(DOC_SVG);
        picture.getElement().getClassList().add("tour-doc-image");

        return picture;
    }

    /**
     * Gives the browser the path of the application and whether it is reached over HTTPS, for the cookie of the tour.
     */
    public void setCookieTarget(String path, boolean secure) {
        getElement().setAttribute("cookie-path", path.isEmpty() ? "/" : path);
        getElement().setAttribute("cookie-secure", secure);
    }

    /**
     * Writes the whole progress into the browser cookie.
     */
    public void writeState(String value) {
        getElement().callJsFunction("writeState", value);
    }

    /**
     * Plays a one-time motion of Doc after the delay, in milliseconds; a later call replaces a motion still waiting.
     */
    public void play(Motion motion, int delay) {
        getElement().callJsFunction("play", motion.value, delay);
    }

    /**
     * Shows the hint in the window only once the window stands, as a popover placed while it grows would jump.
     */
    public void revealHint(Popover hint, Dialog window) {
        getElement().callJsFunction("revealHint", hint.getElement(), window.getElement());
    }

    /**
     * Tells Doc where to stand while his corner would cover a control.
     */
    public void setDock(Dock dock) {
        getElement().setAttribute("dock", dock.value);
    }

    /**
     * Sets how Doc looks while he is folded.
     */
    public void setMood(Mood mood) {
        getElement().setAttribute("mood", mood.value);
    }

    /**
     * Shows or hides Doc with all he has.
     */
    public void setShown(boolean shown) {
        // not setVisible: an invisible component gets no calls in the browser
        getElement().setAttribute("hidden", !shown);
    }

    /**
     * Returns whether Doc is on the page.
     */
    public boolean isShown() {
        return !getElement().hasAttribute("hidden");
    }

    /**
     * Opens or folds the panel with the chapters; a folded panel gives the focus back to Doc.
     */
    public void setExpanded(boolean expanded) {
        boolean folding = isExpanded() && !expanded;

        getElement().setAttribute("expanded", expanded);
        docButton.setExpanded(expanded);

        if (expanded) {
            bubble.hide();
        }

        if (folding) {
            docButton.focus();
        }
    }

    /**
     * Returns whether the panel with the chapters is open.
     */
    public boolean isExpanded() {
        return getElement().hasAttribute("expanded");
    }

    /**
     * Shows how many chapters are done.
     */
    public void setProgress(int done) {
        docButton.setProgressText(messages.formatMessage(getClass(), "doc.progress", done));
        docButton.setAriaLabel(messages.formatMessage(getClass(), "doc.ariaLabel", done));
        chapterPanel.setProgress(done, messages.formatMessage(getClass(), "panel.progress", done));
    }

    /**
     * Shows the chapters; an unavailable one stays in its place, grey.
     */
    public void setRows(List<Row> rows) {
        chapterPanel.setRows(rows.stream()
                .map(this::createChapterRow)
                .toList());
    }

    /**
     * Shows Doc's last words in the panel, a line each.
     */
    public void setLine(List<String> lines) {
        chapterPanel.setLines(lines);
    }

    /**
     * Shows Doc's bubble with the text and its buttons.
     */
    public void showBubble(String text, List<Choice> choices, boolean closable) {
        bubble.show(text, choices, closable);
    }

    /**
     * Hides the bubble of Doc.
     */
    public void hideBubble() {
        bubble.hide();
    }

    /**
     * Gives screen readers what Doc says.
     */
    public void announce(String text) {
        liveRegion.setText(text);
    }

    /**
     * Sets what a click on folded Doc does.
     */
    public void setDocClickHandler(Runnable docClickHandler) {
        docButton.setClickHandler(docClickHandler);
    }

    /**
     * Sets what folding the panel does: its button and Esc in it.
     */
    public void setCollapseHandler(Runnable collapseHandler) {
        chapterPanel.setCollapseHandler(collapseHandler);
    }

    /**
     * Sets what End tour in the panel does.
     */
    public void setEndHandler(Runnable endHandler) {
        chapterPanel.setEndHandler(endHandler);
    }

    /**
     * Sets what the close button of the bubble does.
     */
    public void setBubbleCloseHandler(Runnable bubbleCloseHandler) {
        bubble.setCloseHandler(bubbleCloseHandler);
    }

    /**
     * Sets what Show of a chapter does, given the number of the chapter.
     */
    public void setShowHandler(IntConsumer showHandler) {
        chapterPanel.setShowHandler(showHandler);
    }

    private static String readDoc() {
        try (InputStream stream = TourPanel.class.getResourceAsStream(DOC_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("No picture of Doc at " + DOC_RESOURCE);
            }

            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("The picture of Doc was not read", e);
        }
    }

    /**
     * Where Doc goes when his corner is taken.
     */
    public enum Dock {

        RIGHT("right"),
        RIGHT_RAISED("right-raised"),
        LEFT("left");

        private final String value;

        Dock(String value) {
            this.value = value;
        }
    }

    /**
     * How folded Doc looks between his motions.
     */
    public enum Mood {

        CALM("calm"),
        POINTING("pointing"),
        LISTENING("listening");

        private final String value;

        Mood(String value) {
            this.value = value;
        }
    }

    /**
     * A one-time motion of Doc.
     */
    public enum Motion {

        ROLL_IN("roll-in"),
        GLAD("glad"),
        LEAVE("leave");

        private final String value;

        Motion(String value) {
            this.value = value;
        }
    }

    /**
     * The state of a chapter in the panel.
     */
    public enum RowState {

        DONE("done"),
        NEXT("next"),
        OPEN("open"),
        UNAVAILABLE("unavailable");

        private final String value;

        RowState(String value) {
            this.value = value;
        }

        /**
         * Returns the value of the state attribute of the chapter's row.
         */
        public String getValue() {
            return value;
        }
    }

    /**
     * A chapter in the panel; Show gives its number back.
     */
    public record Row(int number, String title, RowState state, @Nullable String reason) {
    }

    /**
     * A button of the bubble.
     */
    public record Choice(String text, boolean primary, Runnable action) {
    }
}
