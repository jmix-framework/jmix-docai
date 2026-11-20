package com.company.docai.view.tour;

import com.company.docai.ai.AiSettings;
import com.company.docai.app.ui.component.tour.TourPanel;
import com.company.docai.model.document.Document;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinSession;
import io.jmix.core.Messages;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.app.main.StandardMainView;
import io.jmix.flowui.icon.Icons;
import jakarta.servlet.http.Cookie;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The tour of the application with Doc: each main view builds its guide here, and features report their results
 * here. A failure of the tour is logged and never reaches the feature that reported to it.
 */
@org.springframework.stereotype.Component
public class Tour {

    private static final Logger log = LoggerFactory.getLogger(Tour.class);

    private final boolean autoOpen;
    private final AiSettings aiSettings;
    private final TourScreens screens;
    private final UiComponents uiComponents;
    private final Messages messages;
    private final Icons icons;

    public Tour(@Value("${docai.tour.auto-open:true}") boolean autoOpen, AiSettings aiSettings, TourScreens screens,
                UiComponents uiComponents, Messages messages, Icons icons) {
        this.autoOpen = autoOpen;
        this.aiSettings = aiSettings;
        this.screens = screens;
        this.uiComponents = uiComponents;
        this.messages = messages;
        this.icons = icons;
    }

    /**
     * Builds the tour of the tab of the main view. The main view comes as the framework type: Studio's hot deploy
     * loads MainView anew in another class loader.
     */
    public void attach(StandardMainView mainView) {
        UI ui = UI.getCurrent();
        VaadinSession session = ui.getSession();
        VaadinRequest request = VaadinRequest.getCurrent();
        String cookie = request == null ? null : cookieOf(request.getCookies());

        TourState state = TourState.of(session.getAttribute(TourState.class), cookie);
        session.setAttribute(TourState.class, state);

        TourPanel panel = uiComponents.create(TourPanel.class);

        if (request != null) {
            panel.setCookieTarget(request.getContextPath(), request.isSecure());
        }

        ui.add(panel);

        TourGuide guide = new TourGuide(ui, mainView, panel, screens, uiComponents, messages, icons);
        ComponentUtil.setData(ui, TourGuide.class, guide);
        mainView.addDetachListener(detach -> guide.detach());

        if (state.differsFrom(cookie)) {
            panel.writeState(state.cookieValue());
        }

        guide.open(state.greetsByItself(autoOpen, aiSettings.isAvailable()));
    }

    /**
     * Marks the chapter done in the session; without a page the cookie catches up at the next main view.
     */
    public void done(TourChapter chapter) {
        guarded(() -> {
            VaadinSession session = VaadinSession.getCurrent();

            if (session == null || !state(session).markDone(chapter)) {
                return;
            }

            guide().ifPresent(guide -> guide.chapterDone(chapter));
        });
    }

    /**
     * Marks the first chapter done by an upload and remembers its first document for the second chapter.
     */
    public void uploaded(List<Document> created) {
        guarded(() -> {
            VaadinSession session = VaadinSession.getCurrent();

            if (created.isEmpty() || session == null || !state(session).markDone(TourChapter.UPLOAD)) {
                return;
            }

            state(session).rememberUploaded(created.getFirst().getId());
            guide().ifPresent(guide -> guide.chapterDone(TourChapter.UPLOAD));
        });
    }

    /**
     * Tells the guide what the table of a folder took as a drop from the page.
     */
    public void dropped(Component source) {
        guide().ifPresent(guide -> guide.dropped(source));
    }

    /**
     * Begins the tour anew from the user menu.
     */
    public void restart() {
        state(VaadinSession.getCurrent()).restart();
        guide().ifPresent(TourGuide::restart);
    }

    /**
     * Tells the guide of the tab that the screen changed.
     */
    public void afterNavigation(AfterNavigationEvent event) {
        guarded(() -> guide().ifPresent(guide -> guide.afterNavigation(event)));
    }

    /**
     * Tells the guide that the AI discussion window opened or closed.
     */
    public void discussionOpened(boolean opened) {
        guarded(() -> guide().ifPresent(guide -> guide.discussionOpened(opened)));
    }

    /**
     * Tells the guide that an upload into a folder began.
     */
    public void uploadStarted() {
        guarded(() -> guide().ifPresent(TourGuide::uploadStarted));
    }

    /**
     * Returns the progress of the tour in the session.
     */
    public static TourState state(VaadinSession session) {
        TourState state = session.getAttribute(TourState.class);

        if (state == null) {
            state = new TourState();
            session.setAttribute(TourState.class, state);
        }

        return state;
    }

    /**
     * Returns the guide of the current tab, if its main view has built one.
     */
    public static Optional<TourGuide> guide() {
        UI ui = UI.getCurrent();

        return ui == null
                ? Optional.empty()
                : Optional.ofNullable(ComponentUtil.getData(ui, TourGuide.class));
    }

    private static void guarded(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            log.warn("The tour failed and is skipped", e);
        }
    }

    private static @Nullable String cookieOf(Cookie @Nullable [] cookies) {
        return cookies == null
                ? null
                : Arrays.stream(cookies)
                        .filter(cookie -> TourState.COOKIE_NAME.equals(cookie.getName()))
                        .map(Cookie::getValue)
                        .findFirst()
                        .orElse(null);
    }
}
