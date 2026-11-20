package com.company.docai.view.tour;

import com.company.docai.app.ui.component.searchpalette.SearchPaletteOpener;
import com.company.docai.app.ui.component.tour.TourPanel;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.popover.Popover;
import com.vaadin.flow.component.popover.PopoverVariant;
import com.vaadin.flow.shared.Registration;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.kit.component.button.JmixButton;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.ArrayList;
import java.util.List;

/**
 * A hint of the tour at its target: Doc, the text, the buttons and, in the upload, the sample invoice.
 */
public class TourHint extends Popover implements ApplicationContextAware, InitializingBean {

    private static final String WIDTH = "min(22rem, calc(100vw - 2rem))";
    private static final String SAMPLE_WIDTH = "min(30rem, calc(100vw - 2rem))";

    private final List<Registration> listeners = new ArrayList<>();

    private ApplicationContext applicationContext;
    private UiComponents uiComponents;

    private Paragraph hintText;
    private Div hintActions;
    private Div hintContent;

    private Runnable dismissHandler = () -> {
    };
    private Runnable targetGoneHandler = () -> {
    };
    @Nullable
    private Runnable actionHandler;
    @Nullable
    private SearchPaletteOpener paletteOpener;

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
        uiComponents = applicationContext.getBean(UiComponents.class);
    }

    private void initComponent() {
        setOpenOnClick(false);
        setOpenOnHover(false);
        setOpenOnFocus(false);
        setTabFocusEnabled(true);
        addThemeVariants(PopoverVariant.ARROW);

        getElement()
                .getClassList()
                .addAll(List.of("tour-popover", "aura-accent-color"));

        setWidth(WIDTH);

        hintText = uiComponents.create(Paragraph.class);
        hintActions = createHintActions();
        hintContent = createHintContent();
        add(hintContent);
    }

    private Div createHintActions() {
        Div hintActions = uiComponents.create(Div.class);
        hintActions.addClassName("tour-popover-actions");

        return hintActions;
    }

    private Div createHintContent() {
        Div hintContent = uiComponents.create(Div.class);
        hintContent.addClassName("tour-popover-content");
        hintContent.add(TourPanel.createDoc(), hintText, hintActions);

        return hintContent;
    }

    private JmixButton createChoiceButton(TourPanel.Choice choice) {
        JmixButton choiceButton = uiComponents.create(JmixButton.class);
        choiceButton.setText(choice.text());
        choiceButton.addClickListener(click -> choice.action().run());

        if (choice.primary()) {
            choiceButton.addThemeVariants(ButtonVariant.PRIMARY);
        }

        return choiceButton;
    }

    /**
     * Sets the text of the hint.
     */
    public void setText(String text) {
        hintText.setText(text);
    }

    /**
     * Sets the buttons of the hint.
     */
    public void setChoices(List<TourPanel.Choice> choices) {
        hintActions.removeAll();

        for (TourPanel.Choice choice : choices) {
            hintActions.add(createChoiceButton(choice));
        }
    }

    /**
     * Puts the sample invoice between the text and the buttons.
     */
    public void addSample(TourSampleCard sample) {
        // the hint with the invoice fits above the table only this wide
        setWidth(SAMPLE_WIDTH);
        hintContent.addComponentAtIndex(hintContent.indexOf(hintActions), sample);
    }

    /**
     * Makes the hint an explanation: it takes the focus as it opens and stays open at a click outside.
     */
    public void setExplanation(boolean explanation) {
        setCloseOnOutsideClick(!explanation);
        setAutofocus(explanation);
    }

    /**
     * Sets what a close of the hint in the browser does: Esc or a click outside.
     */
    public void setDismissHandler(Runnable dismissHandler) {
        this.dismissHandler = dismissHandler;
    }

    /**
     * Sets what the target leaving the page or the window of the target closing does.
     */
    public void setTargetGoneHandler(Runnable targetGoneHandler) {
        this.targetGoneHandler = targetGoneHandler;
    }

    /**
     * Sets what the person's first action at the target does; a hint without it ignores the target.
     */
    public void setActionHandler(@Nullable Runnable actionHandler) {
        this.actionHandler = actionHandler;
    }

    /**
     * Sets the palette that the target hands its clicks and keys to: the palette opening is an action at the target.
     */
    public void setPaletteOpener(@Nullable SearchPaletteOpener paletteOpener) {
        this.paletteOpener = paletteOpener;
    }

    /**
     * Opens the hint at the target; in a window Doc's element reveals it once the window stands.
     */
    public void openAt(Component target, TourPanel panel) {
        Dialog window = target.findAncestor(Dialog.class);

        listenToAction(target);
        listenToClose(target, window);
        setTarget(target);
        open();

        if (window != null) {
            panel.revealHint(this, window);
        }
    }

    /**
     * Stops listening and takes the hint off the page for good.
     */
    public void discard() {
        listeners.forEach(Registration::remove);
        listeners.clear();
        // with no target a hint that added itself to the page leaves it and never comes back open
        setTarget(null);
    }

    private void listenToAction(Component target) {
        Runnable handler = actionHandler;

        if (handler == null) {
            return;
        }

        // a click on its own target leaves a hint open, so it closes at the first action there; not at a focus: the
        // hub focuses its field itself, and Tab goes into the hint
        String action = target instanceof HasValue<?, ?>
                ? "input"
                : "click";

        listeners.add(target.getElement()
                .addEventListener(action, event -> handler.run()));

        if (paletteOpener != null) {
            listeners.add(paletteOpener.addOpenListener(open -> handler.run()));
        }
    }

    private void listenToClose(Component target, @Nullable Dialog window) {
        // Esc or a click outside; a close by the guide is not from the client
        listeners.add(addOpenedChangeListener(change -> {
            if (!change.isOpened() && change.isFromClient()) {
                dismissHandler.run();
            }
        }));

        listeners.add(target.addDetachListener(detach -> targetGoneHandler.run()));

        // a closed window leaves the page only after its closing animation, so its hint goes at once
        if (window != null) {
            listeners.add(window.addOpenedChangeListener(change -> {
                if (!change.isOpened()) {
                    targetGoneHandler.run();
                }
            }));
        }
    }
}
