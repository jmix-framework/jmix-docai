package com.company.dms.view.favorite;

import com.vaadin.flow.component.Component;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.icon.JmixFontIcon;

/**
 * The star of the favorites: a yellow outline on a folder or document that is not marked and a filled yellow star on
 * a marked one; a disabled action shows it grey. The actions, the favorites section and the documents table show the
 * same star.
 */
public final class FavoriteStars {

    /**
     * The class of the star of a marked item; the theme colors it.
     */
    public static final String MARKED_CLASS = "favorite-star";

    /**
     * The class of the star of an item that is not marked; the theme colors it.
     */
    public static final String UNMARKED_CLASS = "favorite-star-unmarked";

    private FavoriteStars() {
    }

    /**
     * Returns a new star: an outline when the item is not marked, filled when it is. Both are the same glyph, which
     * the theme draws as an outline for the class of an item that is not marked: the outline glyph is deprecated
     * since Jmix 3.1.
     */
    public static Component star(Icons icons, boolean marked) {
        Component star = icons.get(JmixFontIcon.STAR);
        star.getElement().getClassList().add(marked ? MARKED_CLASS : UNMARKED_CLASS);

        return star;
    }
}
