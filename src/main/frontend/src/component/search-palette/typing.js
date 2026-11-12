/**
 * What the elements of the search palette share about typing: when an input method composes a word, and the hand-over
 * of the keys typed into the header field while the palette comes. The line of the palette asks the header field for
 * them with an event, and the opener of the field answers it.
 */
export const HAND_OVER = 'docai-search-palette-hand-over';

// the element where an input method composes a word now
let composing = null;
let following = false;

// an input method keeps its keys until the word is done: Safari sends the Enter that ends the word after the
// composition, and only the key code 229 tells it
export function composes(event) {
    return event.isComposing || event.keyCode === 229;
}

// once for the page: the opener starts it as it reaches the page, so that a word begun in the header field before the
// palette comes is known when the line takes the keys over
export function followCompositions() {
    if (following) {
        return;
    }

    following = true;

    document.addEventListener('compositionstart', event => {
        composing = event.composedPath()[0];
    }, true);

    document.addEventListener('compositionend', () => {
        composing = null;
    }, true);
}

export function composesIn(element) {
    return composing !== null && element.contains(composing);
}

// the answer of the header field: an Enter it held back for the palette, an Esc that cancelled the opening
export function heldKeys(enter, cancel) {
    return {enter, cancel};
}

// a field without an opener held nothing
export function askHeldKeys(field) {
    const asked = new CustomEvent(HAND_OVER, {detail: heldKeys(false, false)});
    field.dispatchEvent(asked);

    return asked.detail;
}
