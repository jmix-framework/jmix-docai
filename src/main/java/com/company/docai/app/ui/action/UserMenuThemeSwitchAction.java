package com.company.docai.app.ui.action;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import io.jmix.flowui.action.ActionType;
import io.jmix.flowui.kit.component.usermenu.TextUserMenuItem;
import io.jmix.flowui.kit.component.usermenu.UserMenuItem;
import io.jmix.flowui.kit.component.usermenu.UserMenuItem.HasSubMenu;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import io.jmix.flowui.kit.theme.ThemeUtils;
import org.jspecify.annotations.Nullable;

/**
 * The theme switch of the user menu: the mixed scheme with the half circle, cogs for the system scheme, and a chosen
 * scheme that spreads over the page from its item.
 */
@ActionType(UserMenuThemeSwitchAction.ID)
public class UserMenuThemeSwitchAction extends io.jmix.flowui.action.usermenu.UserMenuThemeSwitchAction {

    /**
     * The name of the mixed scheme in the browser storage and in the {@code theme} attribute of the page.
     */
    public static final String MIXED_THEME = "mixed";

    private static final String APPLY_WAVE = "window.docaiThemeWave.apply($0, $1, $2)";

    public UserMenuThemeSwitchAction() {
        this(ID);
    }

    public UserMenuThemeSwitchAction(String id) {
        super(id);
    }

    @Override
    protected void initItems(HasSubMenu.SubMenu subMenu) {
        super.initItems(subMenu);

        menuItems.put(MIXED_THEME, createItem(subMenu, MIXED_THEME, event -> switchTo(MIXED_THEME)));
    }

    @Override
    protected Component createIcon(String theme) {
        return switch (theme) {
            case SYSTEM_THEME -> icons.get(JmixFontIcon.COGS);
            case MIXED_THEME -> icons.get(JmixFontIcon.CONTRAST);
            default -> super.createIcon(theme);
        };
    }

    @Override
    protected void selectSystemTheme(UserMenuItem.HasClickListener.ClickEvent<TextUserMenuItem> event) {
        switchTo(null);
    }

    @Override
    protected void selectLightTheme(UserMenuItem.HasClickListener.ClickEvent<TextUserMenuItem> event) {
        switchTo(LIGHT_THEME);
    }

    @Override
    protected void selectDarkTheme(UserMenuItem.HasClickListener.ClickEvent<TextUserMenuItem> event) {
        switchTo(DARK_THEME);
    }

    /**
     * Switches the page to the scheme in a wave from the item of the scheme; {@code null} is the system scheme.
     */
    public void switchTo(@Nullable String theme) {
        UserMenuItem item = menuItems.get(theme == null ? SYSTEM_THEME : theme);

        // not ThemeUtils.applyTheme: docai-theme-wave.js stores the scheme as it does and calls the same function
        UI.getCurrent()
                .getPage()
                .executeJs(APPLY_WAVE, ThemeUtils.THEME_STORAGE_KEY, theme, item.getId());

        updateState(theme);
    }
}
