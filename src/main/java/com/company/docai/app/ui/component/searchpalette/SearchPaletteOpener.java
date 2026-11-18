package com.company.docai.app.ui.component.searchpalette;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.DomEvent;
import com.vaadin.flow.component.EventData;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.dom.DomListenerRegistration;
import com.vaadin.flow.shared.Registration;
import org.jspecify.annotations.Nullable;

/**
 * Opens the search palette from the header field; an Esc typed while the palette is on its way cancels the opening.
 */
@Tag("docai-search-palette-opener")
@JsModule("./src/component/search-palette/docai-search-palette-opener.js")
public class SearchPaletteOpener extends Component {

    @Nullable
    private Component field;

    /**
     * Sets the field that opens the palette, which needs no id: the browser gets the field itself.
     */
    public void setField(Component field) {
        this.field = field;

        if (isAttached()) {
            watchField();
        }
    }

    /**
     * Adds a listener of the requests to open the palette, which may come over an inert page: the listener checks for
     * dialogs itself.
     */
    public Registration addOpenListener(ComponentEventListener<OpenEvent> listener) {
        // a click right after an Esc comes in the cancel's request, which closes the palette before Flow frees the page
        return ComponentUtil.addListener(this, OpenEvent.class, listener, DomListenerRegistration::allowInert);
    }

    /**
     * Adds a listener of the requests to cancel the opening of the palette.
     */
    public Registration addCancelListener(ComponentEventListener<CancelEvent> listener) {
        // the request comes once the palette is open, which makes the rest of the page inert
        return ComponentUtil.addListener(this, CancelEvent.class, listener, DomListenerRegistration::allowInert);
    }

    // a call reaches only the element the browser has now, and an opener that comes back to the page is a new one there
    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);

        watchField();
    }

    private void watchField() {
        if (field != null) {
            getElement().callJsFunction("watch", field.getElement());
        }
    }

    /**
     * A request to open the palette, with the text the field showed in the browser and the number of the request.
     */
    @DomEvent("docai-search-palette-open")
    public static class OpenEvent extends ComponentEvent<SearchPaletteOpener> {

        private final String text;
        private final int opening;

        /**
         * Creates the request with the text of the field and the number of the request.
         */
        public OpenEvent(SearchPaletteOpener source, boolean fromClient,
                         @EventData("event.detail.text") String text,
                         @EventData("event.detail.opening") int opening) {
            super(source, fromClient);
            this.text = text;
            this.opening = opening;
        }

        /**
         * Returns the text the field showed in the browser.
         */
        public String getText() {
            return text;
        }

        /**
         * Returns the number the browser gave the request.
         */
        public int getOpening() {
            return opening;
        }
    }

    /**
     * A request to cancel the opening of the palette: an Esc typed before the palette appeared.
     */
    @DomEvent("docai-search-palette-cancel")
    public static class CancelEvent extends ComponentEvent<SearchPaletteOpener> {

        /**
         * Creates the request.
         */
        public CancelEvent(SearchPaletteOpener source, boolean fromClient) {
            super(source, fromClient);
        }
    }
}
