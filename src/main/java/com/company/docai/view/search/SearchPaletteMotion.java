package com.company.docai.view.search;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;

/**
 * The flight of the search palette: the element {@code docai-search-palette-motion} of
 * {@code components/search-palette-motion.js} flies the query line out of the search field of the header to its
 * place and back, unfolds the panel under it and keeps the header field transparent while the palette is open. The
 * keys typed into the header field before the palette opened go over to the line: the letters fly over with it, and
 * an Enter that the field held back then runs in the line. It shows nothing itself. The palette declares it in its
 * descriptor. It is not a Spring bean: the component factory would give every browser tab the same one.
 */
@Tag("docai-search-palette-motion")
@JsModule("./components/search-palette-motion.js")
public class SearchPaletteMotion extends Component {

    /**
     * Flies the line out of the origin and unfolds the panel, and hands the keys typed into the origin since the
     * palette was asked for over to the line: the letters, then an Enter that the origin held back. The browser
     * measures and reads them, so no size, place or text comes from the server. After an Esc that cancelled the
     * opening it hands nothing over.
     */
    public void open(Component origin, Component line, Component panel) {
        getElement().callJsFunction("open", origin.getElement(), line.getElement(), panel.getElement());
    }
}
