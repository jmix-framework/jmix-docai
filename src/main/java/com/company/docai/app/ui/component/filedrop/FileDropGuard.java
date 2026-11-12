package com.company.docai.app.ui.component.filedrop;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;

/**
 * The guard of the page against files dragged from the computer: they go only to the uploads that take them. The
 * element {@code docai-file-drop-guard} of {@code src/component/file-drop/docai-file-drop-guard.js} stops them anywhere
 * else and shows nothing. The main view declares the guard once in its descriptor.
 */
@Tag("docai-file-drop-guard")
@JsModule("./src/component/file-drop/docai-file-drop-guard.js")
public class FileDropGuard extends Component {
}
