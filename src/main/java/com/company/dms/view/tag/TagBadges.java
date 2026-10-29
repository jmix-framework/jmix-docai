package com.company.dms.view.tag;

import com.company.dms.model.tag.Tag;
import com.company.dms.view.search.DocumentSearchView;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.QueryParameters;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Shows tags as badges in the document card, the document list and the search results. A badge sits inside a bare
 * button, so that a click, Enter or Space opens the page of the documents with its tag.
 */
@Component
public class TagBadges {

    private final UiComponents uiComponents;
    private final Icons icons;
    private final ViewNavigators viewNavigators;

    public TagBadges(UiComponents uiComponents, Icons icons, ViewNavigators viewNavigators) {
        this.uiComponents = uiComponents;
        this.icons = icons;
        this.viewNavigators = viewNavigators;
    }

    /**
     * Returns the badges of the tags; a click on a badge opens the page of the documents with its tag.
     */
    public Span create(@Nullable Collection<Tag> tags) {
        return create(tags, this::open);
    }

    /**
     * Returns the badges of the tags in alphabetical order whatever the case; a click on a badge passes its tag to
     * the handler.
     */
    public Span create(@Nullable Collection<Tag> tags, Consumer<Tag> clickHandler) {
        Span badges = uiComponents.create(Span.class);
        badges.addClassName("tag-badges");

        if (tags == null) {
            return badges;
        }

        // a tag typed in the card or added by the AI joins the end of the list
        List<Tag> sorted = tags.stream()
                .sorted(Comparator.comparing((Tag tag) -> tag.getName().toLowerCase(Locale.ROOT)))
                .toList();

        for (Tag tag : sorted) {
            Badge badge = uiComponents.create(Badge.class);
            badge.setText(tag.getName());
            badge.setIcon(icons.get(JmixFontIcon.TAG));
            badge.addClassName("aura-accent-blue");

            NativeButton button = uiComponents.create(NativeButton.class);
            button.addClassName("tag-badge");
            button.add(badge);
            button.addClickListener(click -> clickHandler.accept(tag));
            badges.add(button);
        }

        return badges;
    }

    /**
     * Opens the page of the documents with the tag.
     */
    public void open(Tag tag) {
        viewNavigators.view(UiComponentUtils.getCurrentView(), DocumentSearchView.class)
                .withQueryParameters(QueryParameters.of(DocumentSearchView.TAG_PARAMETER, tag.getName()))
                .navigate();
    }
}
