/**
 * What the elements of the search palette share about typing: input methods and the hand-over of the keys typed into
 * the header field.
 */
export const HAND_OVER = 'docai-search-palette-hand-over';

let composing = null;
let following = false;

// an input method keeps its keys until the word is done: Safari sends the Enter that ends the word after the
// composition, and only the key code 229 tells it
export function composes(event) {
    return event.isComposing || event.keyCode === 229;
}

// once for the page, so that a word begun in the header field before the palette comes is known at the hand-over
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

export function heldKeys(enter, cancel) {
    return {enter, cancel};
}

// the palette asks for the keys of its opening; a field without an opener held nothing
export function askHeldKeys(field, opening) {
    const asked = new CustomEvent(HAND_OVER, {detail: {opening, ...heldKeys(false, false)}});
    field.dispatchEvent(asked);

    return asked.detail;
}
