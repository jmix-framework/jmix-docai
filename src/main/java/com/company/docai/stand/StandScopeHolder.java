package com.company.docai.stand;

import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.WrappedSession;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.Callable;

/**
 * The scope the current thread works with: its own, or the one of the browser whose command it runs. A thread
 * without either is the service one.
 */
public final class StandScopeHolder {

    /**
     * The attribute of the HTTP session that holds the id of the scope of the browser, for the commands Vaadin runs
     * outside the requests of the browser, such as push.
     */
    public static final String SESSION_ATTRIBUTE = "docai.stand.scope";

    private static final ThreadLocal<StandScope> CURRENT = new ThreadLocal<>();

    // set while the thread works in the control part, where not even the scope of a browser counts
    private static final ThreadLocal<Boolean> IN_CONTROL = new ThreadLocal<>();

    private StandScopeHolder() {
    }

    /**
     * The scope bound to the thread itself; {@link #resolve()} also finds the one of the browser whose command the
     * thread runs.
     */
    public static @Nullable StandScope current() {
        return CURRENT.get();
    }

    /**
     * Binds the scope to the thread, or unbinds any with null, without restoring the previous one: for those who
     * restore it themselves, such as the filter of the requests.
     */
    public static void set(@Nullable StandScope scope) {
        if (scope == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(scope);
        }
    }

    /**
     * The scope of the thread, else the scope of the browser whose command the thread runs: Vaadin runs a command of
     * {@code UI.access} or {@code VaadinSession.access} on whichever thread holds the lock of the session. In the
     * control part, see {@link #runInControl}, only a scope bound to the thread counts.
     */
    public static @Nullable StandScope resolve() {
        StandScope scope = CURRENT.get();

        return scope != null || IN_CONTROL.get() != null
                ? scope
                : ofVaadinSession();
    }

    /**
     * Runs the work in the scope and restores the previous one afterwards, also when the work fails.
     */
    public static <T> T call(StandScope scope, Callable<T> work) throws Exception {
        StandScope previous = CURRENT.get();
        CURRENT.set(scope);

        try {
            return work.call();
        } finally {
            set(previous);
        }
    }

    /**
     * Runs the work without a result in the scope, as {@link #call} runs the work with one.
     */
    public static void run(StandScope scope, Runnable work) {
        StandScope previous = CURRENT.get();
        CURRENT.set(scope);

        try {
            work.run();
        } finally {
            set(previous);
        }
    }

    /**
     * Runs the work in the control part: without a scope, not even the one of the browser whose command the thread
     * runs, so the work reads the control database, as the check of the credentials at a login that makes a copy does.
     * A scope bound within the work counts as usual; the scope of the thread comes back afterwards, also when the work
     * fails.
     */
    public static void runInControl(Runnable work) {
        StandScope previous = CURRENT.get();
        boolean wasInControl = IN_CONTROL.get() != null;
        CURRENT.remove();
        IN_CONTROL.set(Boolean.TRUE);

        try {
            work.run();
        } finally {
            set(previous);

            if (!wasInControl) {
                IN_CONTROL.remove();
            }
        }
    }

    private static @Nullable StandScope ofVaadinSession() {
        VaadinSession session = VaadinSession.getCurrent();
        WrappedSession httpSession = session == null ? null : session.getSession();

        if (httpSession == null) {
            return null;
        }

        try {
            // a forged or a stale id names no scope
            return httpSession.getAttribute(SESSION_ATTRIBUTE) instanceof String id ? StandScope.fromId(id) : null;
        } catch (IllegalStateException e) {
            // a session that is being invalidated
            return null;
        }
    }
}
