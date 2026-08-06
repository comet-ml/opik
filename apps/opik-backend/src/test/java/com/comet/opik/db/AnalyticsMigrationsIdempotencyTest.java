package com.comet.opik.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Analytics Migrations Idempotency")
class AnalyticsMigrationsIdempotencyTest {

    private static final Path MIGRATIONS_DIR = Path
            .of("src/main/resources/liquibase/db-app-analytics/migrations");

    // Matches `ADD COLUMN <name>` but not `ADD COLUMN IF NOT EXISTS <name>`.
    private static final Pattern BARE_ADD_COLUMN = Pattern.compile(
            "(?i)\\bADD\\s+COLUMN\\s+(?!IF\\s+NOT\\s+EXISTS\\b)");

    // Rollbacks run only on an explicit `liquibase rollback`, never during the `update` that a
    // replica performs on start, so they are outside the replay path this test guards.
    private static final Pattern ROLLBACK_LINE = Pattern.compile("(?i)^\\s*--\\s*rollback\\b");

    @Test
    @DisplayName("no analytics migration uses a bare ADD COLUMN")
    void noAnalyticsMigrationUsesBareAddColumn() {
        // A replica re-runs `liquibase update` on every start. If the ledger is ever lost or partial
        // while the schema is intact (OPIK-7445), the changesets replay against existing tables and a
        // bare `ADD COLUMN` fails with `Code: 15 DUPLICATE_COLUMN`, crashlooping the replica.
        // `IF NOT EXISTS` degrades that to a no-op.
        var offenders = migrationFiles()
                .flatMap(AnalyticsMigrationsIdempotencyTest::bareAddColumnOccurrences)
                .toList();

        assertThat(offenders)
                .as("`ADD COLUMN` must be written as `ADD COLUMN IF NOT EXISTS` so a lost or partial "
                        + "Liquibase ledger replays as a no-op instead of crashlooping the replica")
                .isEmpty();
    }

    @Test
    @DisplayName("changesets edited for idempotency accept their pre-edit checksum")
    void editedChangesetsDeclareValidCheckSum() {
        // Editing an already-applied changeset changes its Liquibase checksum, so every deployment that
        // already ran it fails `update` with ValidationFailedException *before* executing anything.
        // `--validCheckSum ANY` waives that stored value. The two directives must therefore stay
        // together: whoever removes the waiver reintroduces a fleet-wide startup failure.
        //
        // Note the accepted spellings are `--validCheckSum ANY` and `--validCheckSum: ANY`; the parser
        // silently ignores `--validCheckSum:ANY` and an inline `validCheckSum:ANY` on the changeset line.
        var edited = List.of(
                "000001_init_script.sql",
                "000003_add_metadata_to_experiments.sql",
                "000014_add_thread_id_to_traces.sql");

        assertThat(edited)
                .allSatisfy(fileName -> assertThat(read(MIGRATIONS_DIR.resolve(fileName)))
                        .as("%s was edited after release, so it must waive its stored checksum", fileName)
                        .containsPattern("(?m)^--validCheckSum:?\\s+ANY\\s*$"));
    }

    private static Stream<Path> migrationFiles() {
        try (var files = Files.list(MIGRATIONS_DIR)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .toList()
                    .stream();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list analytics migrations", e);
        }
    }

    private static Stream<String> bareAddColumnOccurrences(Path file) {
        var lines = read(file).lines().toList();
        return java.util.stream.IntStream.range(0, lines.size())
                .filter(i -> !ROLLBACK_LINE.matcher(lines.get(i)).find())
                .filter(i -> BARE_ADD_COLUMN.matcher(lines.get(i)).find())
                .mapToObj(i -> "%s:%d -> %s".formatted(file.getFileName(), i + 1, lines.get(i).strip()));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }
}
