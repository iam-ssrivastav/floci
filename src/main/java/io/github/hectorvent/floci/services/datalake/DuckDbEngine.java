package io.github.hectorvent.floci.services.datalake;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

@ApplicationScoped
@RegisterForReflection(targets = { org.duckdb.DuckDBDriver.class })
public class DuckDbEngine {

    private static final Logger LOG = Logger.getLogger(DuckDbEngine.class);

    /**
     * Tracks whether the DuckDB httpfs extension has been installed in this JVM lifetime.
     * INSTALL is a network operation; once done it is cached by DuckDB itself.
     */
    private static volatile boolean httpfsInstallAttempted = false;

    private final EmulatorConfig config;
    private boolean available = false;
    private String unavailableReason = null;

    @Inject
    public DuckDbEngine(EmulatorConfig config) {
        this.config = config;
    }

    /**
     * Probes DuckDB at startup. Catches Throwable to handle UnsatisfiedLinkError and
     * ExceptionInInitializerError that occur when the native JNI library cannot be loaded
     * (e.g. in GraalVM native-image builds).
     */
    @PostConstruct
    void init() {
        try {
            try (Connection conn = openConnection()) {
                available = true;
                LOG.info("DuckDB engine initialized successfully.");
            }
        } catch (Throwable t) {
            available = false;
            unavailableReason = t.getClass().getSimpleName() + ": " + t.getMessage();
            LOG.warnv("DuckDB engine is not available in this build — Athena query execution and Firehose " +
                      "Parquet conversion will be disabled. Reason: {0}", unavailableReason);
        }
    }

    /**
     * Opens a raw DuckDB connection with an explicit memory limit. Passing max_memory prevents DuckDB
     * from triggering cgroup v2 auto-detection (GetCGroupV2MemoryLimit), which segfaults on some
     * container runtimes (Alpine + Docker Desktop on macOS with cgroup v2).
     */
    private Connection openConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("max_memory", "512MB");
        props.setProperty("threads", "4");
        return DriverManager.getConnection("jdbc:duckdb:", props);
    }

    public boolean isAvailable() {
        return available;
    }

    public Connection getConnection() throws SQLException {
        if (!available) {
            throw new UnsupportedOperationException(
                "DuckDB is not available in this build mode. Use the JVM image to enable " +
                "Athena query execution and Firehose Parquet conversion. Reason: " + unavailableReason);
        }
        Connection conn = openConnection();
        initS3(conn);
        return conn;
    }

    private void initS3(Connection conn) throws SQLException {
        loadHttpfs(conn);

        String hostname = System.getenv("FLOCI_HOSTNAME");
        if (hostname == null || hostname.isBlank()) {
            hostname = config.hostname().orElse("127.0.0.1");
        }

        // quarkus.http.test-port has a registered default value in production mode (8081),
        // so Optional is never empty even outside tests. Use LaunchMode to pick the right property.
        String port = LaunchMode.current() == LaunchMode.TEST
                ? ConfigProvider.getConfig().getOptionalValue("quarkus.http.test-port", String.class).orElse("8081")
                : ConfigProvider.getConfig().getOptionalValue("quarkus.http.port", String.class).orElse("4566");

        String endpoint = hostname + ":" + port;
        LOG.debugv("Configuring DuckDB S3 endpoint: {0}", endpoint);

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET s3_endpoint='" + endpoint + "';");
            stmt.execute("SET s3_use_ssl=false;");
            stmt.execute("SET s3_url_style='path';");
            stmt.execute("SET s3_access_key_id='test';");
            stmt.execute("SET s3_secret_access_key='test';");
            stmt.execute("SET s3_region='us-east-1';");
        }
    }

    /**
     * Installs (once per JVM) and loads the httpfs extension. INSTALL requires network access to
     * download from extensions.duckdb.org; if offline, the install is skipped and LOAD is attempted
     * against any previously cached version. If neither succeeds, S3-backed operations will fail
     * at query time with a clear error.
     */
    private void loadHttpfs(Connection conn) {
        if (!httpfsInstallAttempted) {
            synchronized (DuckDbEngine.class) {
                if (!httpfsInstallAttempted) {
                    httpfsInstallAttempted = true;
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("INSTALL httpfs;");
                    } catch (SQLException e) {
                        LOG.warnv("DuckDB httpfs INSTALL skipped (offline or already cached): {0}", e.getMessage());
                    }
                }
            }
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("LOAD httpfs;");
        } catch (SQLException e) {
            LOG.warnv("DuckDB httpfs extension could not be loaded — S3 read/write operations will fail: {0}",
                      e.getMessage());
        }
    }

    public void executeUpdate(String sql) throws SQLException {
        if (!available) {
            throw new UnsupportedOperationException(
                "DuckDB is not available. Reason: " + unavailableReason);
        }
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    public List<List<String>> executeQuery(String sql) throws SQLException {
        if (!available) {
            throw new UnsupportedOperationException(
                "DuckDB is not available. Reason: " + unavailableReason);
        }
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();
            List<List<String>> results = new ArrayList<>();

            List<String> header = new ArrayList<>();
            for (int i = 1; i <= cols; i++) {
                header.add(meta.getColumnName(i));
            }
            results.add(header);

            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= cols; i++) {
                    row.add(rs.getString(i));
                }
                results.add(row);
            }
            return results;
        }
    }
}
