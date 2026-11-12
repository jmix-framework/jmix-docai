import { UploadDropZone } from '@vaadin/upload/src/vaadin-upload-drop-zone.js';

// how long the zone waits for the next dragover of the files before it takes them for gone, in milliseconds
const SILENCE = 700;

/**
 * A drop zone for files from the computer, the element of the server component FileDropZone; the rest is the drop
 * zone of Vaadin. The drop zone answers any drag, so the zone passes on to it only the drags of files: the rows of the
 * document table still go to the folders and the favorites. The files of a drop would join a batch still on its way,
 * whose files the manager holds until the batch ends, so meanwhile the zone refuses them as the page does. The drop
 * zone forgets the files only on a dragleave of its own, which never comes while its content covers it, so the zone
 * tells it so when the files leave it or the drag stops without a drop: the browser repeats dragover while the files
 * are over the zone.
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
    }

    disconnectedCallback() {
        super.disconnectedCallback();

        clearTimeout(this.#silence);
    }

    get #busy() {
        return Boolean(this.manager) && this.manager.files.length > 0;
    }

    #sort(event) {
        if (!carriesFiles(event)) {
            event.stopImmediatePropagation();
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
        if (this.#busy) {
            event.stopImmediatePropagation();
            event.preventDefault();
        }
    }

    #forgetOnLeaving(event) {
        if (event.target !== this && !this.contains(event.relatedTarget)) {
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
