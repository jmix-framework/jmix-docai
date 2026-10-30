package com.company.dms.app.ui.action;

import com.vaadin.flow.component.Component;
import io.jmix.flowui.action.ActionType;
import io.jmix.flowui.kit.component.usermenu.UserMenuItem.HasSubMenu;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import io.jmix.flowui.kit.theme.ThemeUtils;

/**
 * The theme switch of the user menu with a fourth item, the mixed scheme: a dark drawer next to light content. The
 * theme stylesheet draws the scheme from the {@code theme="mixed"} attribute that the switching script of Jmix puts
 * on the page. The mixed scheme shows a half circle, so the system scheme, whose standard icon is the same glyph,
 * shows cogs.
 */
@ActionType(UserMenuThemeSwitchAction.ID)
public class UserMenuThemeSwitchAction extends io.jmix.flowui.action.usermenu.UserMenuThemeSwitchAction {

    /**
     * The name of the mixed scheme in the browser storage and in the {@code theme} attribute of the page.
     */
    public static final String MIXED_THEME = "mixed";

    public UserMenuThemeSwitchAction() {
        this(ID);
    }

    public UserMenuThemeSwitchAction(String id) {
        super(id);
    }

    @Override
    protected void initItems(HasSubMenu.SubMenu subMenu) {
        super.initItems(subMenu);

        menuItems.put(MIXED_THEME, createItem(subMenu, MIXED_THEME, event -> applyMixedTheme()));
    }

    @Override
    protected Component createIcon(String theme) {
        return switch (theme) {
            case SYSTEM_THEME -> icons.get(JmixFontIcon.COGS);
            case MIXED_THEME -> icons.get(JmixFontIcon.CONTRAST);
            default -> super.createIcon(theme);
        };
    }

    /**
     * Applies the mixed scheme to the page and remembers it in the browser.
     */
    public void applyMixedTheme() {
        ThemeUtils.applyTheme(MIXED_THEME);
        updateState(MIXED_THEME);
    }
}
