package city.zqdesigned.mc.authplugin.db;

import city.zqdesigned.mc.authplugin.AuthPlugin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DatabaseManager {
    private static final Path DATABASE_DIRECTORY = Path.of("config", "authplugin", "database");
    private static final String JDBC_URL = "jdbc:h2:file:./config/authplugin/database/authplugin";
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final ExecutorService executorService = Executors.newFixedThreadPool(2, new DbThreadFactory());

    public void initialize() throws SQLException {
        if (!this.initialized.compareAndSet(false, true)) {
            return;
        }

        try {
            Files.createDirectories(DATABASE_DIRECTORY);
            this.createSchema();
            AuthPlugin.LOGGER.info("H2 database initialized at {}", DATABASE_DIRECTORY.toAbsolutePath());
        } catch (Exception exception) {
            this.initialized.set(false);
            throw new SQLException("Failed to initialize database", exception);
        }
    }

    public <T> CompletableFuture<T> supplyAsync(CheckedSqlFunction<Connection, T> function) {
        Objects.requireNonNull(function, "function");
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = this.openConnection()) {
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
            statement.execute("""
                CREATE TABLE IF NOT EXISTS tokens (
                    token VARCHAR PRIMARY KEY,
                    bound_uuid VARCHAR,
                    disabled BOOLEAN DEFAULT FALSE,
                    created_at BIGINT NOT NULL,
                    last_used_at BIGINT NOT NULL
                )
                """
            );
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(JDBC_URL);
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
