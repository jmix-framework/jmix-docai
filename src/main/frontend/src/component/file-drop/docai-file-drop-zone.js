import { UploadDropZone } from '@vaadin/upload/src/vaadin-upload-drop-zone.js';

const SILENCE = 700;

/**
 * A drop zone for files from the computer, the element of FileDropZone over the drop zone of Vaadin, which answers any
 * drag: this one passes on only the drags of files and refuses them while the manager holds a batch on its way, which
 * they would join. Vaadin's zone forgets the files only on a dragleave of its own, which never comes while its content
 * covers it, so this one dispatches it when the files leave or the drag stops without a drop: the browser repeats
 * dragover while they are over the zone. While the server makes the element a drop target of Flow (its __active
 * property), a drag without files goes on to the listeners of Flow, and the upload still gets none of it.
 */
class FileDropZone extends UploadDropZone {

    #silence;

    static get is() {
        return 'docai-file-drop-zone';
    }

    constructor() {
        super();

        // the drop zone adds its own listeners when it first renders, so these come first and can keep a drag from it
        for (const type of ['dragenter', 'dragover']) {
            this.addEventListener(type, event => this.#sort(event));
        }

        this.addEventListener('drop', event => this.#refuseWhileBusy(event));
        this.addEventListener('dragleave', event => this.#forgetOnLeaving(event));

        // a popover at the table takes a Tab from it, but the grid would still pull the focus back to its cell
        this.addEventListener('keydown', event => {
            if (event.key === 'Tab' && event.defaultPrevented && !this.contains(document.activeElement)) {
                event.stopPropagation();
            }
        }, true);
    }

    disconnectedCallback() {
        super.disconnectedCallback();

        clearTimeout(this.#silence);
    }

    get #busy() {
        return Boolean(this.manager) && this.manager.files.length > 0;
    }

    __onDragover(event) {
        // the upload answers only files: this listener and __onDrop are those of Vaadin's zone, which it finds by
        // their names
        if (carriesFiles(event)) {
            super.__onDragover(event);
        }
    }

    __onDrop(event) {
        return carriesFiles(event) ? super.__onDrop(event) : undefined;
    }

    #sort(event) {
        if (!carriesFiles(event)) {
            if (!this.__active) {
                event.stopImmediatePropagation();
            } else if (event.type === 'dragover' && !this.hasAttribute('disabled')) {
                // Flow lights a target on a dragenter only, and the server may activate this one later
                this.classList.add('v-drag-over-target');
            }
        } else if (this.#busy) {
            event.stopImmediatePropagation();
            event.preventDefault();
            event.dataTransfer.dropEffect = 'none';
        } else if (event.type === 'dragover') {
            clearTimeout(this.#silence);
            this.#silence = setTimeout(() => this.#forget(), SILENCE);
        }
    }

    #refuseWhileBusy(event) {
        if (carriesFiles(event) && this.#busy) {
            event.stopImmediatePropagation();
            event.preventDefault();
        }
    }

    #forgetOnLeaving(event) {
        if (carriesFiles(event) && event.target !== this && !this.contains(event.relatedTarget)) {
            this.#forget();
        }
    }

    #forget() {
        clearTimeout(this.#silence);
        this.dispatchEvent(new DragEvent('dragleave'));
    }
}

export function carriesFiles(event) {
    return Boolean(event.dataTransfer) && Array.from(event.dataTransfer.types).includes('Files');
}

if (!customElements.get(FileDropZone.is)) {
    customElements.define(FileDropZone.is, FileDropZone);
}
