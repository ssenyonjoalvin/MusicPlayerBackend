package com.pahappa.musicplayer.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pahappa.musicplayer.config.AppProperties;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression coverage for the {@code EnumType.ORDINAL} data corruption.
 *
 * <p>Recreates the exact failure that was hit in practice: a {@code TINYINT}
 * status column holding ordinal 4 for a constant that no longer exists, which
 * made every read of the table throw
 * {@code ArrayIndexOutOfBoundsException} and turned the library listing into a
 * 500.
 */
class EnumColumnMigrationTest {

    @TempDir
    Path root;

    private JdbcDataSource dataSource;
    private Path databaseFile;

    @BeforeEach
    void setUp() {
        databaseFile = root.resolve("legacy");
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:file:" + databaseFile.toString().replace('\\', '/'));
        h2.setUser("sa");
        h2.setPassword("");
        this.dataSource = h2;
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    /** Builds the legacy schema: integer enum columns, as ORDINAL produced. */
    private void createLegacySchema() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE TRACKS (
                        id UUID PRIMARY KEY,
                        youtube_video_id VARCHAR(32) NOT NULL,
                        title VARCHAR(512) NOT NULL,
                        audio_format TINYINT NOT NULL,
                        status TINYINT NOT NULL,
                        error_message VARCHAR(2048)
                    )
                    """);
        }
    }

    private void insertLegacyRow(String videoId, int formatOrdinal, int statusOrdinal) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                var prepared = connection.prepareStatement(
                        "INSERT INTO TRACKS (id, youtube_video_id, title, audio_format, status) "
                                + "VALUES (?, ?, ?, ?, ?)")) {
            prepared.setObject(1, java.util.UUID.randomUUID());
            prepared.setString(2, videoId);
            prepared.setString(3, videoId + " title");
            prepared.setInt(4, formatOrdinal);
            prepared.setInt(5, statusOrdinal);
            prepared.executeUpdate();
        }
    }

    private void runMigration() {
        new EnumColumnMigration(dataSource).run(null);
    }

    private String columnType(String column) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                ResultSet rs = connection.getMetaData().getColumns(null, null, "TRACKS", column)) {
            return rs.next() ? rs.getString("TYPE_NAME") : null;
        }
    }

    private java.util.List<String> allStatuses() throws SQLException {
        java.util.List<String> statuses = new java.util.ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT status FROM TRACKS")) {
            while (rs.next()) {
                statuses.add(rs.getString(1));
            }
        }
        return statuses;
    }

    @Test
    @DisplayName("converts legacy integer columns to enum names")
    void convertsIntegerColumns() throws SQLException {
        createLegacySchema();
        insertLegacyRow("dQw4w9WgXcQ", 0, 2);
        insertLegacyRow("aqz-KE-bpKQ", 1, 1);

        runMigration();

        assertThat(columnType("STATUS")).startsWith("CHARACTER VARYING");
        assertThat(columnType("AUDIO_FORMAT")).startsWith("CHARACTER VARYING");
        assertThat(allStatuses()).containsExactlyInAnyOrder("READY", "DOWNLOADING");
    }

    @Test
    @DisplayName("marks an unmappable ordinal FAILED instead of crashing the read")
    void marksUnmappableOrdinalAsFailed() throws SQLException {
        createLegacySchema();
        // 4 was a valid ordinal under an older, longer enum. It has no meaning now.
        insertLegacyRow("aqz-KE-bpKQ", 1, 4);

        runMigration();

        assertThat(allStatuses()).containsExactly("FAILED");
    }

    @Test
    @DisplayName("survives the exact production data that caused the 500")
    void handlesRealWorldCorruptRows() throws SQLException {
        createLegacySchema();
        insertLegacyRow("aqz-KE-bpKQ", 1, 4);
        insertLegacyRow("8560CAdA0Ys", 1, 4);
        insertLegacyRow("qgHV_nv8u3E", 0, 2);
        insertLegacyRow("zIb0TnEUZU4", 0, 2);

        runMigration();

        // The library listing must be readable again, and nothing may be left NULL.
        assertThat(allStatuses())
                .hasSize(4)
                .doesNotContainNull()
                .containsExactlyInAnyOrder("FAILED", "FAILED", "READY", "READY");
    }

    @Test
    @DisplayName("never loses a row, whatever the stored ordinal")
    void neverLosesRows() throws SQLException {
        createLegacySchema();
        // Every ordinal, including ones that no longer map to a constant.
        for (int ordinal = 0; ordinal <= 6; ordinal++) {
            insertLegacyRow("vid" + ordinal, ordinal % 2, ordinal);
        }

        runMigration();

        assertThat(allStatuses()).hasSize(7).doesNotContainNull();
    }

    @Test
    @DisplayName("is a no-op on a schema that is already string-based")
    void idempotentOnMigratedSchema() throws SQLException {
        createLegacySchema();
        insertLegacyRow("dQw4w9WgXcQ", 0, 2);
        runMigration();

        // Running again must not throw or change anything.
        runMigration();

        assertThat(allStatuses()).containsExactly("READY");
    }

    @Test
    @DisplayName("does not fail startup when the table does not exist yet")
    void toleratesMissingTable() {
        runMigration();
    }

    @Test
    @DisplayName("storage layout default is unaffected by the migration")
    void unrelatedConfigStillResolves() {
        AppProperties properties = new AppProperties(
                new AppProperties.Storage(root.toString(), null), null, null, null, null);

        assertThat(properties.storage().resolvedRoot()).isEqualTo(root.toAbsolutePath().normalize().toString());
    }
}
