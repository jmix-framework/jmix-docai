const OUTSIDE_CLICK_GUARD = 400;

const EASING = 'cubic-bezier(0.32, 0.72, 0, 1)';
const FLIGHT = {duration: 200, easing: EASING};
const UNFOLD = {duration: 160, easing: EASING};
const FOLD = {duration: 120, easing: EASING};
const RETURN = {duration: 180, easing: EASING};

const OVERLAP = 0.55;

// the flight that holds each header field; a palette that opens while the last one flies back takes it over
const flights = new WeakMap();

/**
 * The flight of the search palette, the element of SearchPaletteMotion; without the parts it needs, or with reduced
 * motion, the palette opens and closes as a plain dialog.
 */
class SearchPaletteMotion extends HTMLElement {

    #flight = null;

    static get is() {
        return 'docai-search-palette-motion';
    }

    connectedCallback() {
        this.hidden = true;
    }

    // a dialog that goes without the closed event of its overlay still gives the header field back
    disconnectedCallback() {
        this.#flight?.closed();
    }

    // a palette whose opening an Esc cancelled closes as it comes, before the flight
    open(origin, field, panel) {
        const dialog = this.closest('vaadin-dialog');

        if (!dialog?.opened) {
            return;
        }

        const overlay = dialog.$?.overlay;
        const part = overlay?.$?.overlay;
        const backdrop = overlay?.$?.backdrop;

        if (origin && panel && part && backdrop) {
            this.#flight = new Flight(dialog, overlay, part, backdrop, origin, field, panel);
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

    constructor(dialog, overlay, part, backdrop, origin, field, panel) {
        this.dialog = dialog;
        this.overlay = overlay;
        this.part = part;
        this.backdrop = backdrop;
        this.origin = origin;
        this.field = field;
        this.panel = panel;
    }

    start() {
        const previous = flights.get(this.origin);
        flights.set(this.origin, this);
        previous?.end();

        // a transparent field still takes the focus, which the dialog gives back to it on closing
        this.origin.style.opacity = '0';
        this.#reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;

        // the second click of a double click on the header field lands outside the palette: it must neither close the
        // palette nor take the focus from its line
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

        // the part is centered, so it moves by its center, and its left edge runs straight from the field to its place
        this.#course = {x: center(start) - center(end), y: start.top - end.top, from: start.width, to: end.width};
        this.#flying = this.part.animate([this.flightAt(0), this.flightAt(1)], FLIGHT);

        this.#fading = this.backdrop.animate([{opacity: 0}, {opacity: 1}], {duration: FLIGHT.duration});
        const landing = Math.round(FLIGHT.duration * OVERLAP);
        this.unfold(height, landing);

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

    contentHeight() {
        this.panel.style.removeProperty('height');
        const height = getComputedStyle(this.panel).height;
        this.panel.style.height = '0px';

        return height;
    }

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
            if (this.#request !== null) {
                cancelAnimationFrame(this.#request);
            }

            return;
        }

        if (this.#flying?.playState === 'running') {
            this.turnBack();
            return;
        }

        // read before the unfold is cancelled, which would drop the panel to its pinned 0px
        const height = getComputedStyle(this.panel).height;
        const foldTime = parseFloat(height) > 0 ? FOLD.duration : 0;
        this.#unfolding?.cancel();
        this.panel.style.overflow = 'hidden';
        this.panel.animate([{height}, {height: '0px'}], {...FOLD, fill: 'forwards'});

        const target = this.origin.getBoundingClientRect();
        const returns = hasSize(target);
        const delay = Math.round(foldTime * OVERLAP);
        this.hold(returns ? Math.max(foldTime, delay + RETURN.duration) : foldTime);

        if (returns) {
            this.flyBack(target, delay);
        }
    }

    // a close during the opening sends every stage back from where it is, on the same curve and in the time the opening
    // has run: played backwards, the curve would speed up into the field
    turnBack() {
        const elapsed = this.#flying.currentTime;
        const flown = this.#flying.effect.getComputedTiming().progress ?? 0;
        const shown = this.#fading.effect.getComputedTiming().progress ?? 0;

        // read before the opening lets go: Vaadin's closing animation has just pinned the place of the part and the
        // opacity of the backdrop, so those two come from the progress of the opening
        const stages = [
            [this.part, [this.flightAt(flown), this.flightAt(0)]],
            [this.backdrop, [{opacity: shown}, {opacity: 0}]],
            [this.panel, [{height: getComputedStyle(this.panel).height}, {height: '0px'}]]
        ];

        const field = inputFieldOf(this.field);
        const header = inputFieldOf(this.origin);

        if (field && header) {
            stages.push([field, [frameOf(field), frameOf(header)]]);
        }

        for (const animation of [this.#flying, this.#fading, this.#unfolding, this.#framing]) {
            animation?.cancel();
        }

        this.hold(elapsed);

        for (const [element, keyframes] of stages) {
            element.animate(keyframes, {duration: elapsed, easing: EASING, fill: 'forwards'});
        }
    }

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

    reframe(dropping, timing) {
        const field = inputFieldOf(this.field);
        const header = inputFieldOf(this.origin);

        if (!field || !header) {
            return null;
        }

        const frames = [frameOf(header), frameOf(field)];

        return field.animate(dropping ? frames : frames.reverse(), timing);
    }

    // the dialog stays in its closing state while an animation of its own runs, and the backdrop fades out with it
    hold(duration) {
        this.dialog.style.setProperty('--vaadin-overlay-animation-duration', `${Math.round(duration)}ms`);
    }

    end() {
        if (this.#closing) {
            this.overlay.hidden = true;
        }
    }

    closed() {
        if (flights.get(this.origin) === this) {
            flights.delete(this.origin);
            this.origin.style.removeProperty('opacity');
        }
    }
}

function center(rect) {
    return rect.left + rect.width / 2;
}

function hasSize(rect) {
    return rect.width > 0 && rect.height > 0;
}

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
