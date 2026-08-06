package com.comet.opik.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Analytics Migrations Idempotency")
class AnalyticsMigrationsIdempotencyTest {

    private static final Path MIGRATIONS_DIR = Path
            .of("src/main/resources/liquibase/db-app-analytics/migrations");

    // Matches `ADD COLUMN <name>` but not `ADD COLUMN IF NOT EXISTS <name>`. `\s+` spans newlines,
    // so a statement wrapped between `ADD` and `COLUMN` is still caught.
    private static final Pattern BARE_ADD_COLUMN = Pattern.compile(
            "(?i)\\bADD\\s+COLUMN\\s+(?!IF\\s+NOT\\s+EXISTS\\b)");

    // Rollbacks run only on an explicit `liquibase rollback`, never during the `update` that a
    // replica performs on start, so they are outside the replay path this test guards.
    private static final Pattern ROLLBACK_LINE = Pattern.compile("(?i)^\\s*--\\s*rollback\\b");

    // Liquibase directives open with `--` but are instructions, not commentary, so they must survive
    // comment stripping — ROLLBACK_LINE still has to see the rollback ones. Everything else beginning
    // with `--` is prose and gets blanked, so a commented-out `-- ADD COLUMN ...` is not flagged.
    private static final Pattern LIQUIBASE_DIRECTIVE = Pattern.compile(
            "(?i)^\\s*--\\s*(liquibase|changeset|rollback|validCheckSum|preconditions|precondition-[a-z-]+|comment|ignore|context|labels)\\b");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");

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

    @Test
    @DisplayName("the scanner catches bare ADD COLUMN wherever it hides, and only where it executes")
    void scannerDetectsOffendersWithoutFalsePositives() {
        assertThat(scan("ALTER TABLE t ADD COLUMN c String;"))
                .as("plain bare ADD COLUMN")
                .hasSize(1);

        assertThat(scan("ALTER TABLE t ADD\n    COLUMN c String;"))
                .as("ADD and COLUMN split across lines is still one bare statement")
                .hasSize(1);

        assertThat(scan("ALTER TABLE t\n    ADD COLUMN IF NOT EXISTS c String;"))
                .as("guarded statement")
                .isEmpty();

        assertThat(scan("-- ALTER TABLE t ADD COLUMN c String;"))
                .as("commented-out DDL never executes")
                .isEmpty();

        assertThat(scan("/* ALTER TABLE t ADD\n   COLUMN c String; */"))
                .as("block-commented DDL never executes")
                .isEmpty();

        assertThat(scan("--rollback ALTER TABLE t ADD COLUMN c String;"))
                .as("rollbacks do not run during the update a replica performs on start")
                .isEmpty();

        assertThat(scan("ALTER TABLE t ADD COLUMN IF NOT EXISTS a String;\nALTER TABLE t ADD COLUMN b String;"))
                .as("reports the offending statement, not the guarded one")
                .singleElement()
                .asString()
                .contains("ADD COLUMN b String")
                .doesNotContain("IF NOT EXISTS");
    }

    private static List<String> scan(String sql) {
        return bareAddColumnOccurrences("fixture.sql", sql).toList();
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
        return bareAddColumnOccurrences(file.getFileName().toString(), read(file));
    }

    static Stream<String> bareAddColumnOccurrences(String fileName, String sql) {
        // Scan the whole statement text rather than line by line: `ALTER TABLE t ADD\nCOLUMN c` is a
        // single bare statement that no per-line regex can see. Comments and rollback lines are blanked
        // in place rather than removed so character offsets — and therefore line numbers — stay exact.
        var scannable = blankNonExecutableText(sql);

        var matcher = BARE_ADD_COLUMN.matcher(scannable);
        return matcher.results()
                .map(match -> {
                    int line = (int) scannable.substring(0, match.start()).chars().filter(c -> c == '\n')
                            .count() + 1;
                    return "%s:%d -> %s".formatted(fileName, line, statementAround(sql, match.start()));
                })
                .toList()
                .stream();
    }

    private static String blankNonExecutableText(String sql) {
        var withoutBlockComments = BLOCK_COMMENT.matcher(sql)
                .replaceAll(match -> match.group().replaceAll("[^\n]", " "));

        return withoutBlockComments.lines()
                .map(line -> isExecutable(line) ? line : " ".repeat(line.length()))
                .collect(Collectors.joining("\n"));
    }

    private static boolean isExecutable(String line) {
        // Rollback directives are excluded deliberately: they never run during the `update` a replica
        // performs on start. Other Liquibase directives carry no DDL, so blanking them is harmless.
        return !line.stripLeading().startsWith("--")
                && !ROLLBACK_LINE.matcher(line).find()
                && !LIQUIBASE_DIRECTIVE.matcher(line).find();
    }

    private static String statementAround(String sql, int offset) {
        // Report from the ALTER that owns the match, not the previous `;` — a file whose header has no
        // semicolon would otherwise drag its whole preamble into the failure message.
        int statementStart = sql.lastIndexOf(';', offset) + 1;
        int alterStart = sql.toUpperCase(Locale.ROOT).lastIndexOf("ALTER", offset);
        int start = Math.max(statementStart, alterStart < 0 ? statementStart : alterStart);

        int end = sql.indexOf(';', offset);
        return sql.substring(start, end < 0 ? sql.length() : end).strip().replaceAll("\\s+", " ");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }
}
