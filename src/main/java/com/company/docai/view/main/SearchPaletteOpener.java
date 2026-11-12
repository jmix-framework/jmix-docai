package com.company.docai.view.main;

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

/**
 * Opens the search palette from the search field of the header: the element {@code docai-search-palette-opener} of
 * {@code components/search-palette-opener.js} decides in the browser what opens the palette and sends the server one
 * event with the text of the field. A click on the field, except on its wand and its clear button, ⌘K or Ctrl+K
 * anywhere on the page, and the first character or Enter typed in the field open the palette; the focus alone, as
 * after Tab, opens nothing. The keys typed while the palette is on its way stay with the field, and the palette takes
 * them over as it opens; an Esc among them cancels the opening, with a second event. It shows nothing. The main view
 * declares it in its descriptor and names the field.
 */
@Tag("docai-search-palette-opener")
@JsModule("./components/search-palette-opener.js")
public class SearchPaletteOpener extends Component {

    /**
     * Sets the field that opens the palette; the browser looks it up by its id in the layout that holds the opener, so
     * the field needs one. The browser reads it once, when the opener reaches the page.
     */
    public void setField(Component field) {
        getElement().setAttribute("for", field.getId().orElseThrow());
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
