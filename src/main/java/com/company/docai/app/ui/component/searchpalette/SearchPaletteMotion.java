package com.company.docai.app.ui.component.searchpalette;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;

/**
 * The flight of the search palette out of the header field and back; not a Spring bean, which every tab would share.
 */
@Tag("docai-search-palette-motion")
@JsModule("./src/component/search-palette/docai-search-palette-motion.js")
public class SearchPaletteMotion extends Component {

    /**
     * Flies the field of the palette out of the origin and back into it as the palette closes.
     */
    public void open(Component origin, Component field, Component panel) {
        getElement().callJsFunction("open", origin.getElement(), field.getElement(), panel.getElement());
    }
}
