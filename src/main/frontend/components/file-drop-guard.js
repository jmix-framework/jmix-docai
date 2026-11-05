import { Upload } from '@vaadin/upload/src/vaadin-upload.js';
import { UploadDropZone } from '@vaadin/upload/src/vaadin-upload-drop-zone.js';
import { carriesFiles } from './file-drop-zone.js';

const DRAG_EVENTS = ['dragenter', 'dragover', 'drop'];

/**
 * The guard of the page against files from the computer, the element of the server component FileDropGuard. A file
 * dragged from the computer and dropped where nothing takes it would open in the tab and leave the application, and a
 * drop target for documents would light up for it and do nothing. Files from the computer go only to the uploads that
 * take them now: elsewhere, the chat's upload with attachments off included, the page stops them before anything sees
 * them, and the cursor shows they can't be dropped there.
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
        // the guard shows nothing
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

// the drop zones of the application extend the drop zone of Vaadin
function takesFiles(node) {
    return (node instanceof Upload || node instanceof UploadDropZone) && !node.disabled && !node.nodrop;
}

if (!customElements.get(FileDropGuard.is)) {
    customElements.define(FileDropGuard.is, FileDropGuard);
}
