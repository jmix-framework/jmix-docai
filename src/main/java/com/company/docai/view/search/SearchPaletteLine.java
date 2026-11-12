package com.company.docai.view.search;

import com.google.common.base.Strings;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.DomEvent;
import com.vaadin.flow.component.EventData;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.shared.Registration;
import org.jspecify.annotations.Nullable;

/**
 * The query line of the search palette in the browser: the element {@code docai-search-palette-line} of
 * {@code components/search-palette-line.js} makes the text field of the line a combobox of the list of rows, names the
 * row the arrows chose to a screen reader, gives the line the focus back with the caret after its text, and hands the
 * keys that the palette handles itself over to the server, so that the browser does nothing with them. After a click
 * elsewhere in the palette, the first key brings the focus back to the line and acts there. It shows nothing. The
 * palette declares it in its descriptor beside the field and names the field and the list.
 */
@Tag("docai-search-palette-line")
@JsModule("./components/search-palette-line.js")
public class SearchPaletteLine extends Component {

    /**
     * Sets the text field of the line; the browser looks it up by its id in the layout that holds the line, so the
     * field needs one. The browser reads it once, when the line reaches the page, so it must be set before the palette
     * opens.
     */
    public void setField(Component field) {
        getElement().setAttribute("for", field.getId().orElseThrow());
    }

    /**
     * Sets the list whose rows the line goes through; the browser names it by its id, so the list needs one. The
     * browser reads it once, when the line reaches the page, so it must be set before the palette opens.
     */
    public void setList(Component list) {
        getElement().setAttribute("list", list.getId().orElseThrow());
    }

    /**
     * Names the row the arrows chose to a screen reader, by the id of the row; null or an empty id names none.
     */
    public void setActiveRow(@Nullable String rowId) {
        getElement().setProperty("activeRow", Strings.nullToEmpty(rowId));
    }

    /**
     * Gives the line the focus with the caret after its text, also when the line has the focus already: the opening
     * selects the whole text, which the next character would replace. A palette that is closing in the browser leaves
     * the focus to the header field it gives it back to.
     */
    public void focusAtEnd() {
        getElement().callJsFunction("focusAtEnd");
    }

    /**
     * Adds a listener of the keys that the palette handles itself: the arrows up and down, Tab, Enter, once however
     * long it is held, and Shift+Delete without other modifiers while a row is selected.
     */
    public Registration addKeyListener(ComponentEventListener<KeyEvent> listener) {
        return addListener(KeyEvent.class, listener);
    }

    /**
     * A key of the line that the palette handles itself, with the text the line showed at the press: the browser
     * sends the text after a pause, so the field may not have it yet.
     */
    @DomEvent("docai-search-palette-key")
    public static class KeyEvent extends ComponentEvent<SearchPaletteLine> {

        private final String key;
        private final boolean shift;
        private final String text;

        /**
         * Creates the event of the key with the state of Shift and the text of the line at the press.
         */
        public KeyEvent(SearchPaletteLine source, boolean fromClient,
                        @EventData("event.detail.key") String key,
                        @EventData("event.detail.shift") boolean shift,
                        @EventData("event.detail.text") String text) {
            super(source, fromClient);
            this.key = key;
            this.shift = shift;
            this.text = text;
        }

        /**
         * Returns the key as the browser names it, such as {@code Enter}; Shift+Delete comes as {@code Delete} with
         * Shift.
         */
        public String getKey() {
            return key;
        }

        /**
         * Returns whether Shift was held.
         */
        public boolean isShift() {
            return shift;
        }

        /**
         * Returns the text of the line at the press.
         */
        public String getText() {
            return text;
        }
    }
}
