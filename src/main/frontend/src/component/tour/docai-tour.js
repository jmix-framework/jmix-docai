const COOKIE = 'DOCAI_TOUR';
const YEAR_IN_SECONDS = 31536000;
const FALLBACK_MS = 1500;
const REVEAL_FALLBACK_MS = 1000;
const GLAD_MS = 1200;
const MOVE_MS = 500;
const NUDGE_MS = 320;
const NUDGE_DELAY_MS = 200;
const SPOT_CHECK_MS = 200;
const SPOT_LATE_CHECK_MS = 700;
const SPOT_SAMPLES = [0.05, 0.35, 0.65, 0.95];
const CONTROLS = 'a[href], button, input, select, textarea, [role="button"], [role="link"], [role="tab"], '
        + '[role="menuitem"], vaadin-button, vaadin-text-field, vaadin-text-area, vaadin-combo-box, vaadin-select, '
        + 'vaadin-checkbox, vaadin-date-picker, vaadin-menu-bar, vaadin-tab';
const OVERLAYS = 'vaadin-dialog, jmix-side-dialog, vaadin-confirm-dialog, vaadin-popover, vaadin-tooltip, '
        + 'vaadin-notification-container, vaadin-notification-card';
const ROLL_IN_END = new Set(['tour-settle', 'tour-fade-in']);
const LEAVE_END = new Set(['tour-leave', 'tour-leave-left', 'tour-fade-out']);

/**
 * Doc on the page, the element of TourPanel: writes the tour cookie, plays the one-time motions and takes the spot
 * the dock asks for while his corner is taken.
 */
class Tour extends HTMLElement {

    #reduced = matchMedia('(prefers-reduced-motion: reduce)');
    #timer = null;
    #lastRect = null;
    #remember = () => this.#lastRect = this.getBoundingClientRect();
    #barHeight = 0;
    #bar = new ResizeObserver(([entry]) => this.#followBar(entry.target.offsetHeight));
    #spotTimer = null;
    #lateSpotTimer = null;
    #pageChanges = new MutationObserver(() => this.#checkSpotSoon());
    #onResize = () => {
        this.#remember();
        this.#checkSpotSoon();
    };
    #viewport = new ResizeObserver(this.#onResize);
    #onPageMotion = event => {
        if (!this.contains(event.target)) {
            this.#checkSpotSoon();
        }
    };

    static get is() {
        return 'docai-tour';
    }

    static get observedAttributes() {
        return ['dock', 'spot', 'expanded'];
    }

    connectedCallback() {
        this.#placeSpot();
        requestAnimationFrame(this.#remember);
        this.#viewport.observe(document.documentElement);
        window.addEventListener('transitionend', this.#onPageMotion, true);
        window.addEventListener('animationend', this.#onPageMotion, true);
        this.#pageChanges.observe(document.body,
                {childList: true, subtree: true, attributes: true, attributeFilter: ['opened', 'hidden']});
        customElements.whenDefined('vaadin-app-layout').then(() => this.#watchBar());
    }

    disconnectedCallback() {
        clearTimeout(this.#timer);
        clearTimeout(this.#spotTimer);
        clearTimeout(this.#lateSpotTimer);
        this.#viewport.disconnect();
        window.removeEventListener('transitionend', this.#onPageMotion, true);
        window.removeEventListener('animationend', this.#onPageMotion, true);
        this.#pageChanges.disconnect();
        this.#bar.disconnect();
    }

    #watchBar() {
        // a narrow layout moves its header to the bottom of the screen, and Doc stands above it
        const bar = document.querySelector('vaadin-app-layout')?.shadowRoot?.querySelector('[part~="navbar-bottom"]');

        if (bar && this.isConnected) {
            this.#bar.observe(bar);
        }
    }

    #followBar(height) {
        this.#barHeight = height;
        this.style.setProperty('--tour-bottom-bar', `${height}px`);
        this.#checkSpotSoon();
    }

    #checkSpotSoon() {
        clearTimeout(this.#spotTimer);
        clearTimeout(this.#lateSpotTimer);
        this.#spotTimer = setTimeout(() => this.#placeSpot(), SPOT_CHECK_MS);
        // panels slide inside their shadow trees and tell nothing outside: a second look sees where they ended
        this.#lateSpotTimer = setTimeout(() => this.#placeSpot(), SPOT_LATE_CHECK_MS);
    }

    #placeSpot() {
        if (!this.isConnected) {
            return;
        }

        const dock = this.getAttribute('dock') || 'right';
        const spot = dock !== 'right' && this.#cornerTaken() ? dock : 'right';

        if (this.getAttribute('spot') !== spot) {
            this.setAttribute('spot', spot);
        }
    }

    #cornerTaken() {
        const edge = parseFloat(getComputedStyle(this).getPropertyValue('--tour-edge')) || 0;
        const size = this.offsetWidth;
        const right = document.documentElement.clientWidth - edge;
        const bottom = document.documentElement.clientHeight - edge - this.#barHeight;

        return SPOT_SAMPLES.some(dx => SPOT_SAMPLES.some(dy =>
                this.#takenAt(right - size * (1 - dx), bottom - size * (1 - dy))));
    }

    #takenAt(x, y) {
        const element = document.elementsFromPoint(x, y)
                .find(candidate => !this.contains(candidate) && !this.#passing(candidate));

        if (!element) {
            return false;
        }

        // a control Doc would cover, or a side panel that would hide him
        return element.closest(OVERLAYS) != null || element.closest(CONTROLS) != null;
    }

    #passing(element) {
        // notifications, hints and modal windows come and go over the whole page, so Doc looks past them
        const overlay = element.closest(OVERLAYS) ?? (element.matches(':popover-open') ? element : null);

        return overlay != null && !overlay.hasAttribute('modeless');
    }

    attributeChangedCallback(name, previous, current) {
        if (!this.isConnected) {
            return;
        }

        if (name === 'expanded') {
            if (previous !== null && current === null) {
                this.#nudge();
            }
        } else if (name === 'dock') {
            this.#placeSpot();
            this.#checkSpotSoon();
        } else if (previous !== null && previous !== current) {
            this.#move();
        }
    }

    writeState(value) {
        const path = this.getAttribute('cookie-path') || '/';
        const secure = this.hasAttribute('cookie-secure') ? '; Secure' : '';
        document.cookie = `${COOKIE}=${value}; Path=${path}; Max-Age=${YEAR_IN_SECONDS}; SameSite=Lax${secure}`;
    }

    revealHint(hint, dialog) {
        // a hint that left the page before the response has nothing to reveal
        if (!hint) {
            return;
        }

        const observer = new MutationObserver(() => {
            if (!dialog.hasAttribute('opening')) {
                reveal();
            }
        });

        const reveal = () => {
            observer.disconnect();
            clearTimeout(timer);
            window.visualViewport?.dispatchEvent(new Event('resize'));
            hint.toggleAttribute('waiting', false);
        };

        // a popover keeps the place it took while the window still grew: the hint waits unseen and is placed again
        hint.toggleAttribute('waiting', true);
        // armed first, so a failure below still reveals the hint
        const timer = setTimeout(reveal, REVEAL_FALLBACK_MS);

        requestAnimationFrame(() => {
            if (dialog.hasAttribute('opening')) {
                observer.observe(dialog, {attributes: true, attributeFilter: ['opening']});
            } else {
                reveal();
            }
        });
    }

    play(motion, delay) {
        clearTimeout(this.#timer);

        // a Doc about to roll in stays off the page until he does
        if (motion === 'roll-in') {
            this.toggleAttribute('gone', true);
        }

        this.#timer = setTimeout(() => this.#start(motion), delay || 0);
    }

    #start(motion) {
        if (motion === 'roll-in') {
            this.toggleAttribute('gone', false);
        }

        this.setAttribute('motion', motion);
        requestAnimationFrame(this.#remember);

        if (motion === 'glad') {
            this.#timer = setTimeout(() => this.#stop('glad'), GLAD_MS);
        } else if (motion === 'leave') {
            // a roll-in may follow before the leave ends, and Doc stays then
            this.#afterAnimation(LEAVE_END, () => {
                if (this.getAttribute('motion') === 'leave') {
                    this.removeAttribute('motion');
                    this.toggleAttribute('gone', true);
                }
            });
        } else {
            this.#afterAnimation(ROLL_IN_END, () => this.#stop(motion));
        }
    }

    #stop(motion) {
        if (this.getAttribute('motion') === motion) {
            this.removeAttribute('motion');
        }
    }

    #afterAnimation(names, then) {
        let finished = false;

        const finish = event => {
            if (finished || (event && !names.has(event.animationName))) {
                return;
            }

            finished = true;
            clearTimeout(fallback);
            this.removeEventListener('animationend', finish);
            then();
        };

        // the timer ends a motion whose animation never runs
        const fallback = setTimeout(() => finish(null), FALLBACK_MS);
        this.addEventListener('animationend', finish);
    }

    #nudge() {
        if (this.#reduced.matches) {
            return;
        }

        this.querySelector('.tour-doc')?.animate([{scale: 1}, {scale: 1.12}, {scale: 1}],
                {duration: NUDGE_MS, delay: NUDGE_DELAY_MS, easing: 'ease-out'});
    }

    #move() {
        // the new spot is already in the layout: Doc starts where he stood and rolls over
        const first = this.#lastRect;
        const last = this.getBoundingClientRect();
        this.#lastRect = last;

        if (!first || first.width === 0 || last.width === 0) {
            return;
        }

        if (this.#reduced.matches) {
            this.animate([{opacity: 0}, {opacity: 1}], {duration: 150});
            return;
        }

        const dx = first.left - last.left;
        const dy = first.top - last.top;
        this.toggleAttribute('moving', true);

        const move = this.animate([{translate: `${dx}px ${dy}px`}, {translate: '0 0'}],
                {duration: MOVE_MS, easing: 'ease-in-out'});

        this.querySelector('.tour-doc')?.animate([{rotate: '0turn'}, {rotate: dx > 0 ? '-1turn' : '1turn'}],
                {duration: MOVE_MS, easing: 'ease-in-out'});

        move.finished
                .catch(() => null)
                .then(() => this.toggleAttribute('moving', false));
    }
}

if (!customElements.get(Tour.is)) {
    customElements.define(Tour.is, Tour);
}
