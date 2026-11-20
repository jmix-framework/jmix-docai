package com.company.docai.view.tour;

import com.company.docai.app.ui.component.searchpalette.SearchPaletteOpener;
import com.company.docai.app.ui.component.tour.TourPanel;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.shared.Registration;
import io.jmix.aitoolsflowui.view.chat.AiChatView;
import io.jmix.aitoolsflowui.view.chathub.AiChatHubView;
import io.jmix.core.Messages;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.app.main.StandardMainView;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.component.sidepanellayout.JmixSidePanelLayout;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The tour of one tab: Doc, his bubble and the hints at the targets; the progress lives in the session.
 */
public class TourGuide {

    private static final int ROLL_IN_DELAY_MS = 600;
    private static final int GOODBYE_MS = 5000;

    private static final Set<TourChapter> EXPLAINED = EnumSet.of(TourChapter.FILL_WITH_AI,
            TourChapter.DOCUMENT_ASSISTANT, TourChapter.UPLOAD);
    private static final Set<TourChapter> IN_CARD = EnumSet.of(TourChapter.FILL_WITH_AI,
            TourChapter.DOCUMENT_ASSISTANT);

    private final UI ui;
    private final StandardMainView mainView;
    private final TourPanel panel;
    private final TourScreens screens;
    private final UiComponents uiComponents;
    private final Messages messages;
    private final Icons icons;
    private final TourWindows windows;

    private final Map<TourChapter, TourReason> missing = new EnumMap<>(TourChapter.class);
    private final List<Registration> historyListeners = new ArrayList<>();

    @Nullable
    private TourHint hint;
    @Nullable
    private TourChapter hintChapter;
    @Nullable
    private TourSampleCard sample;
    @Nullable
    private Runnable sampleDragStop;
    @Nullable
    private TourChapter waiting;
    @Nullable
    private String path;
    private boolean docShown;
    private boolean assistantMenuStep;
    private boolean chatShown;
    private boolean historyShown;
    private boolean discussionOpen;

    public TourGuide(UI ui, StandardMainView mainView, TourPanel panel, TourScreens screens, UiComponents uiComponents,
                     Messages messages, Icons icons) {
        this.ui = ui;
        this.mainView = mainView;
        this.panel = panel;
        this.screens = screens;
        this.uiComponents = uiComponents;
        this.messages = messages;
        this.icons = icons;
        windows = new TourWindows(ui, screens);

        panel.setDocClickHandler(this::toggle);
        panel.setCollapseHandler(() -> panel.setExpanded(false));
        panel.setEndHandler(this::end);

        panel.setShowHandler(number -> TourChapter.ofNumber(number)
                .ifPresent(this::show));
    }

    /**
     * Shows the tour as the main view opens: a running one as folded Doc, a new one as the greeting if asked.
     */
    public void open(boolean greet) {
        TourState state = state();
        panel.setProgress(state.getDoneCount());

        if (state.getStatus() == TourState.Status.RUNNING) {
            showDoc();
        } else if (state.getStatus() == TourState.Status.NOT_STARTED && greet) {
            greet();
        } else {
            panel.setShown(false);
        }
    }

    /**
     * Greets anew after the progress was reset from the user menu.
     */
    public void restart() {
        writeCookie();
        endStep();
        missing.clear();
        panel.setProgress(0);
        panel.setLine(List.of());
        greet();
    }

    /**
     * Answers a chapter just marked done; the upload also brings the second chapter its document.
     */
    public void chapterDone(TourChapter chapter) {
        TourState state = state();
        writeCookie();
        panel.setProgress(state.getDoneCount());

        if (chapter == TourChapter.UPLOAD) {
            missing.remove(TourChapter.FILL_WITH_AI);
        }

        if (state.getStatus() != TourState.Status.RUNNING) {
            return;
        }

        endStep();
        windows.whenNone(this::celebrate);
    }

    /**
     * Follows the screen: closes a hint left behind and moves Doc to the spot of the screen.
     */
    public void afterNavigation(AfterNavigationEvent event) {
        String location = event.getLocation().getPath();
        boolean screenChanged = !location.equals(path);
        path = location;

        chatShown = event.getActiveChain()
                .stream()
                .anyMatch(AiChatView.class::isInstance);

        watchHistory(event);

        TourState state = state();

        // another tab may have ended the tour meanwhile; a new query alone, such as another folder, keeps the hint
        if (assistantMenuStep && screens.isHubShown() && state.getStatus() == TourState.Status.RUNNING
                && !state.isDone(TourChapter.AI_ASSISTANT)) {
            assistantMenuStep = false;
            act(TourChapter.AI_ASSISTANT);
        } else if (hint != null && screenChanged) {
            closeHint();
        }

        updateDock();
    }

    /**
     * Moves Doc to the left while the discussion window covers the right side.
     */
    public void discussionOpened(boolean opened) {
        discussionOpen = opened;
        updateDock();
    }

    /**
     * Closes the hint of the upload once an upload begins.
     */
    public void uploadStarted() {
        if (hintChapter == TourChapter.UPLOAD || waiting == TourChapter.UPLOAD) {
            closeHint();
            listen(TourChapter.UPLOAD);
        }
    }

    /**
     * Uploads the sample invoice when the table took the card of the open hint as a drop.
     */
    public void dropped(Component source) {
        if (source == sample) {
            uploadSample();
        }
    }

    /**
     * Takes the tour off the page with its main view.
     */
    public void detach() {
        endStep();
        historyListeners.forEach(Registration::remove);
        historyListeners.clear();
        ui.remove(panel);

        if (ComponentUtil.getData(ui, TourGuide.class) == this) {
            ComponentUtil.setData(ui, TourGuide.class, null);
        }
    }

    private void greet() {
        closeHint();
        panel.setExpanded(false);
        panel.setMood(TourPanel.Mood.CALM);
        showDoc();

        say(message("greeting.text"), List.of(choice("startButton", true, this::start),
                choice("skipButton", false, this::end)), null);
    }

    private void start() {
        TourState state = state();

        // another tab may have begun or ended the tour meanwhile
        if (state.getStatus() == TourState.Status.RUNNING) {
            expand();
            return;
        }

        if (state.getStatus() != TourState.Status.NOT_STARTED) {
            leave();
            return;
        }

        state.start();
        writeCookie();
        screens.openHome();
        say(message("home.text"), List.of(choice("nextButton", true, this::afterPrologue)), null);
    }

    private void afterPrologue() {
        if (screens.availability(TourChapter.FILL_WITH_AI).isPresent()) {
            sayInPanel(List.of(message(TourReason.AI_OFF.getNoteKey())));
        } else if (screens.availability(TourChapter.AI_ASSISTANT).isPresent()) {
            sayInPanel(List.of(message(TourReason.NO_ASSISTANT.getNoteKey())));
        } else {
            panel.setLine(List.of());
        }

        expand();
    }

    private void end() {
        state().end();
        writeCookie();
        endStep();
        panel.setExpanded(false);
        panel.setMood(TourPanel.Mood.CALM);
        say(message("goodbye.text"), List.of(), this::leave);
        panel.play(TourPanel.Motion.LEAVE, GOODBYE_MS);
        docShown = false;
    }

    private void finale() {
        state().finish();
        writeCookie();
        endStep();
        panel.setExpanded(false);
        panel.setMood(TourPanel.Mood.CALM);
        panel.play(TourPanel.Motion.GLAD, 0);

        String text = screens.availability(TourChapter.FILL_WITH_AI).isPresent()
                ? message("finale.noAiText")
                : message("finale.text");

        say(text, List.of(choice("finishButton", true, this::leave)), null);
    }

    private void leave() {
        panel.hideBubble();
        panel.play(TourPanel.Motion.LEAVE, 0);
        docShown = false;
    }

    private void toggle() {
        if (state().getStatus() != TourState.Status.RUNNING) {
            return;
        }

        if (panel.isExpanded()) {
            panel.setExpanded(false);
        } else {
            expand();
        }
    }

    private void expand() {
        renderRows();
        panel.setExpanded(true);
    }

    private void show(TourChapter chapter) {
        Optional<TourReason> reason = screens.check(chapter);

        if (reason.isPresent()) {
            if (reason.get() == TourReason.MISSING_DOCUMENT || reason.get() == TourReason.NO_FOLDER_ACCESS) {
                missing.put(chapter, reason.get());
            }

            sayInPanel(List.of(message(reason.get().getNoteKey())));
            renderRows();
            return;
        }

        missing.remove(chapter);
        endStep();
        panel.setExpanded(false);
        panel.hideBubble();
        assistantMenuStep = chapter == TourChapter.AI_ASSISTANT && !screens.isHubShown();
        screens.open(mainView, chapter);

        if (EXPLAINED.contains(chapter) && state().markExplained(chapter)) {
            explain(chapter);
        } else {
            act(chapter);
        }
    }

    private void explain(TourChapter chapter) {
        if (chapter == TourChapter.UPLOAD) {
            say(message("repository.text"), List.of(choice("nextButton", true, () -> {
                panel.hideBubble();
                act(TourChapter.UPLOAD);
            })), null);

            return;
        }

        windows.whenCardOnTop(() -> screens.explanation(chapter)
                .ifPresentOrElse(target -> openHint(chapter, target, true), () -> act(chapter)));
    }

    private void act(TourChapter chapter) {
        Runnable open = () -> screens.action(mainView, chapter)
                .ifPresentOrElse(target -> openHint(chapter, target, false), () -> sayStep(chapter));

        if (IN_CARD.contains(chapter)) {
            windows.whenCardOnTop(open);
        } else {
            open.run();
        }
    }

    private void sayStep(TourChapter chapter) {
        listen(chapter);

        say(message(stepKey(chapter)), List.of(choice("gotItButton", true, panel::hideBubble),
                choice("skipTaskButton", false, () -> skip(chapter))), null);
    }

    private void openHint(TourChapter chapter, TourScreens.Target target, boolean explanation) {
        closeHint();

        TourHint created = uiComponents.create(TourHint.class);
        created.setAriaLabel(messages.formatMessage(getClass(), "popover.ariaLabel", title(chapter)));
        created.setPosition(target.position());
        created.setText(message(target.textKey()));
        created.setExplanation(explanation);
        created.setDismissHandler(() -> dismissed(chapter));
        created.setTargetGoneHandler(this::closeHint);

        if (explanation) {
            created.setChoices(List.of(choice("nextButton", true, () -> {
                closeHint();
                act(chapter);
            })));
        } else {
            prepareTask(created, chapter);
        }

        hint = created;
        hintChapter = chapter;
        panel.setMood(TourPanel.Mood.POINTING);
        created.openAt(target.component(), panel);
    }

    private void prepareTask(TourHint task, TourChapter chapter) {
        TourPanel.Choice skipTask = choice("skipTaskButton", false, () -> skip(chapter));

        Optional<String> sampleName = chapter == TourChapter.UPLOAD
                ? screens.sampleName()
                : Optional.empty();

        if (sampleName.isPresent()) {
            task.addSample(createSample(sampleName.get()));
            task.setChoices(List.of(choice("uploadSampleButton", true, this::uploadSample), skipTask));
        } else {
            task.setChoices(List.of(choice("gotItButton", true, () -> acted(chapter)), skipTask));
        }

        // the menu item closes its hint by navigating, the table by the upload
        if (chapter == TourChapter.UPLOAD || (chapter == TourChapter.AI_ASSISTANT && assistantMenuStep)) {
            return;
        }

        task.setActionHandler(() -> acted(chapter));

        // the search field hands its clicks and keys to the palette, which opens over the hint
        if (chapter == TourChapter.SEARCH_BY_MEANING) {
            UiComponentUtils.findComponent(mainView, "searchPaletteOpener")
                    .filter(SearchPaletteOpener.class::isInstance)
                    .map(SearchPaletteOpener.class::cast)
                    .ifPresent(task::setPaletteOpener);
        }
    }

    private TourSampleCard createSample(String name) {
        TourSampleCard card = new TourSampleCard(icons.get(JmixFontIcon.FILE_TEXT));
        card.setName(name);
        card.setAriaLabel(messages.formatMessage(getClass(), "sample.ariaLabel", name));
        card.setDragStartHandler(this::startSampleDrag);
        card.setDragEndHandler(this::stopSampleDrag);
        sample = card;

        return card;
    }

    private void startSampleDrag() {
        stopSampleDrag();

        sampleDragStop = screens.startSampleDrag()
                .orElse(null);
    }

    private void stopSampleDrag() {
        Runnable stop = sampleDragStop;
        sampleDragStop = null;

        // the handle reaches the table even after a navigation to a page that is no view
        if (stop != null) {
            stop.run();
        }
    }

    private void uploadSample() {
        screens.uploadSample(this::uploadStarted);
    }

    private void acted(TourChapter chapter) {
        closeHint();
        listen(chapter);
    }

    private void listen(TourChapter chapter) {
        waiting = chapter;
        panel.hideBubble();
        panel.setMood(TourPanel.Mood.LISTENING);
    }

    private void dismissed(TourChapter chapter) {
        discardHint();
        waiting = chapter;
        panel.setMood(TourPanel.Mood.LISTENING);

        say(message("showAgain.text"), List.of(choice("showAgainButton", true, () -> show(chapter))),
                panel::hideBubble);
    }

    private void skip(TourChapter chapter) {
        state().skip(chapter);
        endStep();
        panel.hideBubble();
        windows.whenNone(this::moveOn);
    }

    private void moveOn() {
        if (next().isEmpty()) {
            finale();
        } else {
            panel.setLine(List.of());
            expand();
        }
    }

    private void celebrate() {
        TourState state = state();

        if (state.getStatus() != TourState.Status.RUNNING) {
            return;
        }

        Optional<TourChapter> next = next();

        if (next.isEmpty()) {
            finale();
            return;
        }

        panel.play(TourPanel.Motion.GLAD, 0);

        sayInPanel(List.of(messages.formatMessage(getClass(), "taskDone.title", state.getDoneCount()),
                messages.formatMessage(getClass(), "taskDone.next", title(next.get()))));

        expand();
    }

    private void endStep() {
        waiting = null;
        assistantMenuStep = false;
        windows.cancel();
        closeHint();
    }

    private void closeHint() {
        if (hint != null) {
            hint.close();
        }

        discardHint();
    }

    private void discardHint() {
        sample = null;
        // a card that leaves the page while dragged reports no drag end
        stopSampleDrag();

        if (hint != null) {
            hint.discard();
            hint = null;
        }

        hintChapter = null;
        panel.setMood(waiting != null ? TourPanel.Mood.LISTENING : TourPanel.Mood.CALM);
    }

    private void watchHistory(AfterNavigationEvent event) {
        historyListeners.forEach(Registration::remove);
        historyListeners.clear();
        historyShown = false;

        event.getActiveChain()
                .stream()
                .filter(AiChatHubView.class::isInstance)
                .map(AiChatHubView.class::cast)
                .findFirst()
                .flatMap(hub -> UiComponentUtils.findComponent(hub, "hubFragment.historySidePanel"))
                .filter(JmixSidePanelLayout.class::isInstance)
                .map(JmixSidePanelLayout.class::cast)
                .ifPresent(history -> {
                    historyShown = history.isSidePanelOpened();
                    historyListeners.add(history.addSidePanelAfterOpenListener(open -> {
                        historyShown = true;
                        updateDock();
                    }));

                    historyListeners.add(history.addSidePanelCloseListener(close -> {
                        historyShown = false;
                        updateDock();
                    }));
                });
    }

    private void updateDock() {
        if (discussionOpen || historyShown) {
            panel.setDock(TourPanel.Dock.LEFT);
        } else if (chatShown) {
            panel.setDock(TourPanel.Dock.RIGHT_RAISED);
        } else {
            panel.setDock(TourPanel.Dock.RIGHT);
        }
    }

    private void renderRows() {
        TourState state = state();
        Optional<TourChapter> next = next();
        List<TourPanel.Row> rows = new ArrayList<>();

        for (TourChapter chapter : TourChapter.values()) {
            Optional<TourReason> reason = unavailability(chapter);
            TourPanel.RowState rowState;

            if (state.isDone(chapter)) {
                rowState = TourPanel.RowState.DONE;
            } else if (reason.isPresent()) {
                rowState = TourPanel.RowState.UNAVAILABLE;
            } else if (next.filter(chapter::equals).isPresent()) {
                rowState = TourPanel.RowState.NEXT;
            } else {
                rowState = TourPanel.RowState.OPEN;
            }

            rows.add(new TourPanel.Row(chapter.getNumber(), title(chapter), rowState, reason
                    .map(found -> message(found.getReasonKey()))
                    .orElse(null)));
        }

        panel.setRows(rows);
        panel.setProgress(state.getDoneCount());
    }

    private Optional<TourChapter> next() {
        return state().next(chapter -> unavailability(chapter).isEmpty());
    }

    private Optional<TourReason> unavailability(TourChapter chapter) {
        return screens.availability(chapter)
                .or(() -> Optional.ofNullable(missing.get(chapter)));
    }

    private void showDoc() {
        panel.setShown(true);

        if (!docShown) {
            panel.play(TourPanel.Motion.ROLL_IN, ROLL_IN_DELAY_MS);
            docShown = true;
        }
    }

    private void say(String text, List<TourPanel.Choice> choices, @Nullable Runnable close) {
        panel.setBubbleCloseHandler(close == null
                ? () -> {
                }
                : close);

        panel.showBubble(text, choices, close != null);
        panel.announce(text);
    }

    private void sayInPanel(List<String> lines) {
        panel.setLine(lines);
        panel.announce(String.join(" ", lines));
    }

    private TourPanel.Choice choice(String key, boolean primary, Runnable action) {
        return new TourPanel.Choice(message(key), primary, action);
    }

    private String stepKey(TourChapter chapter) {
        return switch (chapter) {
            case FILL_WITH_AI -> "fillWithAi.text";
            case SEARCH_BY_MEANING -> "searchByMeaning.text";
            case DOCUMENT_ASSISTANT -> "leaseAssistant.text";
            case AI_ASSISTANT -> "assistantQuestion.text";
            case UPLOAD -> "upload.text";
        };
    }

    private void writeCookie() {
        panel.writeState(state().cookieValue());
    }

    private TourState state() {
        return Tour.state(ui.getSession());
    }

    private String title(TourChapter chapter) {
        return message(chapter.getTitleKey());
    }

    private String message(String key) {
        return messages.getMessage(getClass(), key);
    }
}
