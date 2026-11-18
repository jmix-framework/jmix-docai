package com.company.docai.test_support;

import com.company.docai.model.document.Document;
import com.company.docai.model.tag.Tag;
import com.company.docai.view.search.DocumentSearchView;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.html.NativeButton;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.UnconstrainedDataManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Makes tags, puts them on documents past the rules of the card, and reads and clicks the tag badges that a view
 * shows, as a browser would.
 */
public final class TestTags {

    private TestTags() {
    }

    public static Tag tag(UnconstrainedDataManager dataManager, String name) {
        Tag tag = dataManager.create(Tag.class);
        tag.setName(name);
        return dataManager.save(tag);
    }

    /**
     * Gives the document exactly these tags and returns it saved.
     */
    public static Document tagged(UnconstrainedDataManager dataManager, Document document, Tag... tags) {
        Document stored = dataManager.load(Id.of(document))
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("tags", FetchPlan.BASE))
                .one();
        stored.setTags(new ArrayList<>(List.of(tags)));
        return dataManager.save(stored);
    }

    public static List<String> names(Component component) {
        return badges(component)
                .map(button -> button.getChildren()
                        .filter(Badge.class::isInstance)
                        .map(badge -> ((Badge) badge).getText())
                        .findFirst()
                        .orElseThrow())
                .toList();
    }

    public static void click(Component component, String name) {
        NativeButton button = badges(component)
                .filter(badge -> badge.getChildren()
                        .anyMatch(child -> child instanceof Badge text && name.equals(text.getText())))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No badge of tag " + name));

        ComponentUtil.fireEvent(button, new ClickEvent<>(button));
    }

    public static Optional<String> tagInAddress() {
        return UI.getCurrent()
                .getInternals()
                .getActiveViewLocation()
                .getQueryParameters()
                .getSingleParameter(DocumentSearchView.TAG_PARAMETER);
    }

    private static Stream<NativeButton> badges(Component component) {
        return Stream.concat(Stream.of(component), descendants(component))
                .filter(NativeButton.class::isInstance)
                .map(NativeButton.class::cast)
                .filter(button -> button.hasClassName("tag-badge"));
    }

    private static Stream<Component> descendants(Component component) {
        return component.getChildren()
                .flatMap(child -> Stream.concat(Stream.of(child), descendants(child)));
    }
}
