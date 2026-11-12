// the keys that the palette handles itself: the browser does nothing with them, so the focus never leaves the line
const KEYS = new Set(['ArrowDown', 'ArrowUp', 'Tab', 'Enter']);

// the keys that edit the line when they come after a click elsewhere in the palette
const EDITING_KEYS = new Set(['Backspace', 'Delete']);

/**
 * The query line of the search palette, the element of the server component SearchPaletteLine. The rows never take
 * the focus, so a screen reader meets the line as a combobox: the text field that the `for` attribute names controls
 * the list that the `list` attribute names, and the row the arrows chose is its active descendant. The arrows, Tab and
 * Enter, and Shift+Delete while a row is chosen, go to the server with the text the line shows at the press, and the
 * browser does nothing with them; an Enter held down goes once. A click on a chip, on the wand or on a cross gives the
 * line the focus back with the caret after its text at once, before the server answers the click. A click elsewhere
 * in the palette may take the focus and select text, as on any page; the next key brings the focus back to the line
 * and acts there, unless it comes with Cmd, Ctrl or Alt. The card of a document opens inside the dialog of the
 * palette, and its keys and clicks stay its own.
 */
class SearchPaletteLine extends HTMLElement {

    #field = null;
    #dialog = null;
    #activeRow = '';

    // the button takes the focus, and the text the opening selected stays selected: a character typed before the
    // server answers would go to the button or replace the text
    #focusAfterButton = event => {
        if (this.#inPalette(event) && event.composedPath().some(node => node.localName === 'vaadin-button')) {
            this.focusAtEnd();
        }
    };

    #sendKey = event => {
        if (this.#handles(event)) {
            event.preventDefault();

            this.dispatchEvent(new CustomEvent('docai-search-palette-key', {
                detail: {key: event.key, shift: event.shiftKey, text: this.#field.inputElement.value}
            }));
        }
    };

    // a key typed after a click elsewhere in the palette; it is taken on its way down, as the dialog moves the focus
    // on Tab while the key bubbles up
    #returnKey = event => {
        if (!this.#inPalette(event) || event.composedPath().includes(this.#field) || composes(event)
                || event.metaKey || event.ctrlKey || event.altKey) {
            return;
        }

        if (this.#handles(event) || EDITING_KEYS.has(event.key) || Array.from(event.key).length === 1) {
            this.#focusAsLeft();
            this.#sendKey(event);
        }
    };

    static get is() {
        return 'docai-search-palette-line';
    }

    get activeRow() {
        return this.#activeRow;
    }

    set activeRow(id) {
        this.#activeRow = id ?? '';
        this.#showActiveRow();
    }

    connectedCallback() {
        // the line shows nothing itself
        this.hidden = true;
        this.#field = fieldOf(this);
        this.#dialog = this.closest('vaadin-dialog');

        if (this.#field) {
            this.#field.addEventListener('keydown', this.#sendKey);
            document.addEventListener('keydown', this.#returnKey, true);
            document.addEventListener('click', this.#focusAfterButton, true);
            // the field makes its native input on its first render
            this.#field.updateComplete.then(() => this.#describe());
        }
    }

    disconnectedCallback() {
        this.#field?.removeEventListener('keydown', this.#sendKey);
        document.removeEventListener('keydown', this.#returnKey, true);
        document.removeEventListener('click', this.#focusAfterButton, true);
        this.#field = null;
        this.#dialog = null;
    }

    // the caret goes after the text also when the line has the focus already: the opening selects the whole text,
    // which the next character would replace. A palette that closes, as when its card leaves for another page, gives
    // the focus back to the header field instead.
    focusAtEnd() {
        const input = this.#field?.inputElement;

        if (input && (!this.#dialog || this.#dialog.opened)) {
            this.#field.focus();
            input.setSelectionRange(input.value.length, input.value.length);
        }
    }

    // the field selects its whole text as it takes the focus; the line goes on as the person left it
    #focusAsLeft() {
        const input = this.#field.inputElement;
        const {selectionStart, selectionEnd, selectionDirection} = input;
        this.#field.focus();
        input.setSelectionRange(selectionStart, selectionEnd, selectionDirection);
    }

    // the nearest dialog of the event is the palette: a card opened over the palette lies inside its dialog
    #inPalette(event) {
        const dialog = event.composedPath().find(node => node.localName === 'vaadin-dialog');

        return dialog !== undefined && dialog === this.#dialog;
    }

    // Shift+Delete removes the chosen row when it is a recent search; without a chosen row it edits the line, and with
    // Ctrl, Alt or Cmd it is a shortcut of the browser. The repeats of an Enter held down would run the next choice.
    #handles(event) {
        const removes = event.key === 'Delete' && event.shiftKey && !event.ctrlKey && !event.altKey && !event.metaKey
            && this.#activeRow !== '';
        const repeats = event.key === 'Enter' && event.repeat;

        return !composes(event) && !repeats && (KEYS.has(event.key) || removes);
    }

    #describe() {
        const input = this.#field?.inputElement;

        if (input) {
            input.setAttribute('role', 'combobox');
            input.setAttribute('aria-autocomplete', 'list');
            input.setAttribute('aria-expanded', 'true');
            input.setAttribute('aria-controls', this.getAttribute('list'));
            this.#showActiveRow();
        }
    }

    #showActiveRow() {
        const input = this.#field?.inputElement;

        if (!input) {
            return;
        }

        if (this.#activeRow) {
            input.setAttribute('aria-activedescendant', this.#activeRow);
        } else {
            input.removeAttribute('aria-activedescendant');
        }
    }
}

// an input method keeps its keys until the word is done: Safari sends the Enter that ends the word after the
// composition, and only the key code 229 tells it
function composes(event) {
    return event.isComposing || event.keyCode === 229;
}

// the field beside the line: a palette that still closes keeps the same ids on the page
function fieldOf(line) {
    const id = line.getAttribute('for');

    return id ? line.parentElement?.querySelector(`#${CSS.escape(id)}`) : null;
}

if (!customElements.get(SearchPaletteLine.is)) {
    customElements.define(SearchPaletteLine.is, SearchPaletteLine);
}
