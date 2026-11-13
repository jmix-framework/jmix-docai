package com.company.docai.test_support;

import com.company.docai.app.service.dashboard.UpcomingDate;
import com.company.docai.model.document.Document;
import com.company.docai.view.dashboard.DashboardFragment;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.kit.component.button.JmixButton;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads and clicks the blocks of the dashboard as a browser would: the rows of a block, their texts, the button of
 * a row that opens what the row shows and the buttons after it. It also checks the dates of a test against those the
 * block of the nearest dates shows.
 */
public final class TestDashboards {

    // the block of the nearest dates shows five of them
    private static final int UPCOMING_DATES = 5;

    private TestDashboards() {
    }

    /**
     * Asserts that the dates of a test among the upcoming ones follow the rule of the block, the nearest first. The
     * database is shared, so the dates of other tests may take some of the five places: each date of the test is
     * checked against the last date shown — a nearer one shows, a later one does not, and one of that very day may go
     * either way.
     */
    public static void assertNearestShown(List<UpcomingDate> shown, Map<Document, LocalDate> own) {
        List<Document> documents = shown.stream()
                .map(UpcomingDate::document)
                .toList();

        LocalDate last = shown.size() < UPCOMING_DATES ? LocalDate.MAX : shown.getLast().date();

        own.forEach((document, date) -> {
            if (date.isBefore(last)) {
                assertThat(documents).contains(document);
            } else if (date.isAfter(last)) {
                assertThat(documents).doesNotContain(document);
            }
        });
    }

    /**
     * A component of the dashboard by its id in the fragment.
     */
    @SuppressWarnings("unchecked")
    public static <T> T component(DashboardFragment dashboard, String id) {
        return (T) FragmentUtils.getComponent(dashboard, id);
    }

    /**
     * The rows of a block: {@code upcomingBox}, {@code untypedBox}, {@code activityBox} or {@code chatsBox}. A block
     * without rows holds only the line that says so.
     */
    public static List<Div> rows(DashboardFragment dashboard, String box) {
        return TestDashboards.<VerticalLayout>component(dashboard, box)
                .getChildren()
                .filter(Div.class::isInstance)
                .map(Div.class::cast)
                .toList();
    }

    /**
     * The first row of a block whose text holds the given one.
     */
    public static Div row(DashboardFragment dashboard, String box, String text) {
        return rows(dashboard, box)
                .stream()
                .filter(row -> text(row).contains(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row with \"%s\" in %s".formatted(text, box)));
    }

    /**
     * Tells whether a row of a block holds the text.
     */
    public static boolean hasRow(DashboardFragment dashboard, String box, String text) {
        return rows(dashboard, box)
                .stream()
                .anyMatch(row -> text(row).contains(text));
    }

    /**
     * The text of a component with the texts of its parts.
     */
    public static String text(Component component) {
        return component.getElement().getTextRecursively();
    }

    /**
     * The parts of a row inside its button, in their order: the date, the avatar or the icon first.
     */
    public static List<Component> parts(Div row) {
        return opener(row)
                .getChildren()
                .toList();
    }

    /**
     * The buttons after the button of a row, such as Fill with AI.
     */
    public static List<JmixButton> buttons(Div row) {
        return row.getChildren()
                .filter(JmixButton.class::isInstance)
                .map(JmixButton.class::cast)
                .toList();
    }

    /**
     * Clicks the button of a row, which opens what the row shows.
     */
    public static void click(Div row) {
        NativeButton opener = opener(row);
        ComponentUtil.fireEvent(opener, new ClickEvent<>(opener));
    }

    private static NativeButton opener(Div row) {
        return (NativeButton) row.getChildren()
                .findFirst()
                .orElseThrow();
    }
}
