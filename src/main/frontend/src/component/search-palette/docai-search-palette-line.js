import { askHeldKeys, composes, composesIn } from './typing.js';

const KEYS = new Set(['ArrowDown', 'ArrowUp', 'Tab', 'Enter']);

const EDITING_KEYS = new Set(['Backspace', 'Delete']);

/**
 * The query line of the search palette, the element of SearchPaletteLine: a combobox for a screen reader that sends the
 * keys the palette handles to the server with the text it shows.
 */
class SearchPaletteLine extends HTMLElement {

    #field = null;
    #dialog = null;
    #activeRow = '';

    // a button takes the focus: a character typed before the server answers would go to it or replace the selection
    #focusAfterButton = event => {
        if (this.#inPalette(event) && event.composedPath().some(node => node.localName === 'vaadin-button')) {
            this.focusAtEnd();
        }
    };

    #sendKey = event => {
        if (this.#handles(event)) {
            event.preventDefault();
            this.#send(event.key, event.shiftKey);
        }
    };

    // taken on its way down: the dialog moves the focus on Tab while the key bubbles up
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
        this.hidden = true;
        this.#dialog = this.closest('vaadin-dialog');
        this.#listen();
    }

    disconnectedCallback() {
        this.#unlisten();
        this.#dialog = null;
    }

    watch(field) {
        if (field === this.#field) {
            return;
        }

        this.#unlisten();
        this.#field = field ?? null;

        if (this.isConnected) {
            this.#listen();
        }
    }

    // also when the line has the focus: the opening selects the whole text, which the next character would replace
    focusAtEnd() {
        const input = this.#field?.inputElement;

        if (input && (!this.#dialog || this.#dialog.opened)) {
            this.#field.focus();
            input.setSelectionRange(input.value.length, input.value.length);
        }
    }

    // the keys typed into the header field after the click belong to the palette, a held-back Enter too; a palette that
    // an Esc cancelled on its way takes nothing and closes in this very task, before the browser paints it
    takeOver(headerField, opening) {
        // Flow sends null for a header field that is not on the page
        if (!headerField) {
            return;
        }

        // every palette that opens ends the wait of the header field, whatever comes of the keys
        const held = askHeldKeys(headerField, opening);
        const input = this.#field?.inputElement;

        if (held.cancel) {
            // Flow removes the dialog on its closed event, which vaadin-dialog fires also for one opened in this task
            if (this.#dialog) {
                this.#dialog.opened = false;
            }

            return;
        }

        // gone elsewhere before the palette opened, or still composing a word, which a move of the text would cut
        if (!input || !headerField.contains(document.activeElement) || composesIn(headerField)) {
            return;
        }

        const before = this.#field.value;
        const now = headerField.value;

        if (now === before) {
            this.#field.focus();
        } else {
            // the header field gets its text back before it loses the focus, and a change event tells Flow, which may
            // have had the typed text from the field
            headerField.value = before;
            headerField.inputElement?.dispatchEvent(new Event('change', {bubbles: true}));
            this.#field.focus();
            this.#field.value = typed(before, now);
            input.dispatchEvent(new Event('input', {bubbles: true, composed: true}));
        }

        if (held.enter) {
            this.#send('Enter', false);
        }
    }

    #listen() {
        if (!this.#field) {
            return;
        }

        this.#field.addEventListener('keydown', this.#sendKey);
        document.addEventListener('keydown', this.#returnKey, true);
        document.addEventListener('click', this.#focusAfterButton, true);
        // the field makes its native input on its first render
        this.#field.updateComplete.then(() => this.#describe());
    }

    #unlisten() {
        this.#field?.removeEventListener('keydown', this.#sendKey);
        document.removeEventListener('keydown', this.#returnKey, true);
        document.removeEventListener('click', this.#focusAfterButton, true);
    }

    #send(key, shift) {
        this.dispatchEvent(new CustomEvent('docai-search-palette-key', {
            detail: {key, shift, text: this.#field.inputElement.value}
        }));
    }

    // until the server turns autoselect off after the opening, the field selects its whole text as it takes the focus
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

    // Shift+Delete removes the chosen recent search; the repeats of an Enter held down would run the next choice
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

// the letters typed between the start and the end that stayed, by characters, not UTF-16 halves, so no emoji is cut
function typed(before, now) {
    const was = Array.from(before);
    const is = Array.from(now);
    let start = 0;

    while (start < was.length && start < is.length && was[start] === is[start]) {
        start++;
    }

    let end = 0;

    while (end < was.length - start && end < is.length - start
            && was[was.length - 1 - end] === is[is.length - 1 - end]) {
        end++;
    }

    return is.slice(start, is.length - end).join('');
}

if (!customElements.get(SearchPaletteLine.is)) {
    customElements.define(SearchPaletteLine.is, SearchPaletteLine);
}
