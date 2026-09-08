package com.guanxian.platform;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Real PostgreSQL only: Docker by default, explicitly selected disposable native cluster locally.
 * No external JDBC URL is accepted, so this fixture cannot target an existing/production database. */
final class IsolatedPolicyPostgres implements AutoCloseable {
    private PostgreSQLContainer<?> container;
    private Path bin, root, data;
    private String url, password;
    private boolean started;

    static boolean available() {
        // An invalid explicit path must fail, never silently skip an intended native test run.
        return nativeBin() != null || DockerClientFactory.instance().isDockerAvailable();
    }

    private static String nativeBin() {
        String value = System.getenv("GUANXIAN_TEST_PG_BIN");
        return value == null || value.isBlank() ? null : value;
    }

    synchronized void register(DynamicPropertyRegistry registry) {
        if (url == null) start();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "guanxian");
        registry.add("spring.datasource.password", () -> password);
    }

    private void start() {
        password = UUID.randomUUID().toString();
        if (nativeBin() == null) {
            container = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("guanxian").withUsername("guanxian").withPassword(password);
            container.start();
            url = container.getJdbcUrl();
            return;
        }
        try {
            bin = Path.of(nativeBin()).toRealPath();
            for (String name : List.of("postgres", "initdb", "pg_ctl", "createdb")) {
                if (!Files.isRegularFile(executable(name))) throw new IllegalArgumentException("Missing test executable: " + name);
            }
            Path parent = Path.of("target", "isolated-policy-postgres").toAbsolutePath();
            Files.createDirectories(parent);
            root = Files.createTempDirectory(parent, "run-");
            data = root.resolve("data");
            command("version", "postgres", "--version");
            String version = Files.readString(root.resolve("version.log"));
            if (!version.startsWith("postgres (PostgreSQL) 16.")) throw new IllegalArgumentException("PostgreSQL 16 required");
            int port;
            try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
                port = socket.getLocalPort();
            }
            Path pw = root.resolve("init-password");
            Files.writeString(pw, password);
            try {
                command("init", "initdb", "-D", data.toString(), "-U", "guanxian", "--encoding=UTF8",
                        "--locale=C", "--auth-host=scram-sha-256", "--auth-local=scram-sha-256", "--pwfile=" + pw);
            } finally { Files.deleteIfExists(pw); }
            // This data directory is freshly created by this fixture, never supplied by the caller.
            command("start", "pg_ctl", "-D", data.toString(), "-l", root.resolve("postgres.log").toString(),
                    "-o", "-h 127.0.0.1 -p " + port, "-w", "-t", "30", "start");
            started = true;
            Runtime.getRuntime().addShutdownHook(new Thread(this::close, "isolated-policy-postgres-stop"));
            command("create", "createdb", "-h", "127.0.0.1", "-p", Integer.toString(port), "-U", "guanxian", "guanxian");
            url = "jdbc:postgresql://127.0.0.1:" + port + "/guanxian";
            System.out.println("Isolated real PostgreSQL: " + version.trim() + ", " + url + ", logs=" + root);
        } catch (Exception error) {
            close();
            throw new IllegalStateException("Unable to start isolated PostgreSQL; logs=" + root, error);
        }
    }

    private Path executable(String name) {
        return bin.resolve(name + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : ""));
    }

    private void command(String log, String executable, String... args) throws IOException, InterruptedException {
        List<String> argv = new ArrayList<>();
        argv.add(executable(executable).toString());
        argv.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(true)
                .redirectOutput(root.resolve(log + ".log").toFile());
        builder.environment().put("PGPASSWORD", password);
        Process process = builder.start();
        if (!process.waitFor(Duration.ofSeconds(40).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("Test PostgreSQL command timed out: " + log);
        }
        if (process.exitValue() != 0) throw new IOException("Test PostgreSQL command failed: " + root.resolve(log + ".log"));
    }

    @Override public synchronized void close() {
        if (container != null) { container.stop(); container = null; }
        if (started) {
            try {
                command("stop", "pg_ctl", "-D", data.toString(), "-m", "fast", "-w", "-t", "30", "stop");
                started = false;
            } catch (Exception error) {
                throw new IllegalStateException("Cannot stop isolated test PostgreSQL: " + data, error);
            }
        }
    }
}
