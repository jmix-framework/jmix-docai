package com.company.docai.stand;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The script of a template on small in-memory databases of their own: the large objects, which a plain SCRIPT of an
 * in-memory database leaves out, reach the copy with quotes, backslashes, line breaks and characters beyond ASCII.
 */
class TemplateScriptTest {

    // a quote, a backslash that looks like an escape of the script, a line break and two characters beyond ASCII
    private static final String TEXT = "it's \\u0041 here\nand \u00e9\u4e2d there";

    private static final byte[] BYTES = {0, 1, 39, 92, (byte) 0xff};

    @TempDir
    private Path tempDir;

    @Test
    void aCopyGetsTheRowsAndTheLargeObjectsOfTheTemplate() throws SQLException, IOException {
        Path script = tempDir.resolve("db.script");

        try (Connection template = connect("script_test_template")) {
            execute(template, "CREATE TABLE NOTE (ID INT PRIMARY KEY, TITLE VARCHAR(50), BODY CLOB, CONTENT BLOB)");
            execute(template, "CREATE TABLE PAIR (CODE VARCHAR(10), ITEM INT, BODY CLOB, PRIMARY KEY (CODE, ITEM))");
            insertNote(template, 1, "first");
            insertNote(template, 2, null);
            execute(template, "INSERT INTO PAIR VALUES ('a''b', 7, 'pair of " + TEXT.replace("'", "''") + "')");

            TemplateScript.dump(template, script);
            execute(template, "SHUTDOWN");
        }

        try (Connection copy = connect("script_test_copy")) {
            TemplateScript.fill(copy, TemplateScript.read(script));

            assertThat(rows(copy, "SELECT ID, TITLE, BODY FROM NOTE ORDER BY ID"))
                    .containsExactly("1|first|" + TEXT, "2|null|" + TEXT);
            assertThat(bytes(copy)).containsExactly(BYTES);
            assertThat(rows(copy, "SELECT CODE, ITEM, BODY FROM PAIR")).containsExactly("a'b|7|pair of " + TEXT);

            execute(copy, "SHUTDOWN");
        }
    }

    @Test
    void aLargeObjectInATableWithoutAPrimaryKeyIsRefused() throws SQLException {
        try (Connection template = connect("script_test_keyless")) {
            execute(template, "CREATE TABLE LOOSE (BODY CLOB)");
            execute(template, "INSERT INTO LOOSE VALUES ('text')");

            assertThatThrownBy(() -> TemplateScript.dump(template, tempDir.resolve("db.script")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("LOOSE");

            execute(template, "SHUTDOWN");
        }
    }

    private static Connection connect(String database) throws SQLException {
        return DriverManager.getConnection("jdbc:hsqldb:mem:" + database, "SA", "");
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void insertNote(Connection connection, int id, @Nullable String title) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO NOTE VALUES (?, ?, ?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, title);
            statement.setString(3, TEXT);
            statement.setBytes(4, BYTES);
            statement.executeUpdate();
        }
    }

    private static List<String> rows(Connection connection, String query) throws SQLException {
        List<String> rows = new ArrayList<>();

        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(query)) {
            while (result.next()) {
                rows.add(result.getString(1) + "|" + result.getString(2) + "|" + result.getString(3));
            }
        }

        return rows;
    }

    private static byte[] bytes(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT CONTENT FROM NOTE WHERE ID = 1")) {
            result.next();

            return result.getBytes(1);
        }
    }
}
