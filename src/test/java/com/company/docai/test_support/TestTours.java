package com.company.docai.test_support;

import com.company.docai.app.ui.component.tour.TourPanel;
import com.company.docai.view.tour.Tour;
import com.company.docai.view.tour.TourGuide;
import com.company.docai.view.tour.TourState;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dnd.DragEndEvent;
import com.vaadin.flow.component.dnd.DragStartEvent;
import com.vaadin.flow.component.dnd.DropEvent;
import com.vaadin.flow.component.internal.PendingJavaScriptInvocation;
import com.vaadin.flow.component.internal.UIInternals.JavaScriptInvocation;
import com.vaadin.flow.component.popover.Popover;
import com.vaadin.flow.dom.DomEvent;
import com.vaadin.flow.internal.nodefeature.ElementListenerMap;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.VaadinSession;
import io.jmix.core.Messages;
import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.List;
import java.util.stream.Stream;

/**
 * The tour as the UI tests see it.
 */
public final class TestTours {

    private TestTours() {
    }

    /**
     * The panel of the current tab: the one the main view put on the page last.
     */
    public static TourPanel panel() {
        return UI.getCurrent()
                .getChildren()
                .filter(TourPanel.class::isInstance)
                .map(TourPanel.class::cast)
                .reduce((earlier, later) -> later)
                .orElseThrow();
    }

    /**
     * The guide of the current tab.
     */
    public static TourGuide guide() {
        return Tour.guide()
                .orElseThrow();
    }

    /**
     * The progress of the tour in the session.
     */
    public static TourState state() {
        return Tour.state(VaadinSession.getCurrent());
    }

    /**
     * Starts the tour as a person does; the panel it returns is open with the chapters.
     */
    public static TourPanel startTour(Messages messages) {
        guide().open(true);
        TourPanel panel = panel();
        button(panel, message(messages, "startButton")).click();
        button(panel, message(messages, "nextButton")).click();

        return panel;
    }

    /**
     * The text of the guide with the key.
     */
    public static String message(Messages messages, String key) {
        return messages.getMessage(TourGuide.class, key);
    }

    /**
     * The components under the root with the class name, in the order of the page.
     */
    public static List<Component> withClass(Component root, String className) {
        return descendants(root)
                .filter(component -> component.getElement().getClassList().contains(className))
                .toList();
    }

    /**
     * The text of the first component under the root with the class name, its children's included.
     */
    public static String text(Component root, String className) {
        return withClass(root, className).getFirst()
                .getElement()
                .getTextRecursively();
    }

    /**
     * The texts of the buttons under the first component with the class name.
     */
    public static List<String> buttonTexts(Component root, String className) {
        return descendants(withClass(root, className).getFirst())
                .filter(Button.class::isInstance)
                .map(button -> ((Button) button).getText())
                .toList();
    }

    /**
     * The button under the root with the text.
     */
    public static Button button(Component root, String text) {
        return descendants(root)
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No button '%s'".formatted(text)));
    }

    /**
     * The states of the chapters in the panel, in the order of the story.
     */
    public static List<String> rowStates(TourPanel panel) {
        return withClass(panel, "tour-row").stream()
                .map(row -> row.getElement().getAttribute("state"))
                .toList();
    }

    /**
     * Show of the chapter with the number.
     */
    public static Button showButton(TourPanel panel, int number) {
        return withClass(withClass(panel, "tour-row").get(number - 1), "tour-row-show").stream()
                .map(Button.class::cast)
                .findFirst()
                .orElseThrow();
    }

    /**
     * The hint of the tour open now, wherever it put itself: on the page or in a dialog.
     */
    public static Popover openHint() {
        return descendants(UI.getCurrent())
                .filter(Popover.class::isInstance)
                .map(Popover.class::cast)
                .filter(Popover::isOpened)
                .reduce((earlier, later) -> later)
                .orElseThrow(() -> new IllegalStateException("No hint of the tour is open"));
    }

    /**
     * The card of the sample invoice in the hint of the upload.
     */
    public static Component sample(Popover hint) {
        return withClass(hint, "tour-sample").getFirst();
    }

    /**
     * The start of a drag of the component, as the browser reports it.
     */
    public static void startDrag(Component source) {
        ComponentUtil.fireEvent(source, new DragStartEvent<>(source, true, 0, 0, 0, 0));
    }

    /**
     * A drop of what the page drags on the target, as the browser reports it; the drop comes before the end of the
     * drag.
     */
    public static void drop(Component target) {
        ComponentUtil.fireEvent(target, new DropEvent<>(target, true, "copy", 0, 0, 0, 0));
    }

    /**
     * The end of a drag of the component, as the browser reports it.
     */
    public static void endDrag(Component source) {
        ComponentUtil.fireEvent(source, new DragEndEvent<>(source, true, "copy", 0, 0));
    }

    /**
     * A browser event on the component as its server listeners get it, through the internal
     * {@code ElementListenerMap}.
     */
    public static void browserEvent(Component component, String type) {
        component.getElement()
                .getNode()
                .getFeature(ElementListenerMap.class)
                .fireEvent(new DomEvent(component.getElement(), type, JsonNodeFactory.instance.objectNode()));
    }

    /**
     * The values the tour wrote into its cookie since the last call; the browser's other pending work is forgotten too.
     */
    public static List<String> writtenStates() {
        return firstArguments("writeState");
    }

    /**
     * The motions Doc played since the last call; the browser's other pending work is forgotten too.
     */
    public static List<String> playedMotions() {
        return firstArguments("play");
    }

    /**
     * The scripts sent for the component since the last call; the browser's other pending work is forgotten too.
     */
    public static List<JavaScriptInvocation> scripts(Component owner) {
        UI.getCurrent()
                .getInternals()
                .getStateTree()
                .runExecutionsBeforeClientResponse();

        return UI.getCurrent()
                .getInternals()
                .dumpPendingJavaScriptInvocations()
                .stream()
                .filter(pending -> pending.getOwner() == owner.getElement().getNode())
                .map(PendingJavaScriptInvocation::getInvocation)
                .toList();
    }

    /**
     * Runs the action as one browser request: the work it leaves for the response comes after it, as in a browser.
     */
    public static void inOneRequest(Runnable action) {
        UI.getCurrent()
                .beforeClientResponse(UI.getCurrent(), context -> action.run());
    }

    /**
     * Puts the tour cookie into the browser's requests and forgets the session's progress, as a new sign-in does.
     */
    public static void browserCookie(String value) {
        ((MockHttpServletRequest) VaadinServletRequest.getCurrent().getRequest())
                .setCookies(new Cookie(TourState.COOKIE_NAME, value));
        VaadinSession.getCurrent().setAttribute(TourState.class, null);
    }

    private static Stream<Component> descendants(Component root) {
        // a composite shares its element with its content, so the content alone stands for both
        return root.getChildren()
                .flatMap(child -> child instanceof Composite<?>
                        ? descendants(child)
                        : Stream.concat(Stream.of(child), descendants(child)));
    }

    private static List<String> firstArguments(String function) {
        UI.getCurrent()
                .getInternals()
                .getStateTree()
                .runExecutionsBeforeClientResponse();

        return UI.getCurrent()
                .getInternals()
                .dumpPendingJavaScriptInvocations()
                .stream()
                .map(PendingJavaScriptInvocation::getInvocation)
                .filter(invocation -> invocation.getExpression().contains("." + function + "("))
                .map(invocation -> (String) invocation.getParameters().get(1))
                .toList();
    }
}
