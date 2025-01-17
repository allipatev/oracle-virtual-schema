package com.exasol.adapter.dialects.oracle;

import static com.exasol.adapter.dialects.oracle.IntegrationTestConstants.*;
import static com.exasol.adapter.dialects.oracle.IntegrationTestsHelperfunctions.getPropertyFromFile;
import static com.exasol.adapter.dialects.oracle.OracleVirtualSchemaIntegrationTestSetup.*;
import static com.exasol.matcher.ResultSetMatcher.matchesResultSet;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.*;
import java.sql.Date;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.exasol.bucketfs.Bucket;
import com.exasol.bucketfs.BucketAccessException;
import com.exasol.containers.ExasolContainer;
import com.exasol.dbbuilder.dialects.exasol.*;

/**
 * How to run `OracleSqlDialectIT`: See the documentation <a
 * href="doc/development/developing-sql-dialect/integration_testing_with_containers.md>integration_testing_with_containers.md</a>.
 */
@Tag("integration")
@Testcontainers
class OracleSqlDialectIT {
    private static final Logger LOGGER = LoggerFactory.getLogger(OracleSqlDialectIT.class);

    private static final String ORACLE_CONTAINER_NAME = IntegrationTestConstants.ORACLE_CONTAINER_NAME;

    private static final String SCHEMA_ORACLE = "SCHEMA_ORACLE";

    private static final String ORACLE_JDBC_CONNECTION_NAME = "JDBC_CONNECTION";
    private static final String ORACLE_OCI_CONNECTION_NAME = "ORACLE_CONNECTION";

    private static final String VIRTUAL_SCHEMA_JDBC = "VIRTUAL_SCHEMA_JDBC";
    private static final String VIRTUAL_SCHEMA_ORACLE = "VIRTUAL_SCHEMA_ORACLE";
    private static final String VIRTUAL_SCHEMA_JDBC_NUMBER_TO_DECIMAL = "VIRTUAL_SCHEMA_JDBC_NUMBER_TO_DECIMAL";
    private static final String VIRTUAL_SCHEMA_ORACLE_NUMBER_TO_DECIMAL = "VIRTUAL_SCHEMA_ORACLE_NUMBER_TO_DECIMAL";

    private static final String TABLE_ORACLE_ALL_DATA_TYPES = "TABLE_ORACLE_ALL_DATA_TYPES";
    private static final String TABLE_ORACLE_NUMBER_HANDLING = "TABLE_ORACLE_NUMBER_HANDLING";
    private static final String TABLE_ORACLE_TIMESTAMPS = "TABLE_ORACLE_TIMESTAMPS";

    @Container
    private static final ExasolContainer<? extends ExasolContainer<?>> exasolContainer = new ExasolContainer<>(
            EXASOL_DOCKER_IMAGE_REFERENCE) //
            .withLogConsumer(new Slf4jLogConsumer(LOGGER));
    @Container
    private static final OracleContainerDBA oracleContainer = new OracleContainerDBA(ORACLE_CONTAINER_NAME);

    private static Statement statementExasol;

    @BeforeAll
    static void beforeAll() throws BucketAccessException, TimeoutException, SQLException, FileNotFoundException, InterruptedException {
        setupOracleDbContainer();
        setupExasolContainer();
    }

    private static void uploadInstantClientToBucket()
            throws BucketAccessException, TimeoutException, FileNotFoundException {
        final Bucket bucket = exasolContainer.getDefaultBucket();
        final String instantClientName = getPropertyFromFile(RESOURCES_FOLDER_DIALECT_NAME, "instant.client.name");
        final String instantClientPath = getPropertyFromFile(RESOURCES_FOLDER_DIALECT_NAME, "instant.client.path");
        bucket.uploadFile(Path.of(instantClientPath, instantClientName), "drivers/oracle/" + instantClientName);
    }

    private static void setupExasolContainer() throws BucketAccessException, TimeoutException, FileNotFoundException, SQLException, InterruptedException {
        uploadOracleJDBCDriverAndVSToBucket(  exasolContainer.getDefaultBucket());
        uploadInstantClientToBucket();
        final Connection exasolConnection = exasolContainer.createConnectionForUser(exasolContainer.getUsername(),
                exasolContainer.getPassword());
        statementExasol = exasolConnection.createStatement();

        final ExasolObjectFactory exasolFactory = new ExasolObjectFactory(exasolContainer.createConnection(""));
        final ExasolSchema schema = exasolFactory.createSchema(SCHEMA_EXASOL);

        final AdapterScript adapterScript = createAdapterScript(schema);

        final Integer mappedPort = oracleContainer.getMappedPort(ORACLE_PORT);
        final String oracleUsername = "SYSTEM";
        final String oraclePassword = "test";
        createOracleOCIConnection(exasolFactory, mappedPort, oracleUsername, oraclePassword);
        ConnectionDefinition jdbcConnectionDefinition = createOracleJDBCConnection(oracleUsername, oraclePassword, exasolFactory);

        createVirtualSchemasOnExasolDbContainer(exasolFactory, adapterScript, jdbcConnectionDefinition);
    }

    private static ConnectionDefinition createOracleOCIConnection(final ExasolObjectFactory exasolFactory,
                                                                  final Integer mappedPort, final String oracleUsername, final String oraclePassword) {
        final String oraConnectionString = "(DESCRIPTION =" //
                + "(ADDRESS_LIST = (ADDRESS = (PROTOCOL = TCP)" //
                + "(HOST = " + exasolContainer.getHostIp() + " )" //
                + "(PORT = " + oracleContainer.getOraclePort() + ")))" //
                + "(CONNECT_DATA = (SERVER = DEDICATED)" //
                + "(SERVICE_NAME = " + oracleContainer.getDatabaseName() + ")))";
        return exasolFactory.createConnectionDefinition(ORACLE_OCI_CONNECTION_NAME, oraConnectionString, oracleUsername,
                oraclePassword);
    }

    private static ConnectionDefinition createOracleJDBCConnection(String oracleUsername, String oraclePassword, ExasolObjectFactory exasolFactory) {
        final String jdbcConnectionString = "jdbc:oracle:thin:@" + exasolContainer.getHostIp() + ":"
                + oracleContainer.getOraclePort() + "/" + oracleContainer.getDatabaseName();

        return exasolFactory.createConnectionDefinition(ORACLE_JDBC_CONNECTION_NAME, jdbcConnectionString, oracleUsername, oraclePassword);

    }

    private static void createVirtualSchemasOnExasolDbContainer(ExasolObjectFactory exasolFactory, AdapterScript adapterScript, ConnectionDefinition jdbcConnectionDefinition) {
        exasolFactory.createVirtualSchemaBuilder(VIRTUAL_SCHEMA_JDBC).adapterScript(adapterScript)
                .connectionDefinition(jdbcConnectionDefinition).properties(Map.of("SCHEMA_NAME", SCHEMA_ORACLE))
                .build();
        exasolFactory.createVirtualSchemaBuilder(VIRTUAL_SCHEMA_JDBC_NUMBER_TO_DECIMAL).adapterScript(adapterScript)
                .connectionDefinition(jdbcConnectionDefinition).properties(Map.of("SCHEMA_NAME", SCHEMA_ORACLE))
                .properties(Map.of("SCHEMA_NAME", SCHEMA_ORACLE,
                        "oracle_cast_number_to_decimal_with_precision_and_scale", "36,1"))
                .build();
        exasolFactory.createVirtualSchemaBuilder(VIRTUAL_SCHEMA_ORACLE).adapterScript(adapterScript)
                .connectionDefinition(jdbcConnectionDefinition).properties(Map.of("SCHEMA_NAME", SCHEMA_ORACLE,
                        "IMPORT_FROM_ORA", "true", "ORA_CONNECTION_NAME", ORACLE_OCI_CONNECTION_NAME))
                .build();
        exasolFactory.createVirtualSchemaBuilder(VIRTUAL_SCHEMA_ORACLE_NUMBER_TO_DECIMAL).adapterScript(adapterScript)
                .connectionDefinition(jdbcConnectionDefinition)
                .properties(Map.of("SCHEMA_NAME", SCHEMA_ORACLE, "IMPORT_FROM_ORA", "true", "ORA_CONNECTION_NAME",
                        ORACLE_OCI_CONNECTION_NAME, "oracle_cast_number_to_decimal_with_precision_and_scale", "36,1"))
                .build();
    }

    private static void setupOracleDbContainer() throws SQLException {
        var oracleConnection = oracleContainer.createConnectionDBA("");
        final Statement statementOracle = oracleConnection.createStatement();
        createOracleUser(statementOracle);
        grantAdditionalRights(statementOracle);
        createOracleTableAllDataTypes(statementOracle);
        createOracleTableNumberHandling(statementOracle);
        createOracleTableTimestamps(statementOracle);
        createTestTablesForJoinTests(oracleContainer.createConnectionDBA(""), SCHEMA_ORACLE);
    }

    private static void grantAdditionalRights(final Statement statementOracle) throws SQLException {
        statementOracle.execute("GRANT CONNECT TO test");
        statementOracle.execute("GRANT CREATE SESSION TO test");
        statementOracle.execute("GRANT UNLIMITED TABLESPACE TO test");
    }

    private static void createOracleUser(final Statement statementOracle) throws SQLException {
        final String username = SCHEMA_ORACLE;
        final String password = SCHEMA_ORACLE;
        statementOracle.execute("CREATE USER " + username + " IDENTIFIED BY " + password);
        statementOracle.execute("GRANT CONNECT TO " + username);
        statementOracle.execute("GRANT CREATE SESSION TO " + username);
        statementOracle.execute("GRANT UNLIMITED TABLESPACE TO " + username);
    }

    private static void createOracleTableAllDataTypes(final Statement statementOracle) throws SQLException {
        final String qualifiedTableName = SCHEMA_ORACLE + "." + TABLE_ORACLE_ALL_DATA_TYPES;
        statementOracle.execute("CREATE TABLE " + qualifiedTableName + " (" //
                + "c1 char(50),	" //
                + "c2 nchar(50), " //
                + "c3 varchar2(50), " //
                + "c4 nvarchar2(50), " //
                + "c5 number, " //
                + "c_number36 number(36), " //
                + "c6 number(38), " //
                + "c7 number(10,5), " //
                + "c_binfloat binary_float, " //
                + "c_bindouble binary_double, " //
                + "c10 date, " //
                + "c11 timestamp(3), " //
                + "c12 timestamp, " //
                + "c13 timestamp(9), " //
                + "c14 timestamp with time zone, " //
                + "c15 timestamp with local time zone, " //
                + "c16 interval year to month, " //
                + "c17 interval day to second, " //
                + "c18 blob, " //
                + "c19 clob,	" //
                + "c20 nclob, " //
                + "c_float float, " //
                + "c_float126 float(126), " //
                + "c_long long " //
                + ")");
        statementOracle.execute("INSERT INTO " + qualifiedTableName + " VALUES (" //
                + "'aaaaaaaaaaaaaaaaaaaa', " //
                + "'bbbbbbbbbbbbbbbbbbbb', " //
                + "'cccccccccccccccccccc', " //
                + "'dddddddddddddddddddd', " //
                + "123456789012345678901234567890123456, " // C5
                + "123456789012345678901234567890123456, " // c_number36
                + "12345678901234567890123456789012345678, " // C6
                + "12345.12345, " // C7
                + "1234.1241723, " // C_BINFLOAT
                + "1234987.120871234, " // C_BINDOUBLE
                + "TO_DATE('2016-08-19', 'YYYY-MM-DD'), " // C10
                + "TO_TIMESTAMP('2013-03-11 17:30:15.123', 'YYYY-MM-DD HH24:MI:SS.FF'), " // C11
                + "TO_TIMESTAMP('2013-03-11 17:30:15.123456', 'YYYY-MM-DD HH24:MI:SS.FF'), " // C12
                + "TO_TIMESTAMP('2013-03-11 17:30:15.123456789', 'YYYY-MM-DD HH24:MI:SS.FF'), " // C13
                + "TO_TIMESTAMP_TZ('2016-08-19 11:28:05 -08:00', 'YYYY-MM-DD HH24:MI:SS TZH:TZM'), " // C14
                + "TO_TIMESTAMP_TZ('2018-04-30 10:00:05 -08:00', 'YYYY-MM-DD HH24:MI:SS TZH:TZM'), " // C15
                + "'54-2', " // C16
                + "'1 11:12:10.123', " // C17
                + "'0102030405060708090a0b0c0d0e0f', " // C18
                + "'0987asdlfkjq2222qawsf;lkja09ed8q2w;43lkrjasdf09uqaw43lkjra0-98sf[iqjw4,mfas[dpiuj[qa09w44', " // C19
                + "'0987asdlfkjq2222qawsf;lkja09ed8q2w;43lkrjasdf09uqaw43lkjra0-98sf[iqjw4,mfas[dpiuj[qa09w44', " // C20
                + "12345.01982348239, " // c_float
                + "12345678.01234567901234567890123456789, " // c_float126
                + "'test long 123' " // long
                + ")");
        statementOracle.execute("INSERT INTO " + qualifiedTableName + "(c3, c5, c7, c_binfloat, c17) VALUES (" //
                + "'cccccccccccccccccccc', " // C3
                + "1234567890.123456789, " // C5
                + "12355.12345, " // C7
                + "123.12345687987654321, " // C_BINFLOAT
                + "'2 02:03:04.123456' " // C17
                + ")");
    }

    private static void createOracleTableNumberHandling(final Statement statementOracle) throws SQLException {
        final String qualifiedTableName = SCHEMA_ORACLE + "." + TABLE_ORACLE_NUMBER_HANDLING;
        statementOracle.execute("CREATE TABLE " + qualifiedTableName + " (" //
                + "a number,	" //
                + "b number(38, 10), " //
                + "c number(36,2) " //
                + ")");
        statementOracle.execute("INSERT INTO " + qualifiedTableName + " VALUES (" //
                + "1234567890123456789012345678901234.56, " //
                + "1234567890123456789012345678.9012345678, " //
                + "1234567890123456789012345678901234.56 " //
                + ")");
    }

    private static void createOracleTableTimestamps(final Statement statementOracle) throws SQLException {
        final String qualifiedTableName = SCHEMA_ORACLE + "." + TABLE_ORACLE_TIMESTAMPS;
        statementOracle.execute("CREATE TABLE " + qualifiedTableName + " (" //
                + "a timestamp,	" //
                + "b timestamp with local time zone, " //
                + "c timestamp with time zone " //
                + ")");
        statementOracle.execute("INSERT INTO " + qualifiedTableName + " VALUES (" //
                + "timestamp '2018-01-01 11:00:00', " //
                + "timestamp '2018-01-01 11:00:00 +01:00', " //
                + "timestamp '2018-01-01 11:00:00 +01:00' " //
                + ")");
    }

    private static void createTestTablesForJoinTests(final Connection connection, final String schemaName)
            throws SQLException {
        try (final Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + schemaName + "." + TABLE_JOIN_1 + "(x INT, y VARCHAR(100))");
            statement.execute("INSERT INTO " + schemaName + "." + TABLE_JOIN_1 + " VALUES (1,'aaa')");
            statement.execute("INSERT INTO " + schemaName + "." + TABLE_JOIN_1 + " VALUES (2,'bbb')");
            statement.execute("CREATE TABLE " + schemaName + "." + TABLE_JOIN_2 + "(x INT, y VARCHAR(100))");
            statement.execute("INSERT INTO " + schemaName + "." + TABLE_JOIN_2 + " VALUES (2,'bbb')");
            statement.execute("INSERT INTO " + schemaName + "." + TABLE_JOIN_2 + " VALUES (3,'ccc')");
        }
    }

    private ResultSet getExpectedResultSet(final List<String> expectedColumns, final List<String> expectedRows)
            throws SQLException {
        final Connection connection = getExasolConnection();
        try (final Statement statement = connection.createStatement()) {
            final String expectedValues = expectedRows.stream().map(row -> "(" + row + ")")
                    .collect(Collectors.joining(","));
            final String qualifiedExpectedTableName = SCHEMA_EXASOL + ".EXPECTED";
            statement.execute("CREATE OR REPLACE TABLE " + qualifiedExpectedTableName + "("
                    + String.join(", ", expectedColumns) + ")");
            statement.execute("INSERT INTO " + qualifiedExpectedTableName + " VALUES" + expectedValues);
            return statement.executeQuery("SELECT * FROM " + qualifiedExpectedTableName);
        }
    }

    private ResultSet getActualResultSet(final String query) throws SQLException {
        final Connection connection = getExasolConnection();
        try (final Statement statement = connection.createStatement()) {
            return statement.executeQuery(query);
        }
    }

    private Connection getExasolConnection() throws SQLException {
        return exasolContainer.createConnection("");
    }

    @Test
    void testCountAll() throws SQLException {
        final String qualifiedTableName = VIRTUAL_SCHEMA_JDBC + "." + TABLE_ORACLE_NUMBER_HANDLING;
        final String query = "SELECT COUNT(*) FROM " + qualifiedTableName;
        final ResultSet expected = getExpectedResultSet(List.of("x DECIMAL(36,0)"), //
                List.of("1"));
        assertThat(getActualResultSet(query), matchesResultSet(expected));
    }

    @Nested
    @DisplayName("Number handling test")
    class numberHandlingTest {
        @ParameterizedTest
        @ValueSource(strings = {VIRTUAL_SCHEMA_JDBC_NUMBER_TO_DECIMAL, VIRTUAL_SCHEMA_ORACLE_NUMBER_TO_DECIMAL})
        void testNumberToDecimalThrowsException(final String virtualSchemaName) {
            final String qualifiedTableName = virtualSchemaName + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT c5 FROM " + qualifiedTableName;
            final SQLException exception = assertThrows(SQLException.class, () -> statementExasol.execute(query));
            assertThat(exception.getMessage(),
                    containsString("value larger than specified precision allowed for this column"));
        }

        @ParameterizedTest
        @ValueSource(strings = {VIRTUAL_SCHEMA_JDBC_NUMBER_TO_DECIMAL, VIRTUAL_SCHEMA_ORACLE_NUMBER_TO_DECIMAL})
        void testNumber36ToDecimal(final String virtualSchemaName) throws SQLException {
            final String qualifiedTableName = virtualSchemaName + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT c_number36 FROM " + qualifiedTableName;
            assertAll(
                    () -> assertExpressionExecutionBigDecimalResult(query,
                            new BigDecimal("123456789012345678901234567890123456")),
                    () -> assertThat(getColumnTypesOfTable(qualifiedTableName, "C_NUMBER36"),
                            equalTo("DECIMAL(36,0)")));
        }

        @ParameterizedTest
        @ValueSource(strings = {VIRTUAL_SCHEMA_JDBC_NUMBER_TO_DECIMAL, VIRTUAL_SCHEMA_ORACLE_NUMBER_TO_DECIMAL})
        void testNumber38ToDecimalThrowsException(final String virtualSchemaName) {
            final String qualifiedTableName = virtualSchemaName + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT c6 FROM " + qualifiedTableName;
            final SQLException exception = assertThrows(SQLException.class, () -> statementExasol.execute(query));
            assertThat(exception.getMessage(),
                    containsString("value larger than specified precision allowed for this column"));
        }

        @ParameterizedTest
        @ValueSource(strings = {VIRTUAL_SCHEMA_JDBC_NUMBER_TO_DECIMAL, VIRTUAL_SCHEMA_ORACLE_NUMBER_TO_DECIMAL})
        void testNumber10S5ToDecimal(final String virtualSchemaName) {
            final String qualifiedTableName = virtualSchemaName + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT C7 FROM " + qualifiedTableName;
            assertAll(() -> assertExpressionExecutionBigDecimalResult(query, new BigDecimal("12345.12345")),
                    () -> assertThat(getColumnTypesOfTable(qualifiedTableName, "C7"), equalTo("DECIMAL(10,5)")));
        }

        @Test
        void testSelectAllColsNumberFromJDBC() throws SQLException {
            final String qualifiedTableNameActual = VIRTUAL_SCHEMA_JDBC_NUMBER_TO_DECIMAL + "."
                    + TABLE_ORACLE_NUMBER_HANDLING;
            final ResultSet expected = getExpectedResultSet("(A DECIMAL(36,1), B DECIMAL(36,1), C DECIMAL(36,2))",
                    "(1234567890123456789012345678901234.6, 1234567890123456789012345678.9, 1234567890123456789012345678901234.56)");
            assertThat(statementExasol.executeQuery("SELECT * FROM " + qualifiedTableNameActual), //
                    matchesResultSet(expected));
        }

        @Test
        void testSelectAllColsNumberFromOra() throws SQLException {
            final String qualifiedTableNameActual = VIRTUAL_SCHEMA_ORACLE_NUMBER_TO_DECIMAL + "."
                    + TABLE_ORACLE_NUMBER_HANDLING;
            final ResultSet expected = getExpectedResultSet("(A VARCHAR(100), B VARCHAR(100), C VARCHAR(100))",
                    "('12.3456789012345678901234567890123460E32', '12.3456789012345678901234567890E26', '12.3456789012345678901234567890123456E32')");
            assertThat(statementExasol.executeQuery("SELECT * FROM " + qualifiedTableNameActual), //
                    matchesResultSet(expected));
        }

        @ParameterizedTest
        @ValueSource(strings = {VIRTUAL_SCHEMA_JDBC_NUMBER_TO_DECIMAL, VIRTUAL_SCHEMA_ORACLE_NUMBER_TO_DECIMAL})
        void testNumberDataTypes(final String virtualSchemaName) {
            final String qualifiedTableName = virtualSchemaName + "." + TABLE_ORACLE_NUMBER_HANDLING;
            assertAll(() -> assertThat(getColumnTypesOfTable(qualifiedTableName, "A"), equalTo("DECIMAL(36,1)")),
                    () -> assertThat(getColumnTypesOfTable(qualifiedTableName, "B"), equalTo("DECIMAL(36,1)")),
                    () -> assertThat(getColumnTypesOfTable(qualifiedTableName, "C"), equalTo("DECIMAL(36,2)")));
        }

        @ParameterizedTest
        @ValueSource(strings = {VIRTUAL_SCHEMA_JDBC_NUMBER_TO_DECIMAL, VIRTUAL_SCHEMA_ORACLE_NUMBER_TO_DECIMAL})
        void testSelectOneNumberColumn(final String virtualSchemaName) {
            final String qualifiedTableName = virtualSchemaName + "." + TABLE_ORACLE_NUMBER_HANDLING;
            assertAll(
                    () -> assertExpressionExecutionBigDecimalResult("SELECT A FROM " + qualifiedTableName,
                            new BigDecimal("1234567890123456789012345678901234.6")),
                    () -> assertExpressionExecutionBigDecimalResult("SELECT B FROM " + qualifiedTableName,
                            new BigDecimal("1234567890123456789012345678.9")),
                    () -> assertExpressionExecutionBigDecimalResult("SELECT C FROM " + qualifiedTableName,
                            new BigDecimal("1234567890123456789012345678901234.56")));
        }
    }

    private String getColumnTypesOfTable(final String tableName, final String columnName) throws SQLException {
        final ResultSet result = statementExasol.executeQuery("DESCRIBE " + tableName);
        while (result.next()) {
            if (result.getString("COLUMN_NAME").toUpperCase().equals(columnName)) {
                return result.getString("SQL_TYPE").toUpperCase();
            }
        }
        throw new IllegalArgumentException("Type for column " + columnName + " not found");
    }

    private ResultSet getExpectedResultSet(final String expectedColumnTypes, final String expectedValues)
            throws SQLException {
        final String qualifiedExpectedTableName = SCHEMA_EXASOL + "." + "EXPECTED";
        statementExasol.execute("CREATE OR REPLACE TABLE " + qualifiedExpectedTableName + expectedColumnTypes);
        statementExasol.execute("INSERT INTO " + qualifiedExpectedTableName + " VALUES" + expectedValues);
        return statementExasol.executeQuery("SELECT * FROM " + qualifiedExpectedTableName);
    }

    private void assertExpressionExecutionBigDecimalResult(final String query, final BigDecimal expectedValue)
            throws SQLException {
        final ResultSet result = statementExasol.executeQuery(query);
        result.next();
        final BigDecimal actualResult = result.getBigDecimal(1);
        assertThat(actualResult.stripTrailingZeros(), equalTo(expectedValue));
    }

    private void assertExplainVirtual(final String query, final String expected) throws SQLException {
        final ResultSet explainVirtual = statementExasol.executeQuery("EXPLAIN VIRTUAL " + query);
        explainVirtual.next();
        final String explainVirtualStringActual = explainVirtual.getString("PUSHDOWN_SQL");
        assertThat(explainVirtualStringActual, containsString(expected));
    }

    @Nested
    @DisplayName("Join test")
    class JoinTest {
        @ParameterizedTest
        @ValueSource(strings = {VIRTUAL_SCHEMA_JDBC, VIRTUAL_SCHEMA_ORACLE})
        void testInnerJoin(final String virtualSchema) throws SQLException {
            final String query = "SELECT * FROM " + virtualSchema + "." + TABLE_JOIN_1 + " a INNER JOIN  "
                    + virtualSchema + "." + TABLE_JOIN_2 + " b ON a.x=b.x";
            final ResultSet expected = getExpectedResultSet(
                    List.of("x VARCHAR(100)", "y VARCHAR(100)", "a VARCHAR(100)", "b VARCHAR(100)"), //
                    List.of("'2','bbb', '2','bbb'"));
            assertThat(getActualResultSet(query), matchesResultSet(expected));
        }

        @ParameterizedTest
        @ValueSource(strings = {VIRTUAL_SCHEMA_JDBC, VIRTUAL_SCHEMA_ORACLE})
        void testInnerJoinWithProjection(final String virtualSchemaName) throws SQLException {
            final String qualifiedJoinTableName1 = virtualSchemaName + "." + TABLE_JOIN_1;
            final String qualifiedJoinTableName2 = virtualSchemaName + "." + TABLE_JOIN_2;
            final String query = "SELECT b.y || " + qualifiedJoinTableName1 + ".y FROM " + qualifiedJoinTableName1
                    + " INNER JOIN  " + qualifiedJoinTableName2 + " b ON " + qualifiedJoinTableName1 + ".x=b.x";
            final ResultSet expected = getExpectedResultSet(List.of("y VARCHAR(100)"), //
                    List.of("'bbbbbb'"));
            assertThat(getActualResultSet(query), matchesResultSet(expected));
        }

        @ParameterizedTest
        @ValueSource(strings = {VIRTUAL_SCHEMA_JDBC, VIRTUAL_SCHEMA_ORACLE})
        void testLeftJoin(final String virtualSchemaName) throws SQLException {
            final String qualifiedJoinTableName1 = virtualSchemaName + "." + TABLE_JOIN_1;
            final String qualifiedJoinTableName2 = virtualSchemaName + "." + TABLE_JOIN_2;
            final String query = "SELECT * FROM " + qualifiedJoinTableName1 + " a LEFT OUTER JOIN  "
                    + qualifiedJoinTableName2 + " b ON a.x=b.x ORDER BY a.x";
            final ResultSet expected = getExpectedResultSet(
                    List.of("x VARCHAR(100)", "y VARCHAR(100)", "a VARCHAR(100)", "b VARCHAR(100)"), //
                    List.of("'1', 'aaa', null, null", //
                            "'2', 'bbb', '2', 'bbb'"));
            assertThat(getActualResultSet(query), matchesResultSet(expected));
        }

        @ParameterizedTest
        @ValueSource(strings = {VIRTUAL_SCHEMA_JDBC, VIRTUAL_SCHEMA_ORACLE})
        void testRightJoin(final String virtualSchemaName) throws SQLException {
            final String qualifiedJoinTableName1 = virtualSchemaName + "." + TABLE_JOIN_1;
            final String qualifiedJoinTableName2 = virtualSchemaName + "." + TABLE_JOIN_2;
            final String query = "SELECT * FROM " + qualifiedJoinTableName1 + " a RIGHT OUTER JOIN  "
                    + qualifiedJoinTableName2 + " b ON a.x=b.x ORDER BY a.x";
            final ResultSet expected = getExpectedResultSet(
                    List.of("x VARCHAR(100)", "y VARCHAR(100)", "a VARCHAR(100)", "b VARCHAR(100)"), //
                    List.of("'2', 'bbb', '2', 'bbb'", //
                            "null, null, '3', 'ccc'"));
            assertThat(getActualResultSet(query), matchesResultSet(expected));
        }

        @ParameterizedTest
        @ValueSource(strings = {VIRTUAL_SCHEMA_JDBC, VIRTUAL_SCHEMA_ORACLE})
        void testFullOuterJoin(final String virtualSchemaName) throws SQLException {
            final String qualifiedJoinTableName1 = virtualSchemaName + "." + TABLE_JOIN_1;
            final String qualifiedJoinTableName2 = virtualSchemaName + "." + TABLE_JOIN_2;
            final String query = "SELECT * FROM " + qualifiedJoinTableName1 + " a FULL OUTER JOIN  "
                    + qualifiedJoinTableName2 + " b ON a.x=b.x ORDER BY a.x";
            final ResultSet expected = getExpectedResultSet(
                    List.of("x VARCHAR(100)", "y VARCHAR(100)", "a VARCHAR(100)", "b VARCHAR(100)"), //
                    List.of("1, 'aaa', null, null", //
                            "'2', 'bbb', '2', 'bbb'", //
                            "null, null, '3', 'ccc'"));
            assertThat(getActualResultSet(query), matchesResultSet(expected));
        }

        @ParameterizedTest
        @ValueSource(strings = {VIRTUAL_SCHEMA_JDBC, VIRTUAL_SCHEMA_ORACLE})
        void testRightJoinWithComplexCondition(final String virtualSchemaName) throws SQLException {
            final String qualifiedJoinTableName1 = virtualSchemaName + "." + TABLE_JOIN_1;
            final String qualifiedJoinTableName2 = virtualSchemaName + "." + TABLE_JOIN_2;
            final String query = "SELECT * FROM " + qualifiedJoinTableName1 + " a RIGHT OUTER JOIN  "
                    + qualifiedJoinTableName2 + " b ON a.x||a.y=b.x||b.y ORDER BY a.x";
            final ResultSet expected = getExpectedResultSet(
                    List.of("x VARCHAR(100)", "y VARCHAR(100)", "a VARCHAR(100)", "b VARCHAR(100)"), //
                    List.of("'2', 'bbb', '2', 'bbb'", //
                            "null, null, '3', 'ccc'"));
            assertThat(getActualResultSet(query), matchesResultSet(expected));
        }

        @ParameterizedTest
        @ValueSource(strings = {VIRTUAL_SCHEMA_JDBC, VIRTUAL_SCHEMA_ORACLE})
        void testFullOuterJoinWithComplexCondition(final String virtualSchemaName) throws SQLException {
            final String qualifiedJoinTableName1 = virtualSchemaName + "." + TABLE_JOIN_1;
            final String qualifiedJoinTableName2 = virtualSchemaName + "." + TABLE_JOIN_2;
            final String query = "SELECT * FROM " + qualifiedJoinTableName1 + " a FULL OUTER JOIN  "
                    + qualifiedJoinTableName2 + " b ON a.x-b.x=0 ORDER BY a.x";
            final ResultSet expected = getExpectedResultSet(
                    List.of("x VARCHAR(100)", "y VARCHAR(100)", "a VARCHAR(100)", "b VARCHAR(100)"), //
                    List.of("1, 'aaa', null, null", //
                            "'2', 'bbb', '2', 'bbb'", //
                            "null, null, '3', 'ccc'"));
            assertThat(getActualResultSet(query), matchesResultSet(expected));
        }
    }

    @Nested
    @DisplayName("Datatype tests")
    class DatatypeTest {
        @ParameterizedTest
        @CsvSource(value = {"VIRTUAL_SCHEMA_JDBC, 12346.12345", //
                "VIRTUAL_SCHEMA_ORACLE, 01.2346123450E4"})
        void testSelectExpression(final String virtualSchemaName, final String expectedColumnValue) {
            final String qualifiedTableNameActual = virtualSchemaName + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT C7 + 1 FROM " + qualifiedTableNameActual + " ORDER BY 1";
            final String expectedExplainVirtual = "SELECT CAST((\"" + TABLE_ORACLE_ALL_DATA_TYPES
                    + "\".\"C7\" + 1) AS FLOAT) FROM \"" + SCHEMA_ORACLE + "\".\"" + TABLE_ORACLE_ALL_DATA_TYPES
                    + "\" ORDER BY (\"" + TABLE_ORACLE_ALL_DATA_TYPES + "\".\"C7\" + 1)";
            assertAll(() -> assertExpressionExecutionStringResult(query, expectedColumnValue),
                    () -> assertExplainVirtual(query, expectedExplainVirtual));
        }

        private void assertExpressionExecutionStringResult(final String query, final String expected)
                throws SQLException {
            final ResultSet result = statementExasol.executeQuery(query);
            result.next();
            final String actual = result.getString(1);
            MatcherAssert.assertThat(actual, containsString(expected));
        }

        @ParameterizedTest
        @CsvSource(value = {"VIRTUAL_SCHEMA_JDBC, 12355.12345", //
                "VIRTUAL_SCHEMA_ORACLE, 01.2355123450E4"})
        void testFilterExpression(final String virtualSchemaName, final String expectedColumnValue) {
            final String qualifiedTableNameActual = virtualSchemaName + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT C7 FROM " + qualifiedTableNameActual + " WHERE C7 > 12346";
            final String expectedExplainVirtual = "SELECT \"" + TABLE_ORACLE_ALL_DATA_TYPES + "\".\"C7\" FROM \""
                    + SCHEMA_ORACLE + "\".\"" + TABLE_ORACLE_ALL_DATA_TYPES + "\" WHERE 12346 < \""
                    + TABLE_ORACLE_ALL_DATA_TYPES + "\".\"C7\"";
            assertAll(() -> assertExpressionExecutionStringResult(query, expectedColumnValue),
                    () -> assertExplainVirtual(query, expectedExplainVirtual));
        }

        @ParameterizedTest
        @CsvSource(value = {"VIRTUAL_SCHEMA_JDBC, 12345.12345", //
                "VIRTUAL_SCHEMA_ORACLE, 01.2345123450E4"})
        void testAggregateSingleGroup(final String virtualSchemaName, final String expectedColumnValue) {
            final String qualifiedTableNameActual = virtualSchemaName + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT min(C7) FROM " + qualifiedTableNameActual;
            final String expectedExplainVirtual = "SELECT CAST(MIN(\"" + TABLE_ORACLE_ALL_DATA_TYPES
                    + "\".\"C7\") AS FLOAT) FROM \"" + SCHEMA_ORACLE + "\".\"" + TABLE_ORACLE_ALL_DATA_TYPES + "\"";
            assertAll(() -> assertExpressionExecutionStringResult(query, expectedColumnValue),
                    () -> assertExplainVirtual(query, expectedExplainVirtual));
        }

        @Test
        void testAggregateGroupByColumnJdbc() throws SQLException {
            final String qualifiedActualTableName = VIRTUAL_SCHEMA_JDBC + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT C5, min(C7) FROM " + qualifiedActualTableName + " GROUP BY C5 ORDER BY 1 DESC";
            final ResultSet expected = getExpectedResultSet("(A VARCHAR(100), B VARCHAR(100))",
                    "('123456789012345678901234567890123456', '12345.12345')," //
                            + "('1234567890.123456789', '12355.12345')");
            final ResultSet actual = statementExasol.executeQuery(query);
            final String expectedExplainVirtual = "SELECT TO_CHAR(\"" + TABLE_ORACLE_ALL_DATA_TYPES
                    + "\".\"C5\"), CAST(MIN(\"" + TABLE_ORACLE_ALL_DATA_TYPES + "\".\"C7\") AS FLOAT) FROM \""
                    + SCHEMA_ORACLE + "\".\"" + TABLE_ORACLE_ALL_DATA_TYPES + "\" GROUP BY \""
                    + TABLE_ORACLE_ALL_DATA_TYPES + "\".\"C5\" ORDER BY \"" + TABLE_ORACLE_ALL_DATA_TYPES
                    + "\".\"C5\" DESC";
            assertAll(() -> assertThat(actual, matchesResultSet(expected)),
                    () -> assertExplainVirtual(query, expectedExplainVirtual));
        }

        @Test
        void testAggregateGroupByExpressionOra() throws SQLException {
            final String qualifiedActualTableName = VIRTUAL_SCHEMA_ORACLE + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT C5 + 1, min(C7) FROM " + qualifiedActualTableName
                    + " GROUP BY C5 + 1 ORDER BY 1 DESC";
            final ResultSet expected = getExpectedResultSet("(A VARCHAR(100), B VARCHAR(100))",
                    "('12.3456789012345678901234567890123457E34', '01.2345123450E4')," //
                            + "('12.345678911234567890E8', '01.2355123450E4')");
            final ResultSet actual = statementExasol.executeQuery(query);
            final String expectedExplainVirtual = "SELECT CAST((\"" + TABLE_ORACLE_ALL_DATA_TYPES
                    + "\".\"C5\" + 1) AS FLOAT), CAST(MIN(\"" + TABLE_ORACLE_ALL_DATA_TYPES
                    + "\".\"C7\") AS FLOAT) FROM \"" + SCHEMA_ORACLE + "\".\"" + TABLE_ORACLE_ALL_DATA_TYPES
                    + "\" GROUP BY (\"" + TABLE_ORACLE_ALL_DATA_TYPES + "\".\"C5\" + 1) ORDER BY (\""
                    + TABLE_ORACLE_ALL_DATA_TYPES + "\".\"C5\" + 1) DESC";
            assertAll(() -> assertThat(actual, matchesResultSet(expected)),
                    () -> assertExplainVirtual(query, expectedExplainVirtual));
        }

        @Test
        void testAggregateGroupByTuple() throws SQLException {
            final String qualifiedActualTableName = VIRTUAL_SCHEMA_JDBC + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT C_NUMBER36, C5, min(C7) FROM " + qualifiedActualTableName
                    + " GROUP BY C_NUMBER36, C5 ORDER BY C5 DESC";
            final ResultSet expected = getExpectedResultSet("(A DECIMAL(36,0), B VARCHAR(100), C VARCHAR(100))",
                    "(123456789012345678901234567890123456, '123456789012345678901234567890123456', '12345.12345')," //
                            + "(null, '1234567890.123456789', '12355.12345')");
            final ResultSet actual = statementExasol.executeQuery(query);
            final String expectedExplainVirtual = "SELECT \"" + TABLE_ORACLE_ALL_DATA_TYPES
                    + "\".\"C_NUMBER36\", TO_CHAR(\"" + TABLE_ORACLE_ALL_DATA_TYPES + "\".\"C5\"), CAST(MIN(\""
                    + TABLE_ORACLE_ALL_DATA_TYPES + "\".\"C7\") AS FLOAT) FROM \"" + SCHEMA_ORACLE + "\".\""
                    + TABLE_ORACLE_ALL_DATA_TYPES + "\" GROUP BY \"" + TABLE_ORACLE_ALL_DATA_TYPES + "\".\"C5\", \""
                    + TABLE_ORACLE_ALL_DATA_TYPES + "\".\"C_NUMBER36\" ORDER BY \"" + TABLE_ORACLE_ALL_DATA_TYPES
                    + "\".\"C5\" DESC";
            assertAll(() -> assertThat(actual, matchesResultSet(expected)),
                    () -> assertExplainVirtual(query, expectedExplainVirtual));
        }

        @Test
        void testAggregateHaving() throws SQLException {
            final String qualifiedActualTableName = VIRTUAL_SCHEMA_JDBC + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT C5, min(C7) FROM " + qualifiedActualTableName
                    + " GROUP BY C5 HAVING MIN(C7) > 12350";
            final ResultSet expected = getExpectedResultSet("(A VARCHAR(100), B VARCHAR(100))",
                    "('1234567890.123456789', '12355.12345')");
            final ResultSet actual = statementExasol.executeQuery(query);
            final String expectedExplainVirtual = "SELECT TO_CHAR(\"" + TABLE_ORACLE_ALL_DATA_TYPES
                    + "\".\"C5\"), CAST(MIN(\"" + TABLE_ORACLE_ALL_DATA_TYPES + "\".\"C7\") AS FLOAT) FROM \""
                    + SCHEMA_ORACLE + "\".\"" + TABLE_ORACLE_ALL_DATA_TYPES + "\" GROUP BY \""
                    + TABLE_ORACLE_ALL_DATA_TYPES + "\".\"C5\" HAVING 12350 < MIN(\"" + TABLE_ORACLE_ALL_DATA_TYPES
                    + "\".\"C7\")";
            assertAll(() -> assertThat(actual, matchesResultSet(expected)),
                    () -> assertExplainVirtual(query, expectedExplainVirtual));
        }

        @ParameterizedTest
        @ValueSource(strings = {VIRTUAL_SCHEMA_JDBC, VIRTUAL_SCHEMA_ORACLE})
        void testOrderByColumn(final String virtualSchemaName) {
            final String qualifiedTableNameActual = virtualSchemaName + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT C1 FROM " + qualifiedTableNameActual + " ORDER BY C1 DESC NULLS LAST";
            final String expectedExplainVirtual = "SELECT \"" + TABLE_ORACLE_ALL_DATA_TYPES + "\".\"C1\" FROM \""
                    + SCHEMA_ORACLE + "\".\"" + TABLE_ORACLE_ALL_DATA_TYPES + "\" ORDER BY \""
                    + TABLE_ORACLE_ALL_DATA_TYPES + "\".\"C1\" DESC NULLS LAST";
            assertAll(() -> assertExpressionExecutionStringResult(query, "aaaaaaaaaaaaaaaaaaaa"),
                    () -> assertExplainVirtual(query, expectedExplainVirtual));
        }

        @Test
        void testOrderByExpressionJdbc() throws SQLException {
            final String qualifiedActualTableName = VIRTUAL_SCHEMA_JDBC + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT C7 FROM " + qualifiedActualTableName + " ORDER BY ABS(C7) DESC NULLS FIRST";
            final ResultSet expected = getExpectedResultSet("(A DECIMAL(36,5))", "(12355.12345), (12345.12345)");
            final ResultSet actual = statementExasol.executeQuery(query);
            final String expectedExplainVirtual = "SELECT \"" + TABLE_ORACLE_ALL_DATA_TYPES + "\".\"C7\" FROM \""
                    + SCHEMA_ORACLE + "\".\"" + TABLE_ORACLE_ALL_DATA_TYPES + "\" ORDER BY ABS(\""
                    + TABLE_ORACLE_ALL_DATA_TYPES + "\".\"C7\") DESC";
            assertAll(() -> assertThat(actual, matchesResultSet(expected)),
                    () -> assertExplainVirtual(query, expectedExplainVirtual));
        }

        @Test
        void testLimit() throws SQLException {
            final String qualifiedActualTableName = VIRTUAL_SCHEMA_JDBC + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT C7 FROM " + qualifiedActualTableName + " ORDER BY C7 LIMIT 2";
            final ResultSet expected = getExpectedResultSet("(A DECIMAL(36,5))", "(12345.12345), (12355.12345)");
            final ResultSet actual = statementExasol.executeQuery(query);
            final String expectedExplainVirtual = "SELECT LIMIT_SUBSELECT.* FROM ( SELECT \""
                    + TABLE_ORACLE_ALL_DATA_TYPES + "\".\"C7\" FROM \"" + SCHEMA_ORACLE + "\".\""
                    + TABLE_ORACLE_ALL_DATA_TYPES + "\" ORDER BY \"" + TABLE_ORACLE_ALL_DATA_TYPES
                    + "\".\"C7\"  ) LIMIT_SUBSELECT WHERE ROWNUM <= 2";
            assertAll(() -> assertThat(actual, matchesResultSet(expected)),
                    () -> assertExplainVirtual(query, expectedExplainVirtual));
        }

        @Test
        void testLimitOffset() throws SQLException {
            final String qualifiedActualTableName = VIRTUAL_SCHEMA_JDBC + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT C7 FROM " + qualifiedActualTableName + " ORDER BY C7 LIMIT 1 OFFSET 1";
            final ResultSet expected = getExpectedResultSet("(A DECIMAL(36,5))", "(12355.12345)");
            final ResultSet actual = statementExasol.executeQuery(query);
            final String expectedExplainVirtual = "SELECT LIMIT_SUBSELECT.*, ROWNUM ROWNUM_SUB FROM ( SELECT \""
                    + TABLE_ORACLE_ALL_DATA_TYPES + "\".\"C7\" AS c0 FROM \"" + SCHEMA_ORACLE + "\".\""
                    + TABLE_ORACLE_ALL_DATA_TYPES + "\" ORDER BY \"" + TABLE_ORACLE_ALL_DATA_TYPES
                    + "\".\"C7\"  ) LIMIT_SUBSELECT WHERE ROWNUM <= 2 ) WHERE ROWNUM_SUB > 1";
            assertAll(() -> assertThat(actual, matchesResultSet(expected)),
                    () -> assertExplainVirtual(query, expectedExplainVirtual));
        }

        @ParameterizedTest
        @CsvSource(value = { //
                "VIRTUAL_SCHEMA_JDBC, C1, CHAR(50) ASCII, aaaaaaaaaaaaaaaaaaaa", //
                "VIRTUAL_SCHEMA_JDBC, C2, CHAR(50) UTF8, bbbbbbbbbbbbbbbbbbbb", //
                "VIRTUAL_SCHEMA_JDBC, C3, VARCHAR(50) ASCII, cccccccccccccccccccc", //
                "VIRTUAL_SCHEMA_JDBC, C4, VARCHAR(50) UTF8, dddddddddddddddddddd", //
                "VIRTUAL_SCHEMA_ORACLE, C1, CHAR(50) ASCII, aaaaaaaaaaaaaaaaaaaa", //
                "VIRTUAL_SCHEMA_ORACLE, C2, CHAR(50) UTF8, bbbbbbbbbbbbbbbbbbbb", //
                "VIRTUAL_SCHEMA_ORACLE, C3, VARCHAR(50) ASCII, cccccccccccccccccccc", //
                "VIRTUAL_SCHEMA_ORACLE, C4, VARCHAR(50) UTF8, dddddddddddddddddddd" //
        })
        void testCharactersColumns(final String virtualSchemaName, final String columnName,
                                   final String expectedColumnType, final String expectedColumnValue) {
            final String qualifiedTableName = virtualSchemaName + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT " + columnName + " FROM " + qualifiedTableName;
            assertAll(() -> assertExpressionExecutionStringResult(query, expectedColumnValue),
                    () -> assertThat(getColumnTypesOfTable(qualifiedTableName, columnName),
                            equalTo(expectedColumnType)));
        }

        @ParameterizedTest
        @CsvSource(value = { //
                "VIRTUAL_SCHEMA_JDBC, C18", //
                "VIRTUAL_SCHEMA_JDBC, C19", //
                "VIRTUAL_SCHEMA_JDBC, C20", //
                "VIRTUAL_SCHEMA_ORACLE, C18", //
                "VIRTUAL_SCHEMA_ORACLE, C19", //
                "VIRTUAL_SCHEMA_ORACLE, C20",//
        })
        void testBlobColumns(final String virtualSchemaName, final String columnName) {
            final String qualifiedTableName = virtualSchemaName + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT " + columnName + " FROM " + qualifiedTableName;
            final SQLException exception = assertThrows(SQLException.class, () -> statementExasol.execute(query));
            assertThat(exception.getMessage(), startsWith("object " + columnName + " not found"));
        }

        @ParameterizedTest
        @CsvSource(value = { //
                "VIRTUAL_SCHEMA_JDBC | C5 | VARCHAR(2000000) UTF8 | 123456789012345678901234567890123456", //
                "VIRTUAL_SCHEMA_JDBC | C_NUMBER36 | DECIMAL(36,0) | 123456789012345678901234567890123456", //
                "VIRTUAL_SCHEMA_JDBC | C6 | VARCHAR(2000000) UTF8 | 12345678901234567890123456789012345678", //
                "VIRTUAL_SCHEMA_JDBC | C7 | DECIMAL(10,5) | 12345.12345", //
                "VIRTUAL_SCHEMA_ORACLE | C5 | VARCHAR(2000000) UTF8 | 123456789012345678901234567890123456", //
                "VIRTUAL_SCHEMA_ORACLE | C_NUMBER36 | DECIMAL(36,0) | 123456789012345678901234567890123456", //
                "VIRTUAL_SCHEMA_ORACLE | C6 | VARCHAR(2000000) UTF8 | 12345678901234567890123456789012345678", //
                "VIRTUAL_SCHEMA_ORACLE | C7 | DECIMAL(10,5) | 12345.12345" //
        }, delimiter = '|')
        void testNumberColumns(final String virtualSchemaName, final String columnName, final String expectedColumnType,
                               final String expectedValue) {
            final String qualifiedTableName = virtualSchemaName + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT " + columnName + " FROM " + qualifiedTableName;
            assertAll(() -> assertExpressionExecutionBigDecimalResult(query, new BigDecimal(expectedValue)),
                    () -> assertThat(getColumnTypesOfTable(qualifiedTableName, columnName),
                            equalTo(expectedColumnType)));
        }

        @ParameterizedTest
        @CsvSource(value = { //
                "VIRTUAL_SCHEMA_JDBC | C_BINFLOAT | VARCHAR(2000000) UTF8 | 1234.1241723", //
                "VIRTUAL_SCHEMA_JDBC | C_FLOAT | DOUBLE | 12345.01982348239", //
                "VIRTUAL_SCHEMA_JDBC | C_FLOAT126 | DOUBLE | 12345678.01234567901234567890123456789", //
                "VIRTUAL_SCHEMA_ORACLE | C_BINFLOAT | VARCHAR(2000000) UTF8 | 1234.1241723", //
                "VIRTUAL_SCHEMA_ORACLE | C_FLOAT | DOUBLE | 12345.01982348239", //
                "VIRTUAL_SCHEMA_ORACLE | C_FLOAT126 | DOUBLE | 12345678.01234567901234567890123456789" //
        }, delimiter = '|')
        void testFloatNumbers(final String virtualSchemaName, final String columnName, final String expectedColumnType,
                              final String expectedValue) {
            final String qualifiedTableName = virtualSchemaName + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT " + columnName + " FROM " + qualifiedTableName;
            assertAll(() -> assertExpressionExecutionFloatResult(query, Float.parseFloat(expectedValue)),
                    () -> assertThat(getColumnTypesOfTable(qualifiedTableName, columnName),
                            equalTo(expectedColumnType)));
        }

        private void assertExpressionExecutionFloatResult(final String query, final float expected)
                throws SQLException {
            final ResultSet result = statementExasol.executeQuery(query);
            result.next();
            final double actualResult = result.getFloat(1);
            assertEquals(expected, actualResult, 0.000000001);
        }

        @ParameterizedTest
        @ValueSource(strings = {VIRTUAL_SCHEMA_JDBC, VIRTUAL_SCHEMA_ORACLE})
        void testBinaryDouble(final String virtualSchemaName) {
            final String qualifiedTableName = virtualSchemaName + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT C_BINDOUBLE FROM " + qualifiedTableName;
            assertAll(() -> assertExpressionExecutionDoubleResult(query, Double.parseDouble("1234987.120871234")),
                    () -> assertThat(getColumnTypesOfTable(qualifiedTableName, "C_BINDOUBLE"),
                            equalTo("VARCHAR(2000000) UTF8")));
        }

        private void assertExpressionExecutionDoubleResult(final String query, final double expected)
                throws SQLException {
            final ResultSet result = statementExasol.executeQuery(query);
            result.next();
            final double actualResult = result.getDouble(1);
            MatcherAssert.assertThat(actualResult, equalTo(expected));
        }

        @Test
        void testLongJdbc() {
            final String qualifiedTableName = VIRTUAL_SCHEMA_JDBC + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT C_LONG FROM " + qualifiedTableName;
            assertAll(() -> assertExpressionExecutionStringResult(query, "test long 123"),
                    () -> assertThat(getColumnTypesOfTable(qualifiedTableName, "C_LONG"),
                            equalTo("VARCHAR(2000000) ASCII")));
        }

        @Test
        void testLongOra() {
            final String qualifiedTableName = VIRTUAL_SCHEMA_ORACLE + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT C_LONG FROM " + qualifiedTableName;
            final SQLException exception = assertThrows(SQLException.class, () -> statementExasol.execute(query));
            assertThat(exception.getMessage(),
                    containsString("Unknown Oracle OCI column data type (8) found for column 'C_LONG'"));
        }

        @ParameterizedTest
        @ValueSource(strings = {VIRTUAL_SCHEMA_JDBC, VIRTUAL_SCHEMA_ORACLE})
        void testDate(final String virtualSchemaName) {
            final String qualifiedTableName = virtualSchemaName + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT C10 FROM " + qualifiedTableName;
            assertAll(() -> assertExpressionExecutionDateResult(query, Date.valueOf("2016-08-19")),
                    () -> assertThat(getColumnTypesOfTable(qualifiedTableName, "C10"), equalTo("TIMESTAMP")));
        }

        private void assertExpressionExecutionDateResult(final String query, final Date expected) throws SQLException {
            final ResultSet result = statementExasol.executeQuery(query);
            result.next();
            final Date actualResult = result.getDate(1);
            MatcherAssert.assertThat(actualResult, equalTo(expected));
        }

        @ParameterizedTest
        @CsvSource(value = { //
                "C11, 2013-03-11 17:30:15.123", //
                "C12, 2013-03-11 17:30:15.123", //
                "C13, 2013-03-11 17:30:15.123", //
                "C14, 2016-08-19 11:28:05.0", //
                "C15, 2018-04-30 19:00:05.0" //
        })
        void testTimestampsJdbc(final String columnName, final String expectedColumnValue) {
            final String qualifiedTableName = VIRTUAL_SCHEMA_JDBC + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT " + columnName + " FROM " + qualifiedTableName;
            final String expectedExplainVirtual = "SELECT TO_TIMESTAMP(TO_CHAR(\"" + TABLE_ORACLE_ALL_DATA_TYPES
                    + "\".\"" + columnName + "\", ''YYYY-MM-DD HH24:MI:SS.FF3''), "
                    + "''YYYY-MM-DD HH24:MI:SS.FF3'') FROM \"" + SCHEMA_ORACLE + "\".\"" + TABLE_ORACLE_ALL_DATA_TYPES
                    + "\"";
            assertAll(() -> assertExpressionExecutionTimestampResult(query, Timestamp.valueOf(expectedColumnValue)),
                    () -> assertThat(getColumnTypesOfTable(qualifiedTableName, columnName), equalTo("TIMESTAMP")),
                    () -> assertExplainVirtual(query, expectedExplainVirtual));
        }

        @ParameterizedTest
        @CsvSource(value = { //
                "C11, 2013-03-11 17:30:15.123", //
                "C12, 2013-03-11 17:30:15.123", //
                "C13, 2013-03-11 17:30:15.123", //
                "C14, 2016-08-19 19:28:05.0", //
                "C15, 2018-04-30 18:00:05.0" //
        })
        void testTimestampOra(final String columnName, final String expectedColumnValue) throws SQLException {
            statementExasol.execute("ALTER SESSION SET TIME_ZONE = 'UTC'");
            final String qualifiedTableName = VIRTUAL_SCHEMA_ORACLE + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT " + columnName + " FROM " + qualifiedTableName;
            final String expectedExplainVirtual = "SELECT \"" + TABLE_ORACLE_ALL_DATA_TYPES + "\".\"" + columnName
                    + "\" FROM \"" + SCHEMA_ORACLE + "\".\"" + TABLE_ORACLE_ALL_DATA_TYPES + "\"";
            assertAll(() -> assertExpressionExecutionTimestampResult(query, Timestamp.valueOf(expectedColumnValue)),
                    () -> assertThat(getColumnTypesOfTable(qualifiedTableName, columnName), equalTo("TIMESTAMP")),
                    () -> assertExplainVirtual(query, expectedExplainVirtual));
        }

        private void assertExpressionExecutionTimestampResult(final String query, final Timestamp expected)
                throws SQLException {
            final ResultSet result = statementExasol.executeQuery(query);
            result.next();
            final Timestamp actual = result.getTimestamp(1);
            MatcherAssert.assertThat(actual, equalTo(expected));
        }

        @ParameterizedTest
        @ValueSource(strings = {VIRTUAL_SCHEMA_JDBC, VIRTUAL_SCHEMA_ORACLE})
        void testIntervalYear(final String virtualSchemaName) {
            final String qualifiedTableName = virtualSchemaName + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT C16 FROM " + qualifiedTableName;
            final String expectedExplainVirtual = "SELECT TO_CHAR(\"" + TABLE_ORACLE_ALL_DATA_TYPES
                    + "\".\"C16\") FROM \"" + SCHEMA_ORACLE + "\".\"" + TABLE_ORACLE_ALL_DATA_TYPES + "\"";
            assertAll(() -> assertExpressionExecutionStringResult(query, "+54-02"),
                    () -> assertThat(getColumnTypesOfTable(qualifiedTableName, "C16"),
                            equalTo("VARCHAR(2000000) UTF8")),
                    () -> assertExplainVirtual(query, expectedExplainVirtual));
        }

        @ParameterizedTest
        @ValueSource(strings = {VIRTUAL_SCHEMA_JDBC, VIRTUAL_SCHEMA_ORACLE})
        void testIntervalDay(final String virtualSchemaName) throws SQLException {
            final String qualifiedTableName = virtualSchemaName + "." + TABLE_ORACLE_ALL_DATA_TYPES;
            final String query = "SELECT C17 FROM " + qualifiedTableName + " ORDER BY 1";
            final ResultSet expected = getExpectedResultSet("(A VARCHAR(2000000) UTF8)",
                    "('+01 11:12:10.123000'), ('+02 02:03:04.123456')");
            final ResultSet actual = statementExasol.executeQuery(query);
            final String expectedExplainVirtual = "SELECT TO_CHAR(\"" + TABLE_ORACLE_ALL_DATA_TYPES
                    + "\".\"C17\") FROM \"" + SCHEMA_ORACLE + "\".\"" + TABLE_ORACLE_ALL_DATA_TYPES + "\" ORDER BY \""
                    + TABLE_ORACLE_ALL_DATA_TYPES + "\".\"C17\"";
            assertAll(() -> assertThat(actual, matchesResultSet(expected)),
                    () -> assertThat(getColumnTypesOfTable(qualifiedTableName, "C17"),
                            equalTo("VARCHAR(2000000) UTF8")),
                    () -> assertExplainVirtual(query, expectedExplainVirtual));
        }

        @ParameterizedTest
        @CsvSource(value = {
                "VIRTUAL_SCHEMA_JDBC ! ('2018-01-01 11:00:00.0', '2018-01-01 11:00:00.0', '2018-01-01 11:00:00.000')", //
                "VIRTUAL_SCHEMA_ORACLE ! ('2018-01-01 11:00:00.0', '2018-01-01 10:00:00.0', '2018-01-01 10:00:00.000')"}, //
                delimiter = '!')
        void testSelectAllTimestampColumns(final String virtualSchemaName, final String expectedColumnValue)
                throws SQLException {
            statementExasol.execute("ALTER SESSION SET TIME_ZONE = 'UTC'");
            final String qualifiedTableName = virtualSchemaName + "." + TABLE_ORACLE_TIMESTAMPS;
            final String query = "SELECT * FROM " + qualifiedTableName;
            final ResultSet expected = getExpectedResultSet("(A TIMESTAMP, B TIMESTAMP, C TIMESTAMP)",
                    expectedColumnValue);
            final ResultSet actual = statementExasol.executeQuery(query);
            assertAll(() -> assertThat(actual, matchesResultSet(expected)),
                    () -> assertThat(getColumnTypesOfTable(qualifiedTableName, "A"), equalTo("TIMESTAMP")),
                    () -> assertThat(getColumnTypesOfTable(qualifiedTableName, "B"), equalTo("TIMESTAMP")),
                    () -> assertThat(getColumnTypesOfTable(qualifiedTableName, "C"), equalTo("TIMESTAMP")));
        }
    }
}
