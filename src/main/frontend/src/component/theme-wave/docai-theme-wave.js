const WAVE = 'docai-theme-wave';
const ORIGIN_LIFETIME = 10000;

let origin = null;
let running = null;

document.addEventListener('click', event => {
    const item = event.composedPath()
        .find(node => node instanceof Element && node.getAttribute('role') === 'menuitem');

    if (item) {
        origin = {id: item.id, ...pointOf(event, item), time: performance.now()};
    }
}, true);

/**
 * The theme switch of the user menu in the browser: stores the scheme as Jmix does and applies it with the function
 * of Jmix, in a circle that spreads from the menu item clicked.
 */
window.docaiThemeWave = {

    apply(key, theme, itemId) {
        if (theme === null) {
            localStorage.removeItem(key);
        } else {
            localStorage.setItem(key, theme);
        }

        const point = takeOrigin(itemId);

        if (point && waveFits(theme)) {
            spread(point);
        } else {
            running?.skipTransition();
            window.applyTheme();
        }
    }
};

function pointOf(event, item) {
    if (event.detail === 0) {
        // Vaadin clicks the item itself on Enter and Space, with no pointer position; the menu is still open
        const rect = item.getBoundingClientRect();

        return {x: rect.left + rect.width / 2, y: rect.top + rect.height / 2};
    }

    return {x: event.clientX, y: event.clientY};
}

function takeOrigin(itemId) {
    const taken = origin;
    origin = null;

    return taken?.id === itemId && performance.now() - taken.time <= ORIGIN_LIFETIME ? taken : null;
}

function waveFits(theme) {
    // the scheme that window.applyTheme of Jmix picks: the stored one, or the one of the system
    const next = theme ?? (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');

    return next !== document.documentElement.getAttribute('theme')
        && typeof document.startViewTransition === 'function'
        && !matchMedia('(prefers-reduced-motion: reduce)').matches
        && document.visibilityState !== 'hidden';
}

function spread({x, y}) {
    const root = document.documentElement;
    const radius = Math.hypot(Math.max(x, innerWidth - x), Math.max(y, innerHeight - y));
    running?.skipTransition();

    root.style.setProperty('--docai-theme-wave-x', `${x}px`);
    root.style.setProperty('--docai-theme-wave-y', `${y}px`);
    root.style.setProperty('--docai-theme-wave-radius', `${radius}px`);
    root.classList.add(WAVE);

    let transition;

    try {
        transition = document.startViewTransition(() => window.applyTheme());
    } catch {
        clear();
        window.applyTheme();
        return;
    }

    running = transition;

    transition.finished.finally(() => {
        // a skipped wave ends after the next one has started, and the class on the page is that one's now
        if (running === transition) {
            running = null;
            clear();
        }
    });
}

function clear() {
    const root = document.documentElement;
    root.classList.remove(WAVE);
    root.style.removeProperty('--docai-theme-wave-x');
    root.style.removeProperty('--docai-theme-wave-y');
    root.style.removeProperty('--docai-theme-wave-radius');
}
