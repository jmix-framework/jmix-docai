/**
 * The edge of the main menu in the browser, the element of the server component DrawerHandle. A drag of the edge
 * widens the menu in the browser at once, in steps of a quarter of a rem, and reports the width at its end; the arrows
 * of the keyboard move the edge by a rem. The limits, in rem, come from the attributes min and max that the server
 * sets.
 */
class DrawerHandle extends HTMLElement {

    constructor() {
        super();

        this.addEventListener('pointerdown', down => this.drag(down));
        this.addEventListener('keydown', event => this.step(event));
    }

    get layout() {
        return this.closest('vaadin-app-layout');
    }

    show(width) {
        const min = Number(this.getAttribute('min'));
        const max = Number(this.getAttribute('max'));
        const shown = Math.min(max, Math.max(min, Math.round(width * 4) / 4));
        this.layout.style.setProperty('--main-view-drawer-width', shown + 'rem');

        return shown;
    }

    report(width) {
        this.dispatchEvent(new CustomEvent('drawer-resize', {detail: width}));
    }

    drag(down) {
        if (down.button !== 0) {
            return;
        }

        down.preventDefault();
        this.setPointerCapture(down.pointerId);
        const layout = this.layout;
        layout.toggleAttribute('drawer-resizing', true);
        const left = layout.getBoundingClientRect().left;
        const rem = parseFloat(getComputedStyle(document.documentElement).fontSize);
        let width;
        const move = event => width = this.show((event.clientX - left) / rem);

        const up = () => {
            this.removeEventListener('pointermove', move);
            this.removeEventListener('pointerup', up);
            this.removeEventListener('pointercancel', up);
            layout.toggleAttribute('drawer-resizing', false);

            if (width !== undefined) {
                this.report(width);
            }
        };

        this.addEventListener('pointermove', move);
        this.addEventListener('pointerup', up);
        this.addEventListener('pointercancel', up);
    }

    step(event) {
        const step = {ArrowLeft: -1, ArrowRight: 1}[event.key];

        if (step) {
            event.preventDefault();

            const current = parseFloat(getComputedStyle(this.layout).getPropertyValue(
                    '--vaadin-app-layout-drawer-width'));

            this.report(this.show(current + step));
        }
    }
}

if (!customElements.get('docai-drawer-handle')) {
    customElements.define('docai-drawer-handle', DrawerHandle);
}
