// the elements whose keys belong to them: an open overlay, a modal and a dialog with its content
const OVERLAYS = ':popover-open, :modal, vaadin-dialog';

// a palette that has not come by then does not come: the field takes its keys back and drops a held Enter
const WAIT = 3000;

/**
 * Opens the search palette from the search field of the header, the element of the server component
 * SearchPaletteOpener. A click on the field, except on its wand and its clear button, ⌘K or Ctrl+K anywhere on the
 * page, and the first character or Enter typed in the field send the server one event with the text of the field; the
 * focus alone, as after Tab, opens nothing. As Flow's shortcuts do, the keys match the K by its place on the keyboard,
 * so that any layout works, and do nothing inside a dialog or another overlay. The keys typed while the palette is on
 * its way belong to it: the characters go into the field, which the palette takes them from, and the field holds an
 * Enter back until the palette asks for it. An Esc typed meanwhile cancels the opening: the field keeps the text it
 * had, and the server gets a second event, which closes the palette.
 */
class SearchPaletteOpener extends HTMLElement {

    #field = null;
    // the palette is on its way with the text the field had then; an Enter typed meanwhile waits for it, and an Esc
    // cancels it
    #opening = false;
    #text = '';
    #enter = false;
    #cancelled = false;
    #timer = null;

    #openByShortcut = event => {
        if (this.#field && isShortcut(event) && !inOverlay(event)) {
            event.preventDefault();
            this.#field.focus();
            this.#open();
        }
    };

    #openByClick = event => {
        const path = event.composedPath();

        // the wand switches the mode and the clear button empties the field
        if (!path.slice(0, path.indexOf(this.#field)).some(isFieldButton)) {
            this.#open();
        }
    };

    // the keys of the focused wand are its own, and a key held down opens nothing again
    #openByKey = event => {
        if (event.defaultPrevented || composes(event) || !this.#fromInput(event)) {
            return;
        }

        if (event.key === 'Enter') {
            // the field searches nothing itself
            event.preventDefault();

            if (event.repeat) {
                return;
            }

            if (!this.#opening) {
                this.#open();
            } else if (!this.#cancelled) {
                this.#enter = true;
            }
        } else if (!this.#opening && !event.repeat && typesCharacter(event)) {
            this.#open();
        }
    };

    // taken on the way down and stopped there: the field's own listener would clear its text on Esc
    #cancelByEscape = event => {
        if (!this.#opening || event.key !== 'Escape' || composes(event) || !this.#fromInput(event)) {
            return;
        }

        event.preventDefault();
        event.stopPropagation();

        if (this.#cancelled) {
            return;
        }

        this.#cancelled = true;
        this.#enter = false;

        // the characters typed on the way belonged to the palette
        if (this.#field.value !== this.#text) {
            this.#field.value = this.#text;
            this.#field.inputElement?.dispatchEvent(new Event('change', {bubbles: true}));
        }

        this.dispatchEvent(new CustomEvent('docai-search-palette-cancel'));
    };

    // the palette takes the characters from the field as it opens and asks for the Enter; a cancelled one takes nothing
    #handOver = event => {
        event.detail.enter = this.#enter;
        event.detail.cancel = this.#cancelled;
        this.#settle();
    };

    // a palette that does not come leaves the keys to the field once the focus goes or the wait is over
    #settle = () => {
        clearTimeout(this.#timer);
        this.#timer = null;
        this.#opening = false;
        this.#enter = false;
        this.#cancelled = false;
    };

    static get is() {
        return 'docai-search-palette-opener';
    }

    connectedCallback() {
        // the opener shows nothing
        this.hidden = true;
        this.#field = fieldOf(this);
        document.addEventListener('keydown', this.#openByShortcut);

        if (this.#field) {
            this.#field.addEventListener('click', this.#openByClick);
            this.#field.addEventListener('keydown', this.#openByKey);
            this.#field.addEventListener('keydown', this.#cancelByEscape, true);
            this.#field.addEventListener('focusout', this.#settle);
            this.#field.addEventListener('docai-search-palette-hand-over', this.#handOver);
            // the field makes its native input on its first render; a screen reader then hears that it opens a dialog
            this.#field.updateComplete.then(() => this.#field?.inputElement?.setAttribute('aria-haspopup', 'dialog'));
        }
    }

    disconnectedCallback() {
        this.#settle();
        document.removeEventListener('keydown', this.#openByShortcut);
        this.#field?.removeEventListener('click', this.#openByClick);
        this.#field?.removeEventListener('keydown', this.#openByKey);
        this.#field?.removeEventListener('keydown', this.#cancelByEscape, true);
        this.#field?.removeEventListener('focusout', this.#settle);
        this.#field?.removeEventListener('docai-search-palette-hand-over', this.#handOver);
        this.#field = null;
    }

    // a text the field selects as it takes the focus would give way to the first character, and the palette could not
    // tell the characters typed on the way from it: they go after the text
    #open() {
        // a second click or Cmd+K on the way would take the characters typed since the first one for the field's text
        if (this.#opening) {
            return;
        }

        const input = this.#field.inputElement;
        input?.setSelectionRange(input.value.length, input.value.length);
        clearTimeout(this.#timer);
        this.#opening = true;
        this.#cancelled = false;
        this.#text = this.#field.value ?? '';
        this.#timer = setTimeout(this.#settle, WAIT);
        this.dispatchEvent(new CustomEvent('docai-search-palette-open', {detail: {text: this.#text}}));
    }

    #fromInput(event) {
        return event.composedPath()[0] === this.#field.inputElement;
    }
}

// Cmd or Ctrl with K and no other modifier; a key that the page has taken already, or one held down, opens nothing
function isShortcut(event) {
    return event.code === 'KeyK' && event.metaKey !== event.ctrlKey && !event.altKey && !event.shiftKey
        && !event.repeat && !event.defaultPrevented;
}

function inOverlay(event) {
    return event.composedPath()
        .some(node => node instanceof Element && node.matches(OVERLAYS));
}

// the wand is a button of the field, and the clear button a part of its shadow DOM
function isFieldButton(node) {
    return node instanceof Element && (node.localName === 'vaadin-button' || node.part?.contains('clear-button'));
}

// an input method keeps its keys until the word is done: Safari sends the Enter that ends the word after the
// composition, and only the key code 229 tells it
function composes(event) {
    return event.isComposing || event.keyCode === 229;
}

// Cmd and Ctrl make a shortcut of a key, except Ctrl with Alt, which AltGr gives on Windows
function typesCharacter(event) {
    return Array.from(event.key).length === 1 && !event.metaKey
        && (!event.ctrlKey || event.getModifierState('AltGraph'));
}

// the field beside the opener in the header
function fieldOf(opener) {
    const id = opener.getAttribute('for');

    return id ? opener.parentElement?.querySelector(`#${CSS.escape(id)}`) : null;
}

if (!customElements.get(SearchPaletteOpener.is)) {
    customElements.define(SearchPaletteOpener.is, SearchPaletteOpener);
}
