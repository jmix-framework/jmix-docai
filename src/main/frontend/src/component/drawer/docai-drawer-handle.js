/**
 * The edge of the main menu in the browser, the element of the server component DrawerHandle. A drag of the edge
 * widens the menu in the browser at once, in steps of a quarter of a rem, and reports the width at its end; the arrows
 * of the keyboard move the edge by a rem. The limits, in rem, come from the attributes min and max that the server
 * sets. Outside the layout of the application the edge does nothing.
 */
class DrawerHandle extends HTMLElement {

    // ends the drag that is on, without a report
    #endDrag = null;

    static get is() {
        return 'docai-drawer-handle';
    }

    constructor() {
        super();

        this.addEventListener('pointerdown', down => this.drag(down));
        this.addEventListener('keydown', event => this.step(event));
    }

    // an edge taken off the page in the middle of a drag gives the page back its cursor, its text selection and the
    // transitions of the menu
    disconnectedCallback() {
        this.#endDrag?.();
    }

    get layout() {
        return this.closest('vaadin-app-layout');
    }

    show(layout, width) {
        const min = Number(this.getAttribute('min'));
        const max = Number(this.getAttribute('max'));
        const shown = Math.min(max, Math.max(min, Math.round(width * 4) / 4));
        layout.style.setProperty('--main-view-drawer-width', shown + 'rem');

        return shown;
    }

    report(width) {
        this.dispatchEvent(new CustomEvent('docai-drawer-resize', {detail: width}));
    }

    drag(down) {
        const layout = this.layout;

        if (down.button !== 0 || !layout) {
            return;
        }

        down.preventDefault();
        this.setPointerCapture(down.pointerId);
        layout.toggleAttribute('drawer-resizing', true);
        const left = layout.getBoundingClientRect().left;
        const rem = parseFloat(getComputedStyle(document.documentElement).fontSize);
        let width;
        const move = event => width = this.show(layout, (event.clientX - left) / rem);

        const end = () => {
            this.removeEventListener('pointermove', move);
            this.removeEventListener('pointerup', up);
            this.removeEventListener('pointercancel', up);
            layout.toggleAttribute('drawer-resizing', false);
            this.#endDrag = null;
        };

        const up = () => {
            end();

            if (width !== undefined) {
                this.report(width);
            }
        };

        this.#endDrag = end;
        this.addEventListener('pointermove', move);
        this.addEventListener('pointerup', up);
        this.addEventListener('pointercancel', up);
    }

    step(event) {
        const step = {ArrowLeft: -1, ArrowRight: 1}[event.key];
        const layout = this.layout;

        if (step && layout) {
            event.preventDefault();

            const current = parseFloat(getComputedStyle(layout).getPropertyValue(
                    '--vaadin-app-layout-drawer-width'));

            this.report(this.show(layout, current + step));
        }
    }
}

if (!customElements.get(DrawerHandle.is)) {
    customElements.define(DrawerHandle.is, DrawerHandle);
}
