package com.company.docai.stand;

import com.company.docai.DocAiApplication;
import com.company.docai.test_support.AnonymousUiTestAuthenticator;
import com.company.docai.test_support.StubAiConfiguration;
import com.company.docai.view.login.LoginView;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.WrappedHttpSession;
import com.vaadin.flow.server.WrappedSession;
import com.zaxxer.hikari.HikariDataSource;
import io.jmix.core.security.SecurityContextHelper;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.asynctask.UiAsyncTasks;
import io.jmix.flowui.backgroundtask.BackgroundTask;
import io.jmix.flowui.backgroundtask.BackgroundTaskHandler;
import io.jmix.flowui.backgroundtask.BackgroundWorker;
import io.jmix.flowui.backgroundtask.TaskLifeCycle;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import jakarta.servlet.http.HttpSession;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.lang.ref.Reference;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The scope of a visitor in the threads where the work of their browser goes on outside its requests: the background
 * and the asynchronous tasks of the views, and Reactor in the shapes the assistant subscribes in. The class has the
 * configuration of the stand tests on {@code build/test-stand-login}, such as {@link StandLoginTest}, so the five share
 * a Spring context; its scope keeps out of the folder of the stand.
 */
@UiTest(authenticator = AnonymousUiTestAuthenticator.class, initialView = LoginView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class, StubAiConfiguration.class},
        properties = "docai.stand.data-dir=build/test-stand-login")
@ActiveProfiles({"test", "stand"})
class StandScopePropagationTest {

    private static final String VISITOR = "alice";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private BackgroundWorker backgroundWorker;

    @Autowired
    private UiAsyncTasks uiAsyncTasks;

    @Autowired
    private StandDataSource dataSource;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    private final StandScope scope = StandScope.copy(CopyRegistry.newKey());

    @BeforeEach
    void giveTheScopeADatabase() {
        dataSource.add(scope, StandDataSource.pool("propagation", scope.databaseUrl()));
    }

    @AfterEach
    void dropTheDatabaseOfTheScope() {
        HikariDataSource database = dataSource.remove(scope);

        if (database != null) {
            database.close();
        }

        StandDataSource.shutdown(scope);
    }

    @Test
    void theTasksOfTheViewsRunInTheClassesOfTheStand() {
        assertThat(backgroundWorker).isInstanceOf(StandBackgroundWorker.class);
        assertThat(uiAsyncTasks).isInstanceOf(StandUiAsyncTasks.class);
    }

    @Test
    void aBackgroundTaskWorksInTheScopeOfTheViewThatStartsIt() {
        DatabaseTask task = new DatabaseTask();
        BackgroundTaskHandler<String> handler = asTheVisitor(() -> start(task));

        String url = handler.getResult();

        assertThat(task.failure).isNull();
        assertThat(url).isEqualTo(scope.databaseUrl());
    }

    @Test
    void anAsynchronousTaskWorksInTheScopeOfTheViewThatStartsIt() throws Exception {
        AtomicReference<String> url = new AtomicReference<>();

        await(asTheVisitor(() -> uiAsyncTasks.runnableConfigurer(() -> url.set(databaseUrl()))
                .runAsync()));

        assertThat(url).hasValue(scope.databaseUrl());
    }

    // Vaadin runs a command of a browser, such as one of push, on whichever thread holds the lock of its session
    @Test
    void anAsynchronousTaskOfACommandOfTheBrowserWorksInTheScopeOfItsSession() throws Exception {
        AtomicReference<String> url = new AtomicReference<>();

        await(asACommandOfTheBrowser(() -> uiAsyncTasks.runnableConfigurer(() -> url.set(databaseUrl()))
                .runAsync()));

        assertThat(url).hasValue(scope.databaseUrl());
    }

    @Test
    void aBlockingSubscriptionOnAnotherSchedulerWorksInTheScope() {
        String url = asTheVisitor(() -> Mono.fromCallable(this::databaseUrl)
                .subscribeOn(Schedulers.boundedElastic())
                .block(TIMEOUT));

        assertThat(url).isEqualTo(scope.databaseUrl());
    }

    @Test
    void aSubscriptionWithLambdasWorksInTheScope() throws Exception {
        CompletableFuture<String> url = asTheVisitor(() -> subscribed(Mono.fromCallable(this::databaseUrl)
                .subscribeOn(Schedulers.boundedElastic())));

        assertThat(await(url)).isEqualTo(scope.databaseUrl());
    }

    // the client of the model hands the chunks of an answer over on a thread of its own, which has no scope
    @Test
    void theChunksFromAThreadOfTheClientWorkInTheScope() throws Exception {
        CompletableFuture<String> url = asTheVisitor(() -> subscribed(fromAThreadOfTheClient()
                .publishOn(Schedulers.boundedElastic())
                .map(chunk -> databaseUrl())
                .next()));

        assertThat(await(url)).isEqualTo(scope.databaseUrl());
    }

    // Spring AI calls the tools of a streamed answer after its chunks, subscribing anew on another scheduler
    @Test
    void theToolsOfAStreamedAnswerWorkInTheScope() throws Exception {
        CompletableFuture<String> url = asTheVisitor(() -> subscribed(fromAThreadOfTheClient()
                .publishOn(Schedulers.boundedElastic())
                .concatWith(Flux.defer(() -> Mono.fromCallable(this::databaseUrl)
                        .subscribeOn(Schedulers.boundedElastic())))
                .last()));

        assertThat(await(url)).isEqualTo(scope.databaseUrl());
    }

    @Test
    void aSubscriptionOfACommandOfTheBrowserWorksInTheScopeOfItsSession() throws Exception {
        CompletableFuture<String> url = asACommandOfTheBrowser(() -> subscribed(Mono.fromCallable(this::databaseUrl)
                .subscribeOn(Schedulers.boundedElastic())));

        assertThat(await(url)).isEqualTo(scope.databaseUrl());
        // the thread, such as one of push, serves other browsers next: Reactor leaves it no scope of its own
        assertThat(StandScopeHolder.current()).isNull();
    }

    // a task started without a scope runs as it did before: Jmix carries the user into its thread
    @Test
    void aBackgroundTaskOfAVisitorWithoutAScopeStillFailsAtTheDataSource() {
        DatabaseTask task = new DatabaseTask();
        BackgroundTaskHandler<String> handler = systemAuthenticator.withUser(VISITOR, () -> start(task));

        assertThat(handler.getResult()).isNull();
        assertThat(task.failure).hasMessage("A thread of \"" + VISITOR + "\" has no scope of the stand");
    }

    // the tools of the assistant sign the person in on their own thread, so a visitor whose scope is lost meets the
    // data source's refusal instead of the control database
    @Test
    void aToolOfAVisitorWithoutAScopeStillFailsAtTheDataSource() {
        assertThatThrownBy(() -> systemAuthenticator.withUser(VISITOR, () -> Mono.fromCallable(
                        () -> systemAuthenticator.withUser(VISITOR, this::databaseUrl))
                .subscribeOn(Schedulers.boundedElastic())
                .block(TIMEOUT)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("A thread of \"" + VISITOR + "\" has no scope of the stand");
    }

    // the stand carries its scope alone: carried along, the user would be cleared and set around every operator,
    // which loses the people Jmix signs in for a while
    @Test
    void reactorCarriesTheScopeButNotTheUser() throws Exception {
        String blocked = asTheVisitor(() -> Mono.fromCallable(StandScopePropagationTest::scopeAndUser)
                .subscribeOn(Schedulers.boundedElastic())
                .block(TIMEOUT));

        CompletableFuture<String> subscribed = asTheVisitor(() -> subscribed(Mono.fromCallable(
                        StandScopePropagationTest::scopeAndUser)
                .subscribeOn(Schedulers.boundedElastic())));

        assertThat(List.of(blocked, await(subscribed))).containsOnly(scope.id() + " as nobody");
    }

    // an answer with a tool in the shape of Spring AI: the client of the model hands the chunks over on a thread of its
    // own, they go on on a pool, and a tool on another pool signs a person in and out around a stream of its own
    @Test
    void anAnswerWithAToolLeavesEveryThreadAsItFoundIt() throws Exception {
        ExecutorService client = Executors.newSingleThreadExecutor();
        ExecutorService chunks = Executors.newSingleThreadExecutor();
        ExecutorService tools = Executors.newSingleThreadExecutor();
        Authentication admin = systemAuthenticator.withUser("admin", SecurityContextHelper::getAuthentication);
        Authentication before = SecurityContextHelper.getAuthentication();

        try {
            List<String> answers = asTheVisitor(() -> {
                Authentication visitor = SecurityContextHelper.getAuthentication();
                Mono<String> answer = answerWithATool(client, Schedulers.fromExecutorService(chunks), tools, admin);

                String blocked = answer.block(TIMEOUT);
                String subscribed = subscribed(answer)
                        .orTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .join();

                // Jmix keeps the visitor signed in as an override, which a clear of the holder would drop
                assertThat(SecurityContextHelper.getAuthentication()).isSameAs(visitor);
                assertThat(blocked).isNotNull();

                return List.of(blocked, subscribed);
            });

            assertThat(answers).containsOnly(scope.databaseUrl());
            assertThat(SecurityContextHelper.getAuthentication()).isSameAs(before);
            // a pooled thread serves another visitor next: neither the tool's person nor the scope stays on it
            assertThat(List.of(leftOn(client), leftOn(chunks), leftOn(tools))).containsOnly("no scope as nobody");
        } finally {
            client.shutdownNow();
            chunks.shutdownNow();
            tools.shutdownNow();
        }
    }

    // the hooks of Reactor are static: a class loader of its own gives Reactor, the registry and the stand a state
    // apart from the contexts of the stand that the cache of the tests keeps in this JVM
    @Test
    void theHooksStayOnUntilTheLastContextOfTheStandCloses() throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();

        try (URLClassLoader loader = new URLClassLoader(classPath(), ClassLoader.getPlatformClassLoader())) {
            // Spring and the service loader of the registry look their classes up through the loader of the thread
            thread.setContextClassLoader(loader);

            Callable<?> contexts = (Callable<?>) loader.loadClass(ContextsOfTheStand.class.getName())
                    .getConstructor()
                    .newInstance();

            assertThat(contexts.call()).isEqualTo(List.of(
                    List.of(true, true, true, false),
                    List.of(true, true, true, false),
                    List.of(false, false, false, true),
                    List.of(true, false, false, true)));
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private <T> T asTheVisitor(Supplier<T> work) {
        return systemAuthenticator.withUser(VISITOR, () -> {
            StandScopeHolder.set(scope);

            try {
                return work.get();
            } finally {
                StandScopeHolder.set(null);
            }
        });
    }

    private <T> T asACommandOfTheBrowser(Supplier<T> work) {
        HttpSession httpSession = new MockHttpSession();
        httpSession.setAttribute(StandScopeHolder.SESSION_ATTRIBUTE, scope.id());

        return systemAuthenticator.withUser(VISITOR, () -> {
            VaadinSession previous = VaadinSession.getCurrent();
            VaadinSession browser = browserSession(httpSession);
            VaadinSession.setCurrent(browser);

            try {
                return work.get();
            } finally {
                VaadinSession.setCurrent(previous);
                // Vaadin holds its current instances weakly: a collected session would leave the work without a scope
                Reference.reachabilityFence(browser);
            }
        });
    }

    private BackgroundTaskHandler<String> start(DatabaseTask task) {
        BackgroundTaskHandler<String> handler = backgroundWorker.handle(task);
        handler.execute();

        return handler;
    }

    private String databaseUrl() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        } catch (SQLException e) {
            throw new IllegalStateException("The database did not answer", e);
        }
    }

    // subscribes with plain lambdas, which carry no context, as the chat of Jmix does
    private static <T> CompletableFuture<T> subscribed(Mono<T> mono) {
        CompletableFuture<T> value = new CompletableFuture<>();
        mono.subscribe(value::complete, value::completeExceptionally);

        return value;
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static Flux<String> fromAThreadOfTheClient() {
        return Flux.create(sink -> new Thread(() -> {
            sink.next("chunk");
            sink.complete();
        }, "stand-test-client").start());
    }

    // the client's thread hands the chunks over, they go on on a pool, and the tool runs on a pool of its own, which
    // the stand's executor gives the scope
    private Mono<String> answerWithATool(ExecutorService client, Scheduler chunks, ExecutorService tools,
                                         Authentication person) {
        ExecutorService toolsInTheScope = new StandScopeExecutorService(tools);

        return Flux.<String>create(sink -> client.execute(() -> {
                    sink.next("chunk");
                    sink.complete();
                }))
                .publishOn(chunks)
                .concatWith(Mono.fromFuture(() -> CompletableFuture.supplyAsync(() -> asAnotherPerson(person),
                        toolsInTheScope)))
                .last();
    }

    // signs the person in and out as Jmix's SystemAuthenticator does, without the lookup of the user that the bare
    // database of the scope could not answer, and reads through a stream subscribed with plain lambdas
    private String asAnotherPerson(Authentication person) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(person);
        SecurityContextHelper.installContext(context);

        try {
            return subscribed(Mono.fromCallable(this::databaseUrl))
                    .join();
        } finally {
            SecurityContextHelper.restoreContext(previous);
        }
    }

    private static String leftOn(ExecutorService pool) throws Exception {
        return pool.submit(StandScopePropagationTest::scopeAndUser)
                .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static String scopeAndUser() {
        StandScope current = StandScopeHolder.current();
        Authentication authentication = SecurityContextHelper.getAuthentication();

        return (current == null ? "no scope" : current.id()) + " as "
                + (authentication == null ? "nobody" : authentication.getName());
    }

    private static VaadinSession browserSession(HttpSession httpSession) {
        return new VaadinSession(null) {
            @Override
            public WrappedSession getSession() {
                return new WrappedHttpSession(httpSession);
            }
        };
    }

    private static URL[] classPath() throws MalformedURLException {
        String[] entries = System.getProperty("java.class.path")
                .split(File.pathSeparator);

        URL[] urls = new URL[entries.length];

        for (int i = 0; i < entries.length; i++) {
            urls[i] = Path.of(entries[i])
                    .toUri()
                    .toURL();
        }

        return urls;
    }

    private class DatabaseTask extends BackgroundTask<Void, String> {

        private @Nullable IllegalStateException failure;

        private DatabaseTask() {
            super(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        @Override
        public @Nullable String run(TaskLifeCycle<Void> taskLifeCycle) {
            try {
                return databaseUrl();
            } catch (IllegalStateException e) {
                failure = e;

                return null;
            }
        }
    }

    /**
     * Opens two contexts of the stand and closes them one after the other, then opens and closes one more while
     * something else keeps automatic propagation on.
     */
    public static class ContextsOfTheStand implements Callable<List<List<Boolean>>> {

        @Override
        public List<List<Boolean>> call() {
            List<List<Boolean>> steps = new ArrayList<>();
            AnnotationConfigApplicationContext first = standContext();
            AnnotationConfigApplicationContext second = standContext();
            steps.add(hooks());

            first.close();
            steps.add(hooks());

            second.close();
            steps.add(hooks());

            Hooks.enableAutomaticContextPropagation();
            standContext().close();
            steps.add(hooks());

            return steps;
        }

        private static AnnotationConfigApplicationContext standContext() {
            AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();

            context.getEnvironment()
                    .setActiveProfiles("stand");

            context.register(StandScopePropagation.class);
            context.refresh();

            return context;
        }

        private static List<Boolean> hooks() {
            List<Object> keys = ContextRegistry.getInstance()
                    .getThreadLocalAccessors()
                    .stream()
                    .map(ThreadLocalAccessor::key)
                    .toList();

            AtomicBoolean seeded = new AtomicBoolean();

            // a subscription with a lambda carries no context of its own: only the hook puts the scope there
            StandScopeHolder.run(StandScope.template("en"), () -> Mono.deferContextual(context ->
                            Mono.just(context.hasKey(StandScopePropagation.KEY)))
                    .subscribe(seeded::set));

            return List.of(Hooks.isAutomaticContextPropagationEnabled(), keys.contains(StandScopePropagation.KEY),
                    seeded.get(), keys.contains(SecurityContext.class.getName()));
        }
    }
}
