package com.company.docai.app.ui.component.searchpalette;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;

/**
 * The flight of the search palette: the element {@code docai-search-palette-motion} of
 * {@code src/component/search-palette/docai-search-palette-motion.js} flies the query line out of the search field of
 * the header to its place and back, unfolds the panel under it and keeps the header field transparent while the palette
 * is open. It shows nothing itself. The palette declares it in its descriptor. It is not a Spring bean: the component
 * factory would give every browser tab the same one.
 */
@Tag("docai-search-palette-motion")
@JsModule("./src/component/search-palette/docai-search-palette-motion.js")
public class SearchPaletteMotion extends Component {

    /**
     * Flies the field of the palette out of the origin and unfolds the panel under it; the palette flies back into the
     * origin as it closes. The browser measures them, so no size or place comes from the server.
     */
    public void open(Component origin, Component field, Component panel) {
        getElement().callJsFunction("open", origin.getElement(), field.getElement(), panel.getElement());
    }
}
