const OUTSIDE_CLICK_GUARD = 400;

// one curve for every stage: it starts at once and settles softly, so nothing reaches its place at full speed
const EASING = 'cubic-bezier(0.32, 0.72, 0, 1)';
const FLIGHT = {duration: 200, easing: EASING};
const UNFOLD = {duration: 160, easing: EASING};
const FOLD = {duration: 120, easing: EASING};
const RETURN = {duration: 180, easing: EASING};

// the share of a stage after which the next one starts: the stages overlap, so that each gesture reads as one
const OVERLAP = 0.55;

// the flight that holds each header field: a palette that opens while the last one still flies back takes it over
const flights = new WeakMap();

// where an input method composes a word right now: the hand-over leaves an open composition alone
let composing = null;

document.addEventListener('compositionstart', event => {
    composing = event.composedPath()[0];
}, true);

document.addEventListener('compositionend', () => {
    composing = null;
}, true);

/**
 * The flight of the search palette, the element of the server component SearchPaletteMotion. The query line flies
 * from the search field of the header to its place at the top of the window and widens while the backdrop fades in;
 * the panel under it starts to unfold as the line lands, and the line drops the frame of the header field that it wore
 * in flight. Closing runs the other way: the panel folds, the line flies back into the field while it folds and puts
 * the frame on again, and a close during the opening sends every stage back from where it is. The header field stays
 * transparent while the palette is open, so that it seems to fly itself, and the keys typed into it before the palette
 * opened go over to the line, an Enter that the field held back included. The dialog shows and hides itself at once
 * (search-palette.css), and the module holds its closing state as long as the way back takes. Without the parts it
 * needs the palette opens and closes as a plain dialog, and with reduced motion it does so at once.
 */
class SearchPaletteMotion extends HTMLElement {

    #flight = null;

    static get is() {
        return 'docai-search-palette-motion';
    }

    connectedCallback() {
        // the motion shows nothing
        this.hidden = true;
    }

    // a dialog that goes without the closed event of its overlay still gives the header field back
    disconnectedCallback() {
        this.#flight?.closed();
    }

    open(origin, line, panel) {
        const dialog = this.closest('vaadin-dialog');
        const overlay = dialog?.$?.overlay;
        const part = overlay?.$?.overlay;
        const backdrop = overlay?.$?.backdrop;

        if (origin && line) {
            handOver(origin, line);
        }

        // the flight needs every part of the dialog: without one the palette stays a plain dialog
        if (origin && panel && part && backdrop) {
            this.#flight = new Flight(dialog, overlay, part, backdrop, origin, line, panel);
            this.#flight.start();
        }
    }
}

class Flight {

    #reduced = false;
    #started = false;
    #closing = false;
    #request = null;
    #flying = null;
    #fading = null;
    #unfolding = null;
    #framing = null;
    #course = null;

    constructor(dialog, overlay, part, backdrop, origin, line, panel) {
        this.dialog = dialog;
        this.overlay = overlay;
        this.part = part;
        this.backdrop = backdrop;
        this.origin = origin;
        this.line = line;
        this.panel = panel;
    }

    start() {
        const previous = flights.get(this.origin);
        flights.set(this.origin, this);
        previous?.end();

        // a transparent field still takes the focus, which the dialog gives back to it on closing
        this.origin.style.opacity = '0';
        this.#reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;

        // the second click of a double click on the header field lands outside the palette: it neither closes the
        // palette nor takes the focus from its line to the dialog.
        const guard = event => event.preventDefault();
        this.overlay.addEventListener('vaadin-overlay-outside-click', guard);
        this.backdrop.addEventListener('mousedown', guard);

        setTimeout(() => {
            this.overlay.removeEventListener('vaadin-overlay-outside-click', guard);
            this.backdrop.removeEventListener('mousedown', guard);
        }, OUTSIDE_CLICK_GUARD);

        this.overlay.addEventListener('vaadin-overlay-closing', () => this.close(), {once: true});
        this.overlay.addEventListener('vaadin-overlay-closed', () => this.closed(), {once: true});

        if (this.#reduced) {
            return;
        }

        this.panel.style.height = '0px';
        this.panel.style.overflow = 'hidden';

        // Vaadin places and sizes the part in frames that the updates of the dialog ask for, so the flight asks after
        // those updates: its frame comes after Vaadin's and before anything is painted
        Promise.all([this.dialog.updateComplete, this.overlay.updateComplete])
            .then(() => {
                if (!this.#closing) {
                    this.#request = requestAnimationFrame(() => this.fly());
                }
            });
    }

    fly() {
        this.#request = null;
        this.#started = true;

        // the panel unfolds while the part is still narrower than its place, and its content would wrap otherwise
        const height = this.contentHeight();
        const start = this.origin.getBoundingClientRect();
        const end = this.part.getBoundingClientRect();

        if (!hasSize(start)) {
            this.unfold(height, 0);
            return;
        }

        // the part is centered, so it moves by its center: its left edge then runs straight from the field to its place
        this.#course = {x: center(start) - center(end), y: start.top - end.top, from: start.width, to: end.width};
        this.#flying = this.part.animate([this.flightAt(0), this.flightAt(1)], FLIGHT);

        this.#fading = this.backdrop.animate([{opacity: 0}, {opacity: 1}], {duration: FLIGHT.duration});
        const landing = Math.round(FLIGHT.duration * OVERLAP);
        this.unfold(height, landing);

        // the line drops the frame of the header field as it lands, when it becomes the top of the panel
        this.#framing = this.reframe(true, {
            easing: EASING,
            delay: landing,
            duration: FLIGHT.duration - landing,
            fill: 'backwards'
        });
    }

    // worked out from the course, not read from the page: at the closing event Vaadin's closing animations hide the
    // place the part has reached, and the turn-back has to start from there
    flightAt(progress) {
        const {x, y, from, to} = this.#course;
        const rest = 1 - progress;

        return {translate: `${x * rest}px ${y * rest}px`, width: `${from + (to - from) * progress}px`};
    }

    // unlike the part, the panel keeps a height of its own until it is released, so the unfold holds its end
    unfold(height, delay) {
        this.#unfolding = this.panel.animate([{height: '0px'}, {height}], {...UNFOLD, delay, fill: 'forwards'});

        this.#unfolding.finished
            .then(() => this.release(), () => {});
    }

    // the height the content of the panel asks for, in the terms of the box of the panel
    contentHeight() {
        this.panel.style.removeProperty('height');
        const height = getComputedStyle(this.panel).height;
        this.panel.style.height = '0px';

        return height;
    }

    // from now on the panel follows its content without animation
    release() {
        if (!this.#closing) {
            this.panel.style.removeProperty('height');
            this.panel.style.removeProperty('overflow');
            this.#unfolding.cancel();
        }
    }

    close() {
        this.#closing = true;

        if (this.#reduced) {
            return;
        }

        if (!this.#started) {
            // nothing has moved yet, and the dialog closes at once
            if (this.#request !== null) {
                cancelAnimationFrame(this.#request);
            }

            return;
        }

        if (this.#flying?.playState === 'running') {
            this.turnBack();
            return;
        }

        // the panel folds from the height it has, which an unfold that still runs has not reached yet
        const height = getComputedStyle(this.panel).height;
        const foldTime = parseFloat(height) > 0 ? FOLD.duration : 0;
        this.#unfolding?.cancel();
        this.panel.style.overflow = 'hidden';
        this.panel.animate([{height}, {height: '0px'}], {...FOLD, fill: 'forwards'});

        // the window may have changed since the palette opened, so the field is measured again
        const target = this.origin.getBoundingClientRect();
        const returns = hasSize(target);
        const delay = Math.round(foldTime * OVERLAP);
        this.hold(returns ? Math.max(foldTime, delay + RETURN.duration) : foldTime);

        if (returns) {
            this.flyBack(target, delay);
        }
    }

    // a close during the opening sends every stage back from where it is, on the same curve and in the time the opening
    // has run, so that the line slows down into the field as it slowed down towards its place. Played backwards, the
    // curve would speed up into the field instead. The line keeps the place and the frame of the field until the dialog
    // is gone.
    turnBack() {
        const elapsed = this.#flying.currentTime;
        const flown = this.#flying.effect.getComputedTiming().progress ?? 0;
        const shown = this.#fading.effect.getComputedTiming().progress ?? 0;

        // what each stage shows now is read before the opening lets go of it. The closing animation of Vaadin has just
        // pinned the place of the part and the opacity of the backdrop in their computed style, so those two come from
        // the progress of the opening itself.
        const stages = [
            [this.part, [this.flightAt(flown), this.flightAt(0)]],
            [this.backdrop, [{opacity: shown}, {opacity: 0}]],
            [this.panel, [{height: getComputedStyle(this.panel).height}, {height: '0px'}]]
        ];

        const line = inputFieldOf(this.line);
        const header = inputFieldOf(this.origin);

        if (line && header) {
            stages.push([line, [frameOf(line), frameOf(header)]]);
        }

        for (const animation of [this.#flying, this.#fading, this.#unfolding, this.#framing]) {
            animation?.cancel();
        }

        this.hold(elapsed);

        for (const [element, keyframes] of stages) {
            element.animate(keyframes, {duration: elapsed, easing: EASING, fill: 'forwards'});
        }
    }

    // the line flies into the field while the panel folds, and it puts the frame of the field on again on the way. Both
    // are started with the fold and wait for their turn, so that all keep to the closing state that the dialog holds.
    flyBack(target, delay) {
        const current = this.part.getBoundingClientRect();

        this.part.animate([
            {translate: '0px 0px', width: `${current.width}px`},
            {
                translate: `${center(target) - center(current)}px ${target.top - current.top}px`,
                width: `${target.width}px`
            }
        ], {...RETURN, delay, fill: 'forwards'});

        this.reframe(false, {...RETURN, delay, fill: 'both'});
    }

    // the line wears the frame of the header field only while it flies: its border, fill and shadow, but no focus ring
    reframe(dropping, timing) {
        const line = inputFieldOf(this.line);
        const header = inputFieldOf(this.origin);

        if (!line || !header) {
            return null;
        }

        const frames = [frameOf(header), frameOf(line)];

        return line.animate(dropping ? frames : frames.reverse(), timing);
    }

    // the dialog stays in its closing state while an animation of its own runs, and the backdrop fades out with it
    hold(duration) {
        this.dialog.style.setProperty('--vaadin-overlay-animation-duration', `${Math.round(duration)}ms`);
    }

    // a palette that opens while this one still flies back takes the field over at once
    end() {
        if (this.#closing) {
            this.overlay.hidden = true;
        }
    }

    closed() {
        // a palette opened since holds the field
        if (flights.get(this.origin) === this) {
            flights.delete(this.origin);
            this.origin.style.removeProperty('opacity');
        }
    }
}

// the keys typed into the header field after the click belong to the palette, which the server opened with the text of
// the click: the letters replace that text, as typing into the open palette would, and the field gets it back; an Enter
// that the field held back then runs in the line. A palette that an Esc cancelled on its way takes nothing, and the
// server closes it.
function handOver(origin, line) {
    const input = line.inputElement;

    // every palette that opens ends the wait of the field, whatever comes of the keys
    const held = new CustomEvent('docai-search-palette-hand-over', {detail: {enter: false, cancel: false}});
    origin.dispatchEvent(held);

    // the person cancelled the palette or went elsewhere before it opened, or still composes a word, which a move of
    // the text would cut
    if (!input || held.detail.cancel || !origin.contains(document.activeElement) || origin.contains(composing)) {
        return;
    }

    const before = line.value;
    const now = origin.value;

    if (now === before) {
        line.focus();
    } else {
        // the field gets its text back before it loses the focus, and a change event tells Flow, which may have had
        // the typed text from the field
        origin.value = before;
        origin.inputElement?.dispatchEvent(new Event('change', {bubbles: true}));
        line.focus();
        line.value = typed(before, now);
        input.dispatchEvent(new Event('input', {bubbles: true, composed: true}));
    }

    if (held.detail.enter) {
        input.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true}));
    }
}

// what came between the start and the end that stayed: the letters typed, or nothing when letters were only deleted.
// The texts are compared by characters, not by their UTF-16 halves, so that no emoji is cut in two.
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

function center(rect) {
    return rect.left + rect.width / 2;
}

function hasSize(rect) {
    return rect.width > 0 && rect.height > 0;
}

// the box of a text field that draws its frame
function inputFieldOf(field) {
    return field?.shadowRoot?.querySelector('[part~="input-field"]') ?? null;
}

function frameOf(inputField) {
    const style = getComputedStyle(inputField);

    return {borderColor: style.borderColor, backgroundColor: style.backgroundColor, boxShadow: style.boxShadow};
}

if (!customElements.get(SearchPaletteMotion.is)) {
    customElements.define(SearchPaletteMotion.is, SearchPaletteMotion);
}
