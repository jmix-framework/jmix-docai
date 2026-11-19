package com.company.docai.view.document;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasLabel;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.html.NativeLabel;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.spring.annotation.SpringComponent;
import io.jmix.core.Messages;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.icon.JmixFontIcon;

import java.util.Optional;

/**
 * The marks of what Fill with AI set in the card: a wand with "AI" next to the label of a field and a dot on the tab
 * that holds such a field, both in the accent of AI.
 */
@SpringComponent
public class AiFillMarks {

    private static final String LABEL_CLASS = "ai-filled-label";
    private static final String DOT_CLASS = "ai-filled-dot";

    private final UiComponents uiComponents;
    private final Icons icons;
    private final Messages messages;

    public AiFillMarks(UiComponents uiComponents, Icons icons, Messages messages) {
        this.uiComponents = uiComponents;
        this.icons = icons;
        this.messages = messages;
    }

    /**
     * Puts the mark next to the label of the field, or takes it off; a screen reader reads the mark after the label.
     */
    public void markField(Component field, boolean marked) {
        Optional<Element> markedLabel = field.getElement()
                .getChildren()
                .filter(child -> child.getClassList().contains(LABEL_CLASS))
                .findFirst();

        if (!marked) {
            markedLabel.ifPresent(Element::removeFromParent);
            return;
        }

        if (markedLabel.isPresent() || !(field instanceof HasLabel labelled)) {
            return;
        }

        // a label in the slot of the field takes the place of the label the field draws, until it is removed
        NativeLabel label = uiComponents.create(NativeLabel.class);
        label.addClassName(LABEL_CLASS);
        label.getElement().setAttribute("slot", "label");
        label.setText(labelled.getLabel());
        label.add(mark());

        field.getElement().appendChild(label.getElement());
    }

    /**
     * Shows the dot on the tab, or takes it off; a screen reader reads that the tab holds fields Fill with AI set.
     */
    public void markTab(Tab tab, boolean marked) {
        Optional<Component> dot = tab.getChildren()
                .filter(child -> child.hasClassName(DOT_CLASS))
                .findFirst();

        if (!marked) {
            dot.ifPresent(tab::remove);
            return;
        }

        if (dot.isEmpty()) {
            Badge badge = uiComponents.create(Badge.class);
            badge.setText(messages.getMessage(getClass(), "aiFilledTab.text"));
            badge.addThemeVariants(BadgeVariant.DOT);
            badge.addClassNames("aura-accent-purple", DOT_CLASS);

            tab.add(badge);
        }
    }

    private Span mark() {
        Span mark = uiComponents.create(Span.class);
        mark.addClassNames("aura-accent-purple", "ai-filled-mark");
        mark.getElement().setAttribute("role", "img");
        mark.getElement().setAttribute("aria-label", messages.getMessage(getClass(), "aiFilledMark.ariaLabel"));
        mark.setText(messages.getMessage(getClass(), "aiFilledMark.text"));
        mark.addComponentAsFirst(icons.get(JmixFontIcon.MAGIC));

        return mark;
    }
}
