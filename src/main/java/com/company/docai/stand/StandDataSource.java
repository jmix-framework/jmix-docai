package com.company.docai.stand;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jmix.core.security.SecurityContextHelper;
import io.jmix.core.security.UserRepository;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.log.LogFormatUtils;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * The data source of the stand: a connection goes to the database of the scope the current thread works with, and to
 * the control database when the thread has no scope. The thread of a signed-in visitor without a scope gets no
 * connection, since the control database would answer it quietly with no data.
 */
public class StandDataSource extends AbstractDataSource implements AutoCloseable {

    /**
     * The URL of the control database, which holds the schema without demo data.
     */
    public static final String CONTROL_URL = "jdbc:hsqldb:mem:control";

    private static final Logger log = LoggerFactory.getLogger(StandDataSource.class);

    private static final String USER = "SA";
    private static final String PASSWORD = "";

    // nothing but the application connects to a database in memory, so a password of this run is enough
    private static final String VISITOR = "VISITOR";
    private static final String VISITOR_PASSWORD = CopyRegistry.newKey();

    private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

    // one thread keeps the pools of all the databases of the stand, where each pool would start a thread of its own
    private static final ScheduledThreadPoolExecutor HOUSEKEEPER = housekeeper();

    private final HikariDataSource control;
    // the user repository reads the users through this data source, so it is looked up only when a connection is due
    private final ObjectProvider<UserRepository> userRepository;
    private final Map<String, HikariDataSource> databases = new ConcurrentHashMap<>();

    public StandDataSource(HikariDataSource control, ObjectProvider<UserRepository> userRepository) {
        this.control = control;
        this.userRepository = userRepository;
    }

    /**
     * Opens a pool of connections to an in-memory database of the stand as SA; its first connection creates the
     * database, unless the URL says {@code ifexists=true}. The pools share one thread for their housekeeping.
     */
    public static HikariDataSource pool(String name, String url) {
        return pool(name, url, USER, PASSWORD);
    }

    /**
     * Opens a pool of connections to the database of a copy as its visitor, see {@link #addVisitor}; a connection
     * asked for after the shutdown of the copy fails instead of creating an empty database.
     */
    public static HikariDataSource copyPool(StandScope copy) {
        return pool("copy", copy.databaseUrl() + ";ifexists=true", VISITOR, VISITOR_PASSWORD);
    }

    /**
     * Adds to the database of a new copy the user its pool connects as: a user without the rights of a DBA who may read
     * and change the rows of every table. Whatever SQL a visitor gets run in the copy, HSQLDB lets this user write no
     * file, read none, declare no routine and change no setting of the database.
     *
     * @param copy a connection to the database of the copy as SA
     */
    public static void addVisitor(Connection copy) throws SQLException {
        List<String> tables = new ArrayList<>();

        try (Statement statement = copy.createStatement();
             ResultSet rows = statement.executeQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES"
                     + " WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE'")) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        }

        try (Statement statement = copy.createStatement()) {
            statement.execute("CREATE USER " + VISITOR + " PASSWORD '" + VISITOR_PASSWORD + "'");

            for (String table : tables) {
                statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE PUBLIC.\"" + table + "\" TO "
                        + VISITOR);
            }
        }
    }

    /**
     * Keeps every in-memory database opened from now on from reading files, SA included: by default HSQLDB 2.7 lets
     * {@code LOAD_FILE} and the source of a text table of such a database reach any path on the disk. A database
     * reads the setting when it opens, and the setting is one for the whole JVM.
     */
    public static void denyFileReads() {
        System.setProperty("textdb.allow_full_path", "false");
    }

    /**
     * Connects to the in-memory database of the scope past any pool, creating the database when there is none.
     */
    public static Connection connect(StandScope scope) throws SQLException {
        return DriverManager.getConnection(scope.databaseUrl(), USER, PASSWORD);
    }

    /**
     * Shuts the in-memory database of the scope down, which frees its memory: closing its connections does not.
     * The caller is cleaning up, so a failure is logged, not thrown.
     */
    public static void shutdown(StandScope scope) {
        try (Connection connection = connect(scope);
             Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        } catch (SQLException e) {
            log.warn("The database of {} did not shut down: {}", scope.tag(), StandScope.withTags(e));
        }
    }

    /**
     * Sends the connections of the scope to its database from now on.
     *
     * @throws IllegalStateException if the scope has a database already
     */
    public void add(StandScope scope, HikariDataSource database) {
        if (databases.putIfAbsent(scope.id(), database) != null) {
            throw new IllegalStateException("The scope " + scope.tag() + " has a database already");
        }
    }

    /**
     * Stops sending the connections of the scope anywhere; the pool of its database stays open.
     *
     * @return the pool of the database of the scope, or null when the scope had none
     */
    public @Nullable HikariDataSource remove(StandScope scope) {
        return databases.remove(scope.id());
    }

    @Override
    public Connection getConnection() throws SQLException {
        return target().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return target().getConnection(username, password);
    }

    /**
     * Closes the pool of the control database; the pools of the scopes belong to those who added them.
     */
    @Override
    public void close() {
        control.close();
    }

    private HikariDataSource target() {
        StandScope scope = StandScopeHolder.resolve();

        if (scope == null) {
            checkNotAVisitor();

            return control;
        }

        HikariDataSource database = databases.get(scope.id());

        if (database == null) {
            throw new IllegalStateException("The scope " + scope.tag() + " has no database");
        }

        return database;
    }

    private static HikariDataSource pool(String name, String url, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("stand-" + name);
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMinimumIdle(0);
        config.setScheduledExecutor(HOUSEKEEPER);

        return new HikariDataSource(config);
    }

    // a pool that closes cancels its tasks, which then leave the queue instead of piling up with every deleted copy;
    // the thread lives as long as the application, as a pool of its own would
    private static ScheduledThreadPoolExecutor housekeeper() {
        ScheduledThreadPoolExecutor housekeeper = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform()
                .name("stand-pools-housekeeper")
                .daemon()
                .factory());

        housekeeper.setRemoveOnCancelPolicy(true);

        return housekeeper;
    }

    // anonymous threads, such as the login form, and the system user work with the control database
    private void checkNotAVisitor() {
        Authentication authentication = SecurityContextHelper.getAuthentication();

        if (authentication == null || !TRUST_RESOLVER.isAuthenticated(authentication)) {
            return;
        }

        String systemUser = userRepository.getObject()
                .getSystemUser()
                .getUsername();

        // the message reaches the log, and a visitor's username may hold line breaks
        if (!systemUser.equals(authentication.getName())) {
            String username = LogFormatUtils.formatValue(authentication.getName(), -1, true);

            throw new IllegalStateException("A thread of " + username + " has no scope of the stand");
        }
    }
}
