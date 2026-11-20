package com.company.docai.app.ui.component.tour;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import io.jmix.flowui.kit.icon.JmixFontIcon;

import java.util.List;

/**
 * Doc's speech bubble: his words, a button for each answer and the close button; hidden until it is shown.
 */
public class TourBubble extends Composite<Div> {

    private final Div bubbleText = createBubbleText();
    private final Div bubbleActions = createBubbleActions();
    private final Button closeButton = createCloseButton();

    private Runnable closeHandler = () -> {
    };

    @Override
    protected Div initContent() {
        Div bubble = super.initContent();

        bubble.addClassName("tour-bubble");
        bubble.add(bubbleText, bubbleActions, closeButton);
        bubble.setVisible(false);

        return bubble;
    }

    /**
     * Shows the bubble with the text and its buttons; only a closable bubble has the close button.
     */
    public void show(String text, List<TourPanel.Choice> choices, boolean closable) {
        bubbleText.setText(text);
        bubbleActions.removeAll();

        for (TourPanel.Choice choice : choices) {
            bubbleActions.add(createChoiceButton(choice));
        }

        closeButton.setVisible(closable);
        setVisible(true);
    }

    /**
     * Hides the bubble.
     */
    public void hide() {
        setVisible(false);
    }

    /**
     * Sets the name of the close button for screen readers.
     */
    public void setCloseAriaLabel(String closeAriaLabel) {
        closeButton.setAriaLabel(closeAriaLabel);
    }

    /**
     * Sets what the close button does.
     */
    public void setCloseHandler(Runnable closeHandler) {
        this.closeHandler = closeHandler;
    }

    private Div createBubbleText() {
        Div bubbleText = new Div();
        bubbleText.addClassName("tour-bubble-text");

        return bubbleText;
    }

    private Div createBubbleActions() {
        Div bubbleActions = new Div();
        bubbleActions.addClassName("tour-bubble-actions");

        return bubbleActions;
    }

    private Button createCloseButton() {
        Button closeButton = new Button(JmixFontIcon.CLOSE.create());
        closeButton.addClassName("tour-bubble-close");
        closeButton.addThemeVariants(ButtonVariant.TERTIARY);
        closeButton.addClickListener(click -> closeHandler.run());

        return closeButton;
    }

    private Button createChoiceButton(TourPanel.Choice choice) {
        Button choiceButton = new Button(choice.text(), click -> choice.action().run());

        if (choice.primary()) {
            choiceButton.addThemeVariants(ButtonVariant.PRIMARY);
        }

        return choiceButton;
    }
}
