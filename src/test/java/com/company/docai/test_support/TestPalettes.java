package com.company.docai.test_support;

import com.company.docai.app.ui.component.searchpalette.SearchPaletteLine;
import com.company.docai.app.ui.component.searchpalette.SearchPaletteOpener;
import com.company.docai.view.search.SearchPaletteView;
import com.google.common.base.Strings;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.internal.PendingJavaScriptInvocation;
import com.vaadin.flow.component.internal.UIInternals.JavaScriptInvocation;
import com.vaadin.flow.dom.DomEvent;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.internal.nodefeature.ElementListenerMap;
import com.vaadin.flow.router.QueryParameters;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import io.jmix.flowui.testassist.UiTestUtils;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Opens the search palette from the header and types, presses and clicks in it as a browser would.
 */
public final class TestPalettes {

    private static final List<String> CHIPS = List.of("allChip", "documentsChip", "foldersChip", "tagsChip");

    // the browser numbers the openings of a page one after another
    private static final AtomicInteger OPENINGS = new AtomicInteger();

    private TestPalettes() {
    }

    public static TypedTextField<String> searchField() {
        return UiTestUtils.getComponent(TestMenus.mainView(), "searchField");
    }

    /**
     * Asks the header to open the palette, as the browser does after a click on the search field, a shortcut or a key
     * typed there, with the text of the field, and returns the palette that is open then.
     */
    public static SearchPaletteView open() {
        return open(Strings.nullToEmpty(searchField().getValue()));
    }

    /**
     * Asks the header to open the palette with the text the browser shows in the search field, which the server may
     * not have, and returns the palette that is open then.
     */
    public static SearchPaletteView open(String text) {
        return open(text, OPENINGS.incrementAndGet());
    }

    /**
     * Asks the header to open the palette with the text the browser shows in the search field, as the opening that
     * the browser numbered so, and returns the palette that is open then.
     */
    public static SearchPaletteView open(String text, int opening) {
        SearchPaletteOpener opener = opener();
        ComponentUtil.fireEvent(opener, new SearchPaletteOpener.OpenEvent(opener, true, text, opening));

        return UiTestUtils.getLastOpenedViewDialog();
    }

    /**
     * Cancels the opening of the palette as the browser does after an Esc typed before the palette appears. The
     * event comes from the header, which the open palette has made inert, so it goes through the check of Flow that
     * drops the events of inert elements.
     */
    public static void cancel() {
        Element opener = opener().getElement();

        // the browser sends the cancel after the response that opened the palette, which is when Flow makes the rest
        // of the page inert
        UI.getCurrent()
                .getInternals()
                .getStateTree()
                .collectChanges(change -> {
                });

        // no public API fires a browser event in a server-side test, so the event goes through Flow's listener map
        opener.getNode()
                .getFeature(ElementListenerMap.class)
                .fireEvent(new DomEvent(opener, "docai-search-palette-cancel", JsonNodeFactory.instance.objectNode()));
    }

    /**
     * Asks the header to open the palette as the browser does after a click right after the Esc of {@link #cancel()}:
     * the click comes in the same request, while the page is still inert, so it goes through the same check of Flow.
     */
    public static void openAfterCancel(String text) {
        Element opener = opener().getElement();

        ObjectNode detail = JsonNodeFactory.instance.objectNode()
                .put("event.detail.text", text)
                .put("event.detail.opening", OPENINGS.incrementAndGet());

        opener.getNode()
                .getFeature(ElementListenerMap.class)
                .fireEvent(new DomEvent(opener, "docai-search-palette-open", detail));
    }

    public static boolean isOpen() {
        return UiTestUtils.getOpenedViewDialogs()
                .stream()
                .anyMatch(SearchPaletteView.class::isInstance);
    }

    public static TypedTextField<String> queryField(SearchPaletteView palette) {
        return UiTestUtils.getComponent(palette, "queryField");
    }

    public static SearchPaletteLine line(SearchPaletteView palette) {
        return UiTestUtils.getComponent(palette, "line");
    }

    /**
     * Types the text into the line, as the browser sends it after the pause.
     */
    public static void type(SearchPaletteView palette, String text) {
        queryField(palette).setValue(text);
    }

    /**
     * Presses a key that the palette handles itself, with the text of the line that the server has after the pause.
     */
    public static void press(SearchPaletteView palette, String key, boolean shift) {
        press(palette, key, shift, Strings.nullToEmpty(queryField(palette).getValue()));
    }

    /**
     * Presses a key that the palette handles itself, with the text the browser shows in the line, which the server
     * may not have yet.
     */
    public static void press(SearchPaletteView palette, String key, boolean shift, String text) {
        SearchPaletteLine line = line(palette);
        ComponentUtil.fireEvent(line, new SearchPaletteLine.KeyEvent(line, true, key, shift, text));
    }

    /**
     * Presses Enter with the text the browser shows in the line, which the server may not have yet.
     */
    public static void enter(SearchPaletteView palette, String text) {
        press(palette, "Enter", false, text);
    }

    /**
     * The id of the row that the line names to a screen reader as the chosen one; an empty string when it names none.
     */
    public static String activeRow(SearchPaletteView palette) {
        return line(palette).getElement().getProperty("activeRow", "");
    }

    public static List<String> pressedChips(SearchPaletteView palette) {
        return CHIPS.stream()
                .filter(id -> "true".equals(UiTestUtils.<JmixButton>getComponent(palette, id)
                        .getElement()
                        .getAttribute("aria-pressed")))
                .toList();
    }

    /**
     * The rows of the palette, in the order of the arrows.
     */
    public static List<Div> rows(SearchPaletteView palette) {
        return descendants(UiTestUtils.getComponent(palette, "resultsBox"))
                .filter(component -> component.hasClassName("search-palette-row"))
                .map(Div.class::cast)
                .toList();
    }

    public static List<String> names(SearchPaletteView palette) {
        return rows(palette).stream()
                .map(TestPalettes::name)
                .toList();
    }

    public static String name(Div row) {
        return part(row, "search-palette-name");
    }

    /**
     * The place of the row in muted text, or an empty string.
     */
    public static String meta(Div row) {
        return part(row, "search-palette-meta");
    }

    public static List<String> headings(SearchPaletteView palette) {
        return descendants(UiTestUtils.getComponent(palette, "resultsBox"))
                .filter(component -> component.hasClassName("search-palette-heading"))
                .map(component -> component.getElement().getText())
                .toList();
    }

    /**
     * The names of the rows of the group with the heading, in their order; none when the group is not shown.
     */
    public static List<String> groupNames(SearchPaletteView palette, String heading) {
        return group(palette, heading)
                .map(group -> group.getChildren()
                        .filter(child -> child.hasClassName("search-palette-row"))
                        .map(row -> name((Div) row))
                        .toList())
                .orElse(List.of());
    }

    public static List<String> groupMessages(SearchPaletteView palette, String heading) {
        return group(palette, heading)
                .map(group -> group.getChildren()
                        .filter(child -> child.hasClassName("search-palette-message"))
                        .map(child -> child.getElement().getText())
                        .toList())
                .orElse(List.of());
    }

    public static Div row(SearchPaletteView palette, String name) {
        return rows(palette).stream()
                .filter(row -> name.equals(name(row)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row " + name));
    }

    public static void click(SearchPaletteView palette, String name) {
        Div row = row(palette, name);
        ComponentUtil.fireEvent(row, new ClickEvent<>(row));
    }

    public static void remove(SearchPaletteView palette, String name) {
        removeButton(row(palette, name))
                .orElseThrow(() -> new AssertionError("No cross in the row " + name))
                .click();
    }

    /**
     * Clicks the component as the second click of a double click, which the browser counts as the second.
     */
    public static void secondClick(Component component) {
        ComponentUtil.fireEvent(component,
                new ClickEvent<>(component, true, 0, 0, 0, 0, 2, 0, false, false, false, false));
    }

    /**
     * Tells whether the browser stops a click on the component where it lands, so that the elements around it never
     * hear the click. The server gets each element's click on its own, so only the listener says it.
     */
    public static boolean keepsItsClicks(Component component) {
        return component.getElement()
                .getNode()
                .getFeature(ElementListenerMap.class)
                .getExpressions("click")
                .contains("event.stopPropagation()");
    }

    public static Optional<JmixButton> removeButton(Div row) {
        return row.getChildren()
                .filter(child -> child.hasClassName("search-palette-remove"))
                .map(JmixButton.class::cast)
                .findFirst();
    }

    public static List<Div> selectedRows(SearchPaletteView palette) {
        return rows(palette).stream()
                .filter(row -> "true".equals(row.getElement().getAttribute("aria-selected")))
                .toList();
    }

    public static String iconClass(Div row) {
        return row.getChildren()
                .findFirst()
                .orElseThrow()
                .getElement()
                .getProperty("iconClass");
    }

    public static String iconClass(JmixFontIcon icon) {
        return icon.create().getElement().getProperty("iconClass");
    }

    public static boolean isGreyed(Div row) {
        return row.getChildren()
                .anyMatch(child -> child.hasClassName("search-palette-name") && child.hasClassName("folder-greyed"));
    }

    public static boolean wearsAiAccent(Div row) {
        return row.getChildren()
                .findFirst()
                .orElseThrow()
                .hasClassName("aura-accent-purple");
    }

    /**
     * The scripts that the browser gets with the next response: the call takes them, so the next one returns only the
     * scripts queued after it.
     */
    public static List<PendingJavaScriptInvocation> sentScripts() {
        UI ui = UI.getCurrent();
        ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();

        return ui.getInternals().dumpPendingJavaScriptInvocations();
    }

    /**
     * The scripts that the browser gets with the next response for the elements, in the order it runs them: the calls
     * of their functions, which name the element first. The call takes all the scripts, as {@link #sentScripts()}
     * does.
     */
    public static List<JavaScriptInvocation> scriptsTo(Element... elements) {
        List<Element> addressees = List.of(elements);

        return sentScripts().stream()
                .map(PendingJavaScriptInvocation::getInvocation)
                .filter(invocation -> !invocation.getParameters().isEmpty()
                        && invocation.getParameters().getFirst() instanceof Element element
                        && addressees.contains(element))
                .toList();
    }

    /**
     * Tells whether the browser is to give the line the focus with the caret after its text, through the line alone:
     * a focus that Flow gave the field itself would let the field select its whole text, which it does at the opening
     * only. The call takes the scripts, as {@link #sentScripts()} does.
     */
    public static boolean focusesTheLineAtTheEnd(SearchPaletteView palette) {
        Element line = line(palette).getElement();
        Element field = queryField(palette).getElement();

        List<JavaScriptInvocation> invocations = sentScripts().stream()
                .map(PendingJavaScriptInvocation::getInvocation)
                .toList();

        return invocations.stream()
                .anyMatch(invocation -> invocation.getExpression().contains("$0.focusAtEnd(")
                        && invocation.getParameters().equals(List.of(line)))
                && invocations.stream()
                .noneMatch(invocation -> invocation.getParameters().contains(field));
    }

    /**
     * The query parameters of the address the browser shows, where the search page keeps its words and its mode.
     */
    public static QueryParameters currentQueryParameters() {
        return UI.getCurrent().getInternals().getActiveViewLocation().getQueryParameters();
    }

    private static Optional<Component> group(SearchPaletteView palette, String heading) {
        return UiTestUtils.<Div>getComponent(palette, "resultsBox")
                .getChildren()
                .filter(child -> child.hasClassName("search-palette-group"))
                .filter(child -> child.getChildren()
                        .anyMatch(part -> part.hasClassName("search-palette-heading")
                                && heading.equals(part.getElement().getText())))
                .findFirst();
    }

    private static SearchPaletteOpener opener() {
        return UiTestUtils.getComponent(TestMenus.mainView(), "searchPaletteOpener");
    }

    private static String part(Div row, String className) {
        return row.getChildren()
                .filter(child -> child.hasClassName(className))
                .findFirst()
                .map(child -> child.getElement().getTextRecursively())
                .orElse("");
    }

    // in the order of the page, which is the order of the arrows
    private static Stream<Component> descendants(Component component) {
        return component.getChildren()
                .flatMap(child -> Stream.concat(Stream.of(child), descendants(child)));
    }
}
