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
 * Opens the search palette from the search field of the header: the element {@code docai-search-palette-opener} of
 * {@code src/component/search-palette/docai-search-palette-opener.js} decides in the browser what opens the palette and
 * sends the server one event with the text of the field. A click on the field, except on its wand and its clear button,
 * ⌘K or Ctrl+K anywhere on the page, and the first character or Enter typed in the field open the palette; the focus
 * alone, as after Tab, opens nothing. The keys typed while the palette is on its way stay with the field, and the
 * palette takes them over as it opens; an Esc among them cancels the opening, with a second event. It shows nothing.
 * The main view declares it in its descriptor and names the field.
 */
@Tag("docai-search-palette-opener")
@JsModule("./src/component/search-palette/docai-search-palette-opener.js")
public class SearchPaletteOpener extends Component {

    @Nullable
    private Component field;

    /**
     * Sets the field that opens the palette. The browser gets the field itself whenever the opener reaches the page,
     * so the field needs no id and may stand anywhere on the page.
     */
    public void setField(Component field) {
        this.field = field;

        if (isAttached()) {
            watchField();
        }
    }

    /**
     * Adds a listener of the requests to open the palette.
     */
    public Registration addOpenListener(ComponentEventListener<OpenEvent> listener) {
        return addListener(OpenEvent.class, listener);
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
     * A request to open the palette, with the text the field showed in the browser: the field sends its text to the
     * server only when the person leaves it, so the server may not have it yet.
     */
    @DomEvent("docai-search-palette-open")
    public static class OpenEvent extends ComponentEvent<SearchPaletteOpener> {

        private final String text;

        /**
         * Creates the request with the text of the field.
         */
        public OpenEvent(SearchPaletteOpener source, boolean fromClient,
                         @EventData("event.detail.text") String text) {
            super(source, fromClient);
            this.text = text;
        }

        /**
         * Returns the text the field showed in the browser.
         */
        public String getText() {
            return text;
        }
    }

    /**
     * A request to cancel the opening of the palette: an Esc typed into the field after the request to open it and
     * before the palette appeared.
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
