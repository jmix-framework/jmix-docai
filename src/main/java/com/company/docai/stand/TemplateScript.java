package com.company.docai.stand;

import org.hsqldb.lib.StringConverter;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * The script of a template, which the copies are made from. {@code SCRIPT} of HSQLDB writes a large object of an
 * in-memory database as its number only, so the values of the large objects go to {@code UPDATE} statements at the end
 * of the same file.
 */
public final class TemplateScript {

    private TemplateScript() {
    }

    // a column of large objects; the names are the ones of the database, unquoted
    private record LobColumn(String schema, String table, String column, boolean characters) {

        private String quotedTable() {
            return quoted(schema) + "." + quoted(table);
        }

        private String quotedColumn() {
            return quoted(column);
        }
    }

    /**
     * Dumps the template into the file, replacing it. The large objects of the template become null, so the dump is
     * the last thing the template does before its shutdown.
     */
    public static void dump(Connection template, Path script) throws SQLException, IOException {
        List<String> lobUpdates = takeLobs(template);

        // SCRIPT refuses to overwrite a file
        Files.deleteIfExists(script);

        try (Statement statement = template.createStatement()) {
            statement.execute("SCRIPT " + quote(script.toAbsolutePath().toString()));
        }

        try (Writer writer = Files.newBufferedWriter(script, StandardCharsets.ISO_8859_1,
                StandardOpenOption.APPEND)) {
            for (String update : lobUpdates) {
                writer.write(escape(update));
                writer.write('\n');
            }
        }
    }

    /**
     * Reads the statements of a script for a new in-memory database, which has a user, a schema and a store of large
     * objects of its own; the large objects of the template come with the {@code UPDATE} statements.
     */
    public static List<String> read(Path script) throws IOException {
        List<String> statements = new ArrayList<>();
        boolean lobSchema = false;

        for (String line : Files.readAllLines(script, StandardCharsets.ISO_8859_1)) {
            String statement = StringConverter.unicodeStringToString(line);

            if (statement.startsWith("SET SCHEMA ")) {
                lobSchema = statement.equals("SET SCHEMA SYSTEM_LOBS");
            }

            if (!statement.isBlank() && !lobSchema && !clashes(statement)) {
                statements.add(statement);
            }
        }

        return statements;
    }

    /**
     * Fills a new in-memory database with the statements of a script in one transaction.
     */
    public static void fill(Connection copy, List<String> statements) throws SQLException {
        copy.setAutoCommit(false);

        try (Statement statement = copy.createStatement()) {
            // blocks of 1 KB: the store of large objects of an in-memory database then grows by 128 KB, not by 4 MB;
            // the scale changes only while the database has no large object
            statement.execute("SET FILES LOB SCALE 1");
            // the rows come table by table, and the references between the tables point both ways
            statement.execute("SET DATABASE REFERENTIAL INTEGRITY FALSE");

            for (String sql : statements) {
                statement.execute(sql);
            }

            statement.execute("SET DATABASE REFERENTIAL INTEGRITY TRUE");
        }

        copy.commit();
        copy.setAutoCommit(true);
    }

    // a new in-memory database has the user SA and the schema PUBLIC; the settings of files do not apply to it, and
    // it keeps a unique name of its own
    private static boolean clashes(String statement) {
        return statement.startsWith("CREATE USER SA ")
                || statement.startsWith("ALTER USER SA ")
                || statement.equals("CREATE SCHEMA PUBLIC AUTHORIZATION DBA")
                || statement.equals("GRANT DBA TO SA")
                || statement.startsWith("SET DATABASE UNIQUE NAME ")
                || statement.startsWith("SET FILES ")
                || statement.startsWith("ALTER SEQUENCE SYSTEM_LOBS.");
    }

    // the values of the large objects as UPDATE statements by the primary key, with nulls left in their place
    private static List<String> takeLobs(Connection template) throws SQLException {
        List<String> updates = new ArrayList<>();

        for (LobColumn column : lobColumns(template)) {
            List<String> keys = primaryKey(template, column);

            String select = "SELECT " + column.quotedColumn() + ", " + String.join(", ", keys)
                    + " FROM " + column.quotedTable()
                    + " WHERE " + column.quotedColumn() + " IS NOT NULL";

            try (Statement statement = template.createStatement();
                 ResultSet rows = statement.executeQuery(select)) {
                while (rows.next()) {
                    updates.add("UPDATE " + column.quotedTable()
                            + " SET " + column.quotedColumn() + " = " + value(rows, column)
                            + " WHERE " + where(rows, keys));
                }
            }

            try (Statement statement = template.createStatement()) {
                statement.executeUpdate("UPDATE " + column.quotedTable()
                        + " SET " + column.quotedColumn() + " = NULL"
                        + " WHERE " + column.quotedColumn() + " IS NOT NULL");
            }
        }

        return updates;
    }

    private static List<LobColumn> lobColumns(Connection template) throws SQLException {
        List<LobColumn> columns = new ArrayList<>();

        try (Statement statement = template.createStatement();
             ResultSet rows = statement.executeQuery("SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, DATA_TYPE"
                     + " FROM INFORMATION_SCHEMA.COLUMNS"
                     + " WHERE DATA_TYPE IN ('CHARACTER LARGE OBJECT', 'BINARY LARGE OBJECT')"
                     + " AND TABLE_SCHEMA NOT IN ('INFORMATION_SCHEMA', 'SYSTEM_LOBS')")) {
            while (rows.next()) {
                columns.add(new LobColumn(rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getString(4).startsWith("CHARACTER")));
            }
        }

        return columns;
    }

    private static List<String> primaryKey(Connection template, LobColumn column) throws SQLException {
        List<String> keys = new ArrayList<>();
        DatabaseMetaData metaData = template.getMetaData();

        try (ResultSet rows = metaData.getPrimaryKeys(null, column.schema(), column.table())) {
            while (rows.next()) {
                keys.add(quoted(rows.getString("COLUMN_NAME")));
            }
        }

        if (keys.isEmpty()) {
            throw new IllegalStateException("A large object in a table without a primary key: "
                    + column.quotedTable());
        }

        return keys;
    }

    private static String value(ResultSet rows, LobColumn column) throws SQLException {
        return column.characters()
                ? quote(rows.getString(1))
                : "X'" + HexFormat.of().formatHex(rows.getBytes(1)) + "'";
    }

    // the key values follow the large object in the row
    private static String where(ResultSet rows, List<String> keys) throws SQLException {
        List<String> conditions = new ArrayList<>();

        for (int i = 0; i < keys.size(); i++) {
            conditions.add(keys.get(i) + " = " + literal(rows, i + 2));
        }

        return String.join(" AND ", conditions);
    }

    private static String literal(ResultSet rows, int index) throws SQLException {
        return switch (rows.getMetaData().getColumnType(index)) {
            case Types.INTEGER, Types.BIGINT, Types.SMALLINT, Types.TINYINT, Types.NUMERIC, Types.DECIMAL ->
                    rows.getString(index);
            default -> quote(rows.getString(index));
        };
    }

    private static String quoted(String identifier) {
        return "\"" + identifier + "\"";
    }

    private static String quote(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    // the escapes of the lines HSQLDB writes itself, which StringConverter.unicodeStringToString reads back
    private static String escape(String line) {
        StringBuilder escaped = new StringBuilder(line.length() + 16);

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c >= 0x20 && c < 0x7f && c != '\\') {
                escaped.append(c);
            } else {
                escaped.append(String.format("\\u%04x", (int) c));
            }
        }

        return escaped.toString();
    }
}
