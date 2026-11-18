import { Upload } from '@vaadin/upload/src/vaadin-upload.js';
import { UploadDropZone } from '@vaadin/upload/src/vaadin-upload-drop-zone.js';
import { carriesFiles } from './docai-file-drop-zone.js';

const DRAG_EVENTS = ['dragenter', 'dragover', 'drop'];

/**
 * The guard of the page against files from the computer, the element of FileDropGuard: a file dropped where nothing
 * takes it would open in the tab and leave the application.
 */
class FileDropGuard extends HTMLElement {

    #stopStrayFiles = event => {
        if (carriesFiles(event) && !event.composedPath().some(takesFiles)) {
            event.stopImmediatePropagation();
            event.preventDefault();
            event.dataTransfer.dropEffect = 'none';
        }
    };

    static get is() {
        return 'docai-file-drop-guard';
    }

    connectedCallback() {
        this.hidden = true;

        for (const type of DRAG_EVENTS) {
            window.addEventListener(type, this.#stopStrayFiles, true);
        }
    }

    disconnectedCallback() {
        for (const type of DRAG_EVENTS) {
            window.removeEventListener(type, this.#stopStrayFiles, true);
        }
    }
}

function takesFiles(node) {
    return (node instanceof Upload || node instanceof UploadDropZone) && !node.disabled && !node.nodrop;
}

if (!customElements.get(FileDropGuard.is)) {
    customElements.define(FileDropGuard.is, FileDropGuard);
}
