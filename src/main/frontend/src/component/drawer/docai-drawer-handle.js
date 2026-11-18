/**
 * The edge of the main menu in the browser, the element of DrawerHandle, which sets its limits in the attributes min
 * and max.
 */
class DrawerHandle extends HTMLElement {

    #endDrag = null;

    static get is() {
        return 'docai-drawer-handle';
    }

    constructor() {
        super();

        this.addEventListener('pointerdown', down => this.drag(down));
        this.addEventListener('keydown', event => this.step(event));
    }

    // taken off mid-drag, the edge gives the page back its cursor, its text selection and the menu's transitions
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
