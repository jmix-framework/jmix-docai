import { composes, followCompositions, HAND_OVER, heldKeys } from './typing.js';

const OVERLAYS = ':popover-open, :modal, vaadin-dialog';

// a palette that has not come by then does not come: the field takes its keys back and drops a held Enter
const WAIT = 3000;

const SHORTCUTS = 'Meta+K Control+K';

/**
 * Opens the search palette from the search field of the header, the element of SearchPaletteOpener; the keys typed
 * while the palette is on its way belong to it. An Esc typed meanwhile cancels the opening: the field gets back the
 * text it had and opens the next palette at once, and the server gets a second event, which closes the palette. The
 * next Esc is the field's own, which clears it.
 */
class SearchPaletteOpener extends HTMLElement {

    #field = null;
    // the last opening and the one on its way, 0 when none is, with the text the field had then
    #openings = 0;
    #opening = 0;
    #text = '';
    #enter = false;
    #timer = null;
    // the openings an Esc cancelled whose palettes may still come
    #cancelled = new Set();

    #openByShortcut = event => {
        if (this.#field && isShortcut(event) && !inOverlay(event)) {
            event.preventDefault();
            this.#field.focus();
            this.#open();
        }
    };

    #openByClick = event => {
        const path = event.composedPath();

        if (!path.slice(0, path.indexOf(this.#field)).some(isFieldButton)) {
            this.#open();
        }
    };

    #openByKey = event => {
        if (event.defaultPrevented || composes(event) || !this.#fromInput(event)) {
            return;
        }

        if (event.key === 'Enter') {
            event.preventDefault();

            if (event.repeat) {
                return;
            }

            if (!this.#opening) {
                this.#open();
            } else {
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

        if (this.#field.value !== this.#text) {
            this.#field.value = this.#text;
            this.#field.inputElement?.dispatchEvent(new Event('change', {bubbles: true}));
        }

        this.#cancelled.add(this.#opening);
        this.#settle();
        this.dispatchEvent(new CustomEvent('docai-search-palette-cancel'));
    };

    // the palette names its opening as it comes, and the palettes come in the order of their openings
    #handOver = event => {
        const opening = event.detail.opening;
        const cancelled = this.#cancelled.has(opening);

        for (const each of this.#cancelled) {
            if (each <= opening) {
                this.#cancelled.delete(each);
            }
        }

        if (cancelled) {
            Object.assign(event.detail, heldKeys(false, true));
        } else {
            Object.assign(event.detail, heldKeys(this.#enter, false));
            this.#settle();
        }
    };

    #settle = () => {
        clearTimeout(this.#timer);
        this.#timer = null;
        this.#opening = 0;
        this.#enter = false;
    };

    static get is() {
        return 'docai-search-palette-opener';
    }

    connectedCallback() {
        this.hidden = true;
        followCompositions();
        document.addEventListener('keydown', this.#openByShortcut);
        this.#listen();
    }

    disconnectedCallback() {
        this.#settle();
        document.removeEventListener('keydown', this.#openByShortcut);
        this.#unlisten();
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

    #listen() {
        const field = this.#field;

        if (!field) {
            return;
        }

        field.addEventListener('click', this.#openByClick);
        field.addEventListener('keydown', this.#openByKey);
        field.addEventListener('keydown', this.#cancelByEscape, true);
        field.addEventListener('focusout', this.#settle);
        field.addEventListener(HAND_OVER, this.#handOver);
        // the field makes its native input on its first render
        field.updateComplete.then(() => {
            field.inputElement?.setAttribute('aria-haspopup', 'dialog');
            field.inputElement?.setAttribute('aria-keyshortcuts', SHORTCUTS);
        });
    }

    #unlisten() {
        const field = this.#field;

        if (!field) {
            return;
        }

        field.removeEventListener('click', this.#openByClick);
        field.removeEventListener('keydown', this.#openByKey);
        field.removeEventListener('keydown', this.#cancelByEscape, true);
        field.removeEventListener('focusout', this.#settle);
        field.removeEventListener(HAND_OVER, this.#handOver);
    }

    // the characters typed on the way go after the text: a text selected as the field takes the focus would give way to
    // the first of them
    #open() {
        // a second click or Cmd+K on the way would take the characters typed since the first one for the field's text
        if (this.#opening) {
            return;
        }

        const input = this.#field.inputElement;
        input?.setSelectionRange(input.value.length, input.value.length);
        clearTimeout(this.#timer);
        this.#opening = ++this.#openings;
        this.#text = this.#field.value ?? '';
        this.#timer = setTimeout(this.#settle, WAIT);

        this.dispatchEvent(new CustomEvent('docai-search-palette-open', {
            detail: {text: this.#text, opening: this.#opening}
        }));
    }

    #fromInput(event) {
        return event.composedPath()[0] === this.#field.inputElement;
    }
}

// the K by its place on the keyboard, as Flow's shortcuts match it, so that any layout works
function isShortcut(event) {
    return event.code === 'KeyK' && event.metaKey !== event.ctrlKey && !event.altKey && !event.shiftKey
        && !event.repeat && !event.defaultPrevented;
}

function inOverlay(event) {
    return event.composedPath()
        .some(node => node instanceof Element && node.matches(OVERLAYS));
}

function isFieldButton(node) {
    return node instanceof Element && (node.localName === 'vaadin-button' || node.part?.contains('clear-button'));
}

// Cmd and Ctrl make a shortcut of a key, except Ctrl with Alt, which AltGr gives on Windows
function typesCharacter(event) {
    return Array.from(event.key).length === 1 && !event.metaKey
        && (!event.ctrlKey || event.getModifierState('AltGraph'));
}

if (!customElements.get(SearchPaletteOpener.is)) {
    customElements.define(SearchPaletteOpener.is, SearchPaletteOpener);
}
