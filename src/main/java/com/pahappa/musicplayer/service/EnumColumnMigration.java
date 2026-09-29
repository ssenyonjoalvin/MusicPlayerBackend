package com.pahappa.musicplayer.service;

import com.pahappa.musicplayer.domain.AudioFormat;
import com.pahappa.musicplayer.domain.TrackStatus;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time repair for enum columns left behind by {@code EnumType.ORDINAL}.
 *
 * <p>{@code spring.jpa.hibernate.ddl-auto=update} adds columns but will never
 * change an existing column's <em>type</em>, so switching the mapping to
 * {@code EnumType.STRING} on its own leaves a {@code TINYINT} column that
 * Hibernate cannot read. This widens those columns in place.
 *
 * <p>Runs as an {@link ApplicationRunner}, which Spring invokes <em>before</em>
 * {@code ApplicationReadyEvent} - so it completes before
 * {@link StartupReconciler} issues its first query. A runner is used rather
 * than a bean initialiser because the DDL has to happen after Hibernate has
 * created the schema.
 *
 * <p>Rows whose stored ordinal no longer maps to a constant cannot be
 * interpreted with any confidence: the ordinals were written under a different
 * enum ordering. They are mapped to {@code FAILED} with an explanatory message
 * rather than guessed at, which surfaces them as retryable in the UI while
 * leaving the downloaded audio on disk untouched.
 */
@Component
@Order(0)
public class EnumColumnMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EnumColumnMigration.class);

    private static final String TABLE = "TRACKS";
    private static final String STATUS_COLUMN = "STATUS";
    private static final String FORMAT_COLUMN = "AUDIO_FORMAT";

    private final DataSource dataSource;

    public EnumColumnMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection)) {
                return;
            }
            // The fallback differs per column: writing TrackStatus.FAILED into
            // AUDIO_FORMAT would produce a value the enum cannot parse.
            migrate(connection, STATUS_COLUMN, TrackStatusValues.ORDINALS, TrackStatus.FAILED.name());
            migrate(connection, FORMAT_COLUMN, AudioFormatValues.ORDINALS, AudioFormat.M4A.name());
        } catch (SQLException ex) {
            // Never block startup on housekeeping; report it and carry on.
            log.error("Enum column migration could not run: {}", ex.getMessage());
        }
    }

    private void migrate(Connection connection, String column, String[] names, String fallback)
            throws SQLException {
        String typeName = columnType(connection, column);
        if (typeName == null || isCharacterType(typeName)) {
            // Absent, or already stored as a name: nothing to repair.
            return;
        }

        log.warn("Column {} is stored as {} but the mapping now uses enum names; converting",
                column, typeName.toUpperCase(Locale.ROOT));

        String temporary = column + "_VARCHAR";
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + TABLE + " ADD COLUMN " + temporary + " VARCHAR(16)");

            int remapped = 0;
            int unrecoverable = 0;
            try (ResultSet rows = statement.executeQuery("SELECT id, " + column + " FROM " + TABLE)) {
                List<Object[]> updates = new ArrayList<>();
                while (rows.next()) {
                    Object id = rows.getObject(1);
                    int ordinal = rows.getInt(2);
                    String name = ordinal >= 0 && ordinal < names.length ? names[ordinal] : null;
                    if (name != null) {
                        updates.add(new Object[] {name, id});
                    } else {
                        // No trustworthy mapping exists; fall back to a value this
                        // column's enum can always represent.
                        updates.add(new Object[] {fallback, id});
                        unrecoverable++;
                    }
                }
                remapped = updates.size();

                try (var prepared = connection.prepareStatement(
                        "UPDATE " + TABLE + " SET " + temporary + " = ? WHERE id = ?")) {
                    for (Object[] update : updates) {
                        prepared.setObject(1, update[0]);
                        prepared.setObject(2, update[1]);
                        prepared.addBatch();
                    }
                    prepared.executeBatch();
                }
            }

            // Drop the old column and rename, so the schema ends up matching the
            // entity mapping without leaving a legacy TINYINT behind.
            statement.execute("ALTER TABLE " + TABLE + " DROP COLUMN " + column);
            statement.execute("ALTER TABLE " + TABLE + " ALTER COLUMN " + temporary + " SET NOT NULL");
            statement.execute("ALTER TABLE " + TABLE + " RENAME COLUMN " + temporary + " TO " + column);

            log.warn("Converted {} rows in {}.{}", remapped, TABLE, column);
            if (unrecoverable > 0) {
                // Say what they became, rather than assuming this column is status.
                log.warn(
                        "{} rows in {}.{} had an ordinal with no matching constant and were set to {}",
                        unrecoverable,
                        TABLE,
                        column,
                        fallback);
            }
        }
    }

    private boolean tableExists(Connection connection) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet tables = metaData.getTables(null, null, TABLE, new String[] {"TABLE"})) {
            return tables.next();
        }
    }

    /** The column's declared type, or null when the column does not exist. */
    private String columnType(Connection connection, String column) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet columns = metaData.getColumns(null, null, TABLE, column)) {
            return columns.next() ? columns.getString("TYPE_NAME") : null;
        }
    }

    /**
     * True when the column already stores text, so there is nothing to repair.
     *
     * <p>Hibernate 7 maps a {@code STRING} enum to a native
     * {@code ENUM('A','B')} column on H2, so that counts as already-correct
     * alongside VARCHAR. Only genuinely numeric types need converting.
     */
    private static boolean isCharacterType(String typeName) {
        String normalised = typeName.toUpperCase(Locale.ROOT);
        return normalised.startsWith("CHAR")
                || normalised.startsWith("VARCHAR")
                || normalised.startsWith("ENUM");
    }

    /**
     * Enum names in the order the ordinals were written.
     *
     * <p>Deliberately a snapshot rather than a live {@code values()} lookup, so
     * that a future reordering of the enum cannot change what these historical
     * ordinals mean.
     */
    private static final class TrackStatusValues {
        static final String[] ORDINALS = {"QUEUED", "DOWNLOADING", "READY", "FAILED"};

        private TrackStatusValues() {
        }
    }

    private static final class AudioFormatValues {
        static final String[] ORDINALS = {"M4A", "MP3"};

        private AudioFormatValues() {
        }
    }
}
