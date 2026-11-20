package com.company.docai.view.tour;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ModalityMode;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.shared.Registration;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The windows over one tab as the tour sees them: a step that waits for them goes on once they let it.
 */
public class TourWindows {

    private static final Logger log = LoggerFactory.getLogger(TourWindows.class);

    private final UI ui;
    private final TourScreens screens;
    private final List<Registration> listeners = new ArrayList<>();

    @Nullable
    private Predicate<List<Dialog>> ready;
    @Nullable
    private Runnable action;

    /**
     * Watches the windows of the tab of the UI; the screens tell which window is the card of a document.
     */
    public TourWindows(UI ui, TourScreens screens) {
        this.ui = ui;
        this.screens = screens;
    }

    /**
     * Runs the action once no window is open; it takes the place of an action still waiting.
     */
    public void whenNone(Runnable action) {
        waitFor(List::isEmpty, action);
    }

    /**
     * Runs the action once the card of a document is the only window, and at once without a card.
     */
    public void whenCardOnTop(Runnable action) {
        Optional<Dialog> card = screens.card()
                .map(view -> view.findAncestor(Dialog.class));

        if (card.isEmpty()) {
            action.run();
            return;
        }

        // a UI Recovery question may stand over the card: the hints wait until the card is on top
        waitFor(windows -> windows.size() == 1 && windows.getFirst() == card.get(), action);
    }

    /**
     * Forgets the action that waits for the windows.
     */
    public void cancel() {
        ready = null;
        action = null;
        removeListeners();
    }

    private void waitFor(Predicate<List<Dialog>> ready, Runnable action) {
        this.ready = ready;
        this.action = action;

        // a window opened earlier in this request joins the page just before the response, so they are counted then
        ui.beforeClientResponse(ui, context -> {
            try {
                check();
            } catch (RuntimeException e) {
                log.warn("The tour failed and is skipped", e);
            }
        });
    }

    private void check() {
        removeListeners();

        if (ready == null || action == null) {
            return;
        }

        List<Dialog> windows = openWindows();

        if (ready.test(windows)) {
            Runnable waiting = action;
            ready = null;
            action = null;
            waiting.run();
            return;
        }

        for (Dialog window : windows) {
            listeners.add(window.addOpenedChangeListener(change -> {
                if (!change.isOpened()) {
                    check();
                }
            }));
        }
    }

    private void removeListeners() {
        listeners.forEach(Registration::remove);
        listeners.clear();
    }

    private List<Dialog> openWindows() {
        List<Dialog> windows = new ArrayList<>();
        collectWindows(ui, windows);

        return windows;
    }

    private static void collectWindows(Component parent, List<Dialog> windows) {
        // the modeless discussion window leaves the page usable, so it does not count
        parent.getChildren()
                .filter(Dialog.class::isInstance)
                .map(Dialog.class::cast)
                .filter(dialog -> dialog.isOpened() && dialog.getModality() != ModalityMode.MODELESS)
                .forEach(dialog -> {
                    windows.add(dialog);
                    // a window opened over a strictly modal one lives inside it
                    collectWindows(dialog, windows);
                });
    }
}
