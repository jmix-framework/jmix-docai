package com.company.docai.app.ui.component.searchpalette;

import com.google.common.base.Strings;
import com.vaadin.flow.component.AttachEvent;
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
 * The query line of the search palette: a combobox of the rows that hands the palette's keys to the server.
 */
@Tag("docai-search-palette-line")
@JsModule("./src/component/search-palette/docai-search-palette-line.js")
public class SearchPaletteLine extends Component {

    @Nullable
    private Component field;

    /**
     * Sets the text field of the line, which needs no id: the browser gets the field itself.
     */
    public void setField(Component field) {
        this.field = field;

        if (isAttached()) {
            watchField();
        }
    }

    /**
     * Sets the list whose rows the line goes through, which needs an id and must be set before the palette opens.
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
     * Gives the line the focus with the caret after its text, also when focused: the opening selects the whole text.
     */
    public void focusAtEnd() {
        getElement().callJsFunction("focusAtEnd");
    }

    /**
     * Takes over the keys typed into the header field since the palette was asked for; a palette whose opening an Esc
     * cancelled takes nothing.
     *
     * @param opening the number the browser gave the request that opened the palette
     */
    public void takeOver(Component headerField, int opening) {
        getElement().callJsFunction("takeOver", headerField.getElement(), opening);
    }

    /**
     * Adds a listener of the palette's keys: the arrows, Tab, Enter once however long it is held, and Shift+Delete.
     */
    public Registration addKeyListener(ComponentEventListener<KeyEvent> listener) {
        return addListener(KeyEvent.class, listener);
    }

    // a call reaches only the element the browser has now, and a line that comes back to the page is a new one there
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
     * A key the palette handles itself, with the text the line showed at the press, which the field may not have yet.
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
         * Shift+Delete comes as {@code Delete} with Shift.
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
