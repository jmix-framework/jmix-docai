package com.company.docai.view.main;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.DomEvent;
import com.vaadin.flow.component.EventData;
import com.vaadin.flow.component.HasStyle;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.dependency.JsModule;
import io.jmix.core.Messages;
import io.jmix.flowui.settings.UserSettingsService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

/**
 * The resizable edge of the main menu: a drag or the arrow keys move it within limits, in steps of a quarter of a
 * rem, and the menu opens as wide as the user left it. The element {@code docai-drawer-handle} of
 * {@code components/drawer-handle.js} moves the edge in the browser; this class restores the width, keeps it within
 * the limits and saves it. The main view declares it in its descriptor. It is not a Spring bean: the component
 * factory would give every browser tab the same one.
 */
@Tag("docai-drawer-handle")
@JsModule("./components/drawer-handle.js")
public class DrawerHandle extends Component implements HasStyle {

    /**
     * The user setting with the width of the main menu in rem, which the user drags with its edge.
     */
    public static final String DRAWER_WIDTH_SETTING = "docai.drawerWidth";

    private static final String DRAWER_WIDTH = "--main-view-drawer-width";
    private static final double MIN_DRAWER_WIDTH = 16;
    private static final double MAX_DRAWER_WIDTH = 32;

    private UserSettingsService userSettingsService;

    /**
     * Creates the edge with its class, the role of a separator that takes the keyboard focus, and the limits of the
     * width in rem for the browser.
     */
    public DrawerHandle() {
        addClassName("main-view-drawer-handle");

        getElement()
                .setAttribute("role", "separator")
                .setAttribute("aria-orientation", "vertical")
                .setAttribute("tabindex", "0")
                .setAttribute("min", String.valueOf(MIN_DRAWER_WIDTH))
                .setAttribute("max", String.valueOf(MAX_DRAWER_WIDTH));

        addListener(DrawerResizeEvent.class, resize -> resizeDrawer(resize.getWidth()));
    }

    @Autowired
    protected void setUserSettingsService(UserSettingsService userSettingsService) {
        this.userSettingsService = userSettingsService;
    }

    @Autowired
    protected void setMessages(Messages messages) {
        getElement().setAttribute("aria-label", messages.getMessage(getClass(), "drawerHandle.ariaLabel"));
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);

        // the menu opens as wide as the user left it
        if (attachEvent.isInitialAttach()) {
            userSettingsService.load(DRAWER_WIDTH_SETTING)
                    .flatMap(DrawerHandle::parseDrawerWidth)
                    .ifPresent(this::showDrawerWidth);
        }
    }

    // the width comes from the browser, so it is kept within the limits and in steps of a quarter of a rem here too
    private void resizeDrawer(double width) {
        if (!Double.isFinite(width)) {
            return;
        }

        double shown = Math.round(Math.clamp(width, MIN_DRAWER_WIDTH, MAX_DRAWER_WIDTH) * 4) / 4.0;
        showDrawerWidth(shown);
        userSettingsService.save(DRAWER_WIDTH_SETTING, String.valueOf(shown));
    }

    private void showDrawerWidth(double width) {
        layout().getStyle().set(DRAWER_WIDTH, width + "rem");
    }

    private AppLayout layout() {
        return findAncestor(AppLayout.class);
    }

    private static Optional<Double> parseDrawerWidth(String setting) {
        try {
            return Optional.of(Double.parseDouble(setting))
                    .filter(Double::isFinite)
                    .map(width -> Math.clamp(width, MIN_DRAWER_WIDTH, MAX_DRAWER_WIDTH));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * The edge of the main menu dragged to a new width, as the browser reports it at the end of the drag.
     */
    @DomEvent("drawer-resize")
    public static class DrawerResizeEvent extends ComponentEvent<DrawerHandle> {

        private final double width;

        /**
         * Creates the event of the edge of the menu with the width it was dragged to, in rem.
         */
        public DrawerResizeEvent(DrawerHandle source, boolean fromClient, @EventData("event.detail") double width) {
            super(source, fromClient);
            this.width = width;
        }

        /**
         * Returns the width the edge was dragged to, in rem.
         */
        public double getWidth() {
            return width;
        }
    }
}
