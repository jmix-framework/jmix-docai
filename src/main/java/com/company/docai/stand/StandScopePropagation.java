package com.company.docai.stand;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.List;
import java.util.function.Function;

/**
 * Carries the scope into the threads of Reactor, where the assistant streams its answers and calls its tools: Reactor
 * restores the scope from the context of a subscription on every thread its signals go to, and each subscription
 * takes the scope of the thread or the browser that subscribes. Reactor carries the scope alone: while the hooks are
 * on, the other thread-locals of the registry, Spring Security's context among them, stay where they are, as without
 * the stand. The hooks of Reactor and the registry of thread-locals are static, so the first context of the stand in
 * the JVM puts them in place and the last one to close takes them away and puts the others back; a context that
 * closes while another one lives, as in the cache of the tests, leaves them on.
 */
@Component
@Profile("stand")
public class StandScopePropagation {

    /**
     * The key of the scope in the context of a subscription, in the registry of thread-locals and among the hooks of
     * Reactor.
     */
    public static final String KEY = "docai.stand.scope";

    private static final ThreadLocalAccessor<StandScope> ACCESSOR = new ScopeAccessor();

    // the contexts of the stand that live in this JVM; guarded by the class
    private static int standContexts;

    // automatic propagation that something else has turned on stays on after the stand
    private static boolean propagatingBefore;

    // the accessors of others, out of the registry while the hooks of the stand are on
    private static List<ThreadLocalAccessor<?>> othersAside = List.of();

    /**
     * Puts the hooks in place, unless another context of the stand in this JVM has done it.
     */
    @PostConstruct
    public void enable() {
        retain();
    }

    /**
     * Takes the hooks away and puts the thread-locals of others back into the registry when the last context of the
     * stand in this JVM closes.
     */
    @PreDestroy
    public void disable() {
        release();
    }

    private static synchronized void retain() {
        if (standContexts++ > 0) {
            return;
        }

        propagatingBefore = Hooks.isAutomaticContextPropagationEnabled();

        ContextRegistry registry = ContextRegistry.getInstance();
        // Reactor would restore Spring Security's context too, clearing and setting the holder around each operator;
        // a clear drops the person Jmix signs in for a while, and the restore then leaves them on the thread for good
        othersAside = List.copyOf(registry.getThreadLocalAccessors());
        othersAside.forEach(accessor -> registry.removeThreadLocalAccessor(accessor.key()));
        registry.registerThreadLocalAccessor(ACCESSOR);

        Hooks.enableAutomaticContextPropagation();
        // the client of the model calls back on threads of its own, and the chat of Jmix subscribes with plain lambdas,
        // whose context is empty: without the scope in the context of the subscription Reactor restores nothing there
        Hooks.onLastOperator(KEY, StandScopePropagation::withScope);
    }

    private static synchronized void release() {
        if (--standContexts > 0) {
            return;
        }

        Hooks.resetOnLastOperator(KEY);

        if (!propagatingBefore) {
            Hooks.disableAutomaticContextPropagation();
        }

        ContextRegistry registry = ContextRegistry.getInstance();
        registry.removeThreadLocalAccessor(KEY);
        othersAside.forEach(registry::registerThreadLocalAccessor);
        othersAside = List.of();
    }

    // a scope the subscriber already carries stays
    private static Publisher<Object> withScope(Publisher<Object> publisher) {
        StandScope scope = StandScopeHolder.resolve();

        if (scope == null) {
            return publisher;
        }

        Function<Context, Context> put = context -> context.hasKey(KEY) ? context : context.put(KEY, scope);

        if (publisher instanceof Mono<Object> mono) {
            return mono.contextWrite(put);
        }

        return publisher instanceof Flux<Object> flux
                ? flux.contextWrite(put)
                : publisher;
    }

    // the thread's own scope, not the one of the session of its browser: Reactor gives a value it restores back to the
    // thread it took it from, and a thread must not keep a scope it did not have
    private static final class ScopeAccessor implements ThreadLocalAccessor<StandScope> {

        @Override
        public Object key() {
            return KEY;
        }

        @Override
        public @Nullable StandScope getValue() {
            return StandScopeHolder.current();
        }

        @Override
        public void setValue(StandScope value) {
            StandScopeHolder.set(value);
        }

        @Override
        public void setValue() {
            StandScopeHolder.set(null);
        }
    }
}
