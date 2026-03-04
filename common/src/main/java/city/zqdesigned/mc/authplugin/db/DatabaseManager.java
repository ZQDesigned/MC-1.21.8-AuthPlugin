package city.zqdesigned.mc.authplugin.db;

import city.zqdesigned.mc.authplugin.AuthPlugin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DatabaseManager {
    private static final String[] H2_DRIVER_CLASS_NAMES = {
        "org.h2.Driver",
        "city.zqdesigned.mc.authplugin.shadow.org.h2.Driver"
    };
    private static final String CREATE_TOKENS_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS tokens (
            token VARCHAR PRIMARY KEY,
            bound_uuid VARCHAR,
            disabled BOOLEAN DEFAULT FALSE,
            created_at BIGINT NOT NULL,
            last_used_at BIGINT NOT NULL
        )
        """;
    private static final String CREATE_PLAYER_PROFILES_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS player_profiles (
            uuid VARCHAR PRIMARY KEY,
            player_name VARCHAR NOT NULL,
            last_seen_at BIGINT NOT NULL
        )
        """;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final ExecutorService executorService = Executors.newFixedThreadPool(2, new DbThreadFactory());
    private final Path databaseDirectory;
    private final String jdbcUrl;
    private volatile Driver resolvedDriver;

    public DatabaseManager() {
        this(Path.of("config", "authplugin", "database"));
    }

    public DatabaseManager(Path databaseDirectory) {
        this(databaseDirectory, toJdbcFileUrl(databaseDirectory.resolve("authplugin")));
    }

    public DatabaseManager(Path databaseDirectory, String jdbcUrl) {
        this.databaseDirectory = databaseDirectory;
        this.jdbcUrl = jdbcUrl;
    }

    public void initialize() throws SQLException {
        if (!this.initialized.compareAndSet(false, true)) {
            return;
        }

        try {
            Files.createDirectories(this.databaseDirectory);
            this.ensureH2DriverLoaded();
            this.createSchema();
            AuthPlugin.LOGGER.info("H2 database initialized at {}", this.databaseDirectory.toAbsolutePath());
        } catch (Exception exception) {
            this.initialized.set(false);
            throw new SQLException("Failed to initialize database", exception);
        }
    }

    public <T> CompletableFuture<T> supplyAsync(CheckedSqlFunction<Connection, T> function) {
        Objects.requireNonNull(function, "function");
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = this.openConnection()) {
                this.ensureSchema(connection);
                return function.apply(connection);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }, this.executorService);
    }

    public CompletableFuture<Void> runAsync(CheckedSqlConsumer<Connection> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = this.openConnection()) {
                this.ensureSchema(connection);
                consumer.accept(connection);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }, this.executorService);
    }

    public <T> CompletableFuture<T> transactionAsync(CheckedSqlFunction<Connection, T> function) {
        Objects.requireNonNull(function, "function");
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = this.openConnection()) {
                this.ensureSchema(connection);
                connection.setAutoCommit(false);
                try {
                    T result = function.apply(connection);
                    connection.commit();
                    return result;
                } catch (Exception exception) {
                    connection.rollback();
                    throw exception;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }, this.executorService);
    }

    public void shutdown() {
        this.executorService.shutdown();
        try {
            if (!this.executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                this.executorService.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            this.executorService.shutdownNow();
        }
        this.initialized.set(false);
    }

    private void createSchema() throws SQLException {
        try (Connection connection = this.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TOKENS_TABLE_SQL);
            statement.execute(CREATE_PLAYER_PROFILES_TABLE_SQL);
        }
    }

    private Connection openConnection() throws SQLException {
        Driver driver = this.resolvedDriver;
        if (driver != null && driver.acceptsURL(this.jdbcUrl)) {
            Connection connection = driver.connect(this.jdbcUrl, new Properties());
            if (connection != null) {
                return connection;
            }
        }
        return DriverManager.getConnection(this.jdbcUrl);
    }

    private void ensureH2DriverLoaded() throws SQLException {
        if (this.resolvedDriver != null) {
            return;
        }

        for (ClassLoader classLoader : collectCandidateClassLoaders()) {
            for (String driverClassName : H2_DRIVER_CLASS_NAMES) {
                Driver driver = tryInstantiateDriver(driverClassName, classLoader);
                if (driver != null) {
                    this.resolvedDriver = driver;
                    return;
                }
            }
        }
        throw new SQLException("H2 JDBC driver class not found on runtime classpath");
    }

    private static Set<ClassLoader> collectCandidateClassLoaders() {
        LinkedHashSet<ClassLoader> loaders = new LinkedHashSet<>();
        loaders.add(DatabaseManager.class.getClassLoader());
        loaders.add(Thread.currentThread().getContextClassLoader());
        loaders.add(ClassLoader.getSystemClassLoader());

        String[] probeClasses = {
            "city.zqdesigned.mc.authplugin.neoforge.AuthPluginNeoForge",
            "city.zqdesigned.mc.authplugin.fabric.AuthPluginFabric",
            "city.zqdesigned.mc.authplugin.AuthPlugin"
        };
        ClassLoader[] snapshot = loaders.stream().filter(Objects::nonNull).toArray(ClassLoader[]::new);
        for (ClassLoader loader : snapshot) {
            if (loader == null) {
                continue;
            }
            for (String className : probeClasses) {
                try {
                    Class<?> clazz = Class.forName(className, false, loader);
                    loaders.add(clazz.getClassLoader());
                } catch (ClassNotFoundException ignored) {
                    // keep probing
                }
            }
            ClassLoader parent = loader.getParent();
            while (parent != null) {
                loaders.add(parent);
                parent = parent.getParent();
            }
        }

        loaders.remove(null);
        return loaders;
    }

    private static Driver tryInstantiateDriver(String driverClassName, ClassLoader classLoader) {
        try {
            Class<?> driverClass = Class.forName(driverClassName, true, classLoader);
            if (!Driver.class.isAssignableFrom(driverClass)) {
                return null;
            }
            return (Driver) driverClass.getDeclaredConstructor().newInstance();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void ensureSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TOKENS_TABLE_SQL);
            statement.execute(CREATE_PLAYER_PROFILES_TABLE_SQL);
        }
    }

    private static String toJdbcFileUrl(Path databaseFile) {
        String normalized = databaseFile.toAbsolutePath().normalize().toString();
        return "jdbc:h2:file:" + normalized;
    }

    @FunctionalInterface
    public interface CheckedSqlFunction<T, R> {
        R apply(T value) throws Exception;
    }

    @FunctionalInterface
    public interface CheckedSqlConsumer<T> {
        void accept(T value) throws Exception;
    }

    private static final class DbThreadFactory implements ThreadFactory {
        private int index = 0;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "authplugin-db-" + this.index++);
            thread.setDaemon(true);
            return thread;
        }
    }
}
