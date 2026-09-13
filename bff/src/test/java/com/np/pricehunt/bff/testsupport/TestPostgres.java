package com.np.pricehunt.bff.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * One Postgres for every Docker-backed suite in the JVM, initialized by the real
 * {@code create-bff-role.sh} so the tests connect as {@code bff_session} into {@code bff_sessions},
 * never as the container's superuser: the role/schema boundary is part of what they prove.
 * Resolved relative to the module directory (the working directory locally and in CI's
 * {@code working-directory: bff}), with a fallback for an IDE runner rooted at the repo.
 */
public final class TestPostgres {

    public static final String BFF_ROLE = "bff_session";
    public static final String BFF_PASSWORD = "bff-test-password";

    private static final PostgreSQLContainer<?> CONTAINER = new PostgreSQLContainer<>(
                    DockerImageName.parse("postgres:17"))
            .withEnv("BFF_DB_PASSWORD", BFF_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(initScript(), 0755), "/docker-entrypoint-initdb.d/create-bff-role.sh");

    static {
        CONTAINER.start();
    }

    private TestPostgres() {}

    public static PostgreSQLContainer<?> container() {
        return CONTAINER;
    }

    private static Path initScript() {
        Path relative = Path.of("../infra/postgres/init/create-bff-role.sh");
        if (Files.exists(relative)) {
            return relative.toAbsolutePath().normalize();
        }
        Path fromRepoRoot = Path.of("infra/postgres/init/create-bff-role.sh");
        if (Files.exists(fromRepoRoot)) {
            return fromRepoRoot.toAbsolutePath().normalize();
        }
        throw new IllegalStateException(
                "create-bff-role.sh not found relative to " + Path.of("").toAbsolutePath());
    }
}
