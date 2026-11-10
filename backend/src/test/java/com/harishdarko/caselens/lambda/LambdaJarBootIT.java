package com.harishdarko.caselens.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Boots the shaded Lambda jar (target/caselens-lambda.jar) the way the Lambda handlers do, on a database at the
 * previous schema version, and checks that Flyway migrates it before Hibernate validates the schema. The jar is
 * loaded in its own class loader, so its merged META-INF files are what Spring reads, as on Lambda. Runs in the
 * lambda profile's integration-test phase, after the jar is built.
 */
@Testcontainers
class LambdaJarBootIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("caselens").withUsername("caselens").withPassword("caselens");

    @Test
    void theLambdaJarMigratesAnOlderSchemaBeforeHibernateValidatesIt() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("5").load().migrate();
        assertThat(latestVersion()).isEqualTo("5");

        File jar = new File(System.getProperty("caselens.lambda.jar", "target/caselens-lambda.jar"));
        assertThat(jar).isFile();
        Map<String, String> properties = Map.of(
                "spring.datasource.url", POSTGRES.getJdbcUrl(),
                "spring.datasource.username", POSTGRES.getUsername(),
                "spring.datasource.password", POSTGRES.getPassword(),
                "CASELENS_SESSION_SECRET", "lambda-jar-boot-test-secret-that-is-at-least-32-bytes",
                "CASELENS_TRIAGE_QUEUE_URL", "http://localhost/unused",
                "CASELENS_QUEUE_ENABLED", "false");
        properties.forEach(System::setProperty);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toURI().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> lambdaContext = Class.forName("com.harishdarko.caselens.lambda.LambdaSpringContext", true, loader);
            Method builder = lambdaContext.getDeclaredMethod("applicationBuilder");
            builder.setAccessible(true);
            Object application = builder.invoke(null);
            Object context = application.getClass().getMethod("run", String[].class).invoke(application, (Object) new String[0]);
            try {
                assertThat((Boolean) context.getClass().getMethod("isActive").invoke(context)).isTrue();
                assertThat(latestVersion()).isEqualTo("6");
            } finally {
                context.getClass().getMethod("close").invoke(context);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
            properties.keySet().forEach(System::clearProperty);
            System.clearProperty("spring.profiles.active");
        }
    }

    private String latestVersion() throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(
                        "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")) {
            assertThat(row.next()).isTrue();
            return row.getString(1);
        }
    }
}
