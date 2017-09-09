/*
* Copyright 2016 Anatoly Kutyakov
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package buckelieg.simpletools.db;

import org.apache.derby.jdbc.EmbeddedDataSource;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.AbstractMap.SimpleImmutableEntry;
import static org.junit.Assert.assertTrue;


// TODO more test suites for other RDBMS
public class DBTestSuite {

    private static Connection conn;
    private static DB db;
    private static DataSource ds;
    private static TrySupplier<Connection, SQLException> single;

    @BeforeClass
    public static void init() throws Exception {
//        Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
//        conn = DriverManager.getConnection("jdbc:derby:memory:test;create=true");
        EmbeddedDataSource ds = new EmbeddedDataSource();
        ds.setDatabaseName("test");
        ds.setCreateDatabase("create");
        DBTestSuite.ds = ds;
        conn = ds.getConnection();
        conn.createStatement().execute("CREATE TABLE TEST(id int PRIMARY KEY generated always as IDENTITY, name varchar(255) not null)");
        conn.createStatement().execute("CREATE PROCEDURE CREATETESTROW1(NAME_TO_ADD VARCHAR(255)) DYNAMIC RESULT SETS 2 LANGUAGE JAVA EXTERNAL NAME 'buckelieg.simpletools.db.DerbyStoredProcedures.createTestRow' PARAMETER STYLE JAVA");
        conn.createStatement().execute("CREATE PROCEDURE CREATETESTROW2(NAME_TO_ADD VARCHAR(255)) LANGUAGE JAVA EXTERNAL NAME 'buckelieg.simpletools.db.DerbyStoredProcedures.testProcedure' PARAMETER STYLE JAVA");
        conn.createStatement().execute("CREATE PROCEDURE GETNAMEBYID(NAME_ID INTEGER, OUT NAME_NAME VARCHAR(255)) LANGUAGE JAVA EXTERNAL NAME 'buckelieg.simpletools.db.DerbyStoredProcedures.testProcedureWithResults' PARAMETER STYLE JAVA");
//        db = new DB(() -> conn);
//        db = new DB(conn);
//        Properties props = new Properties();
//        props.setProperty(SingleConnectionSupplier.URL, "jdbc:derby:memory:test;create=true");
//        props.setProperty(SingleConnectionSupplier.USER, "");
//        props.setProperty(SingleConnectionSupplier.PASSWORD, "");
//        single = new SingleConnectionSupplier(props);
        db = new DB(ds::getConnection);
//        db = new DB(single);

    }

    @AfterClass
    public static void destroy() throws Exception {
        conn.createStatement().execute("DROP TABLE TEST");
        conn.createStatement().execute("DROP PROCEDURE CREATETESTROW1");
        conn.createStatement().execute("DROP PROCEDURE CREATETESTROW2");
        conn.createStatement().execute("DROP PROCEDURE GETNAMEBYID");
        db.close();
    }

    @Before
    public void reset() throws Exception {
        conn.createStatement().executeUpdate("TRUNCATE TABLE TEST");
        conn.createStatement().executeUpdate("ALTER TABLE TEST ALTER COLUMN ID RESTART WITH 1");
        PreparedStatement ps = conn.prepareStatement("INSERT INTO TEST(name) VALUES(?)");
        for (int i = 0; i < 10; i++) {
            ps.setString(1, "name_" + (i + 1));
            ps.execute();
        }
    }

    @Test
    public void testResultSet() throws Exception {
        try (ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM TEST")) {
            int rows = 0;
            while (rs.next()) {
                rows++;
            }
            assertTrue(rows == 10);
        }
    }

    @Test
    public void testFetchSize() throws Exception {
        assertTrue(10 == db.select("SELECT * FROM TEST").fetchSize(1).execute().count());
    }

    @Test
    public void testMaxRows() throws Exception {
        assertTrue(1 == db.select("select * from test").maxRows(1).execute().count());
        assertTrue(1 == db.select("select * from test").maxRows(1L).execute().count());
        assertTrue(2 == db.select("select * from test").maxRows(1).maxRows(2L).execute().count());
        assertTrue(2 == db.select("select * from test").maxRows(1L).maxRows(2).execute().count());
    }

    @Test
    public void testSelect() throws Exception {
        Collection<?> results = db.select("SELECT * FROM TEST WHERE ID IN (?, ?)", 1, 2)
                .execute()
                .parallel()
                .collect(
                        ArrayList<Map.Entry<Integer, String>>::new,
                        (pList, rs) -> {
                            try {
                                pList.add(new SimpleImmutableEntry<>(rs.getInt(1), rs.getString(2)));
                            } catch (SQLException e) {
                                e.printStackTrace();
                            }
                        },
                        Collection::addAll
                );
        assertTrue(results.size() == 2);
    }

    @Test
    public void testSelectNamed() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("ID", new Object[]{1, 2});
//        params.put("id", Arrays.asList(1, 2));
        params.put("name", "name_5");
        params.put("NAME", "name_6");
        Collection<Map.Entry<Integer, String>> results = db.select("SELECT * FROM TEST WHERE 1=1 AND ID IN (:ID) OR NAME=:name OR NAME=:NAME", params)
                .execute()
                .parallel()
                .collect(
                        LinkedList<Map.Entry<Integer, String>>::new,
                        (pList, rs) -> {
                            try {
                                pList.add(new SimpleImmutableEntry<>(rs.getInt(1), rs.getString(2)));
                            } catch (SQLException e) {
                                e.printStackTrace();
                            }
                        },
                        Collection::addAll
                );
        assertTrue(results.size() == 4);
    }

    @Test
    public void testSelectNoParams() throws Exception {
        assertTrue(10 == db.select("SELECT COUNT(*) FROM TEST").single(rs -> rs.getInt(1)).get());
    }

    @Test
    public void testUpdateNoParams() throws Exception {
        assertTrue(10L == db.update("DELETE FROM TEST").execute().toOptional().orElse(0L));
    }

    @Test
    public void testInsert() throws Exception {
        long res = db.update("INSERT INTO TEST(name) VALUES(?)", "New_Name").execute().toOptional().orElse(0L);
        assertTrue(1L == res);
    }

    @Test
    public void testInsertNamed() throws Exception {
        long res = db.update("INSERT INTO TEST(name) VALUES(:name)", new SimpleImmutableEntry<>("name", "New_Name")).execute().toOptional().orElse(0L);
        assertTrue(1L == res);
        assertTrue(Long.valueOf(11L).equals(db.<Long>select("SELECT COUNT(*) FROM TEST").single((rs) -> rs.getLong(1)).get()));
    }

    @Test
    public void testUpdate() throws Exception {
        long res = db.update("UPDATE TEST SET NAME=? WHERE NAME=?", "new_name_2", "name_2").execute().toOptional().orElse(0L);
        assertTrue(1L == res);
        assertTrue(Long.valueOf(1L).equals(db.<Long>select("SELECT COUNT(*) FROM TEST WHERE name=?", "new_name_2").single((rs) -> rs.getLong(1)).get()));
    }

    @Test
    public void testUpdateNamed() throws Exception {
        long res = db.update("UPDATE TEST SET NAME=:name WHERE NAME=:new_name", new SimpleImmutableEntry<>("name", "new_name_2"), new SimpleImmutableEntry<>("new_name", "name_2")).execute().toOptional().orElse(0L);
        assertTrue(1L == res);
        assertTrue(Long.valueOf(1L).equals(db.<Long>select("SELECT COUNT(*) FROM TEST WHERE name=?", "new_name_2").single((rs) -> rs.getLong(1)).get()));
    }

    @Test
    public void testUpdateBatch() throws Exception {
        assertTrue(2L == db.update("INSERT INTO TEST(name) VALUES(?)", new Object[][]{{"name1"}, {"name2"}}).execute().toOptional().orElse(0L));
    }

    @Test
    public void testUpdateBatchNamed() throws Exception {
        Map<String, String> params1 = new HashMap<String, String>() {{
            put("names", "name1");
        }};
        Map<String, String> params2 = new HashMap<String, String>() {{
            put("names", "name2");
        }};
        long res = db.update("INSERT INTO TEST(name) VALUES(:names)", params1, params2).execute().toOptional().orElse(0L);
        assertTrue(2L == res);
    }

    @Test
    public void testUpdateBatchBatch() throws Exception {
        assertTrue(2L == db.update("INSERT INTO TEST(name) VALUES(?)", new Object[][]{{"name1"}, {"name2"}}).batched().execute().toOptional().orElse(0L));
    }

    @Test
    public void testLargeUpdate() throws Exception {
        long res = db.update("INSERT INTO TEST(name) VALUES(?)", "largeupdatenametest").execute().toOptional().orElse(0L);
        assertTrue(1L == res);
    }

    @Test
    public void testDelete() throws Exception {
        long res = db.update("DELETE FROM TEST WHERE name=?", "name_2").execute().toOptional().orElse(0L);
        assertTrue(1L == res);
        assertTrue(Long.valueOf(9L).equals(db.<Long>select("SELECT COUNT(*) FROM TEST").single((rs) -> rs.getLong(1)).get()));
    }

    @Test
    public void testDeleteNamed() throws Exception {
        long res = db.update("DELETE FROM TEST WHERE name=:name", new SimpleImmutableEntry<>("name", "name_2")).execute().toOptional().orElse(0L);
        assertTrue(1L == res);
        assertTrue(Long.valueOf(9L).equals(db.<Long>select("SELECT COUNT(*) FROM TEST").single((rs) -> rs.getLong(1)).get()));
    }

    @Test(expected = IllegalStateException.class)
    public void testDuplicatedNamedParameters() throws Exception {
        db.select("SELECT * FROM TEST WHERE 1=1 AND (NAME IN (:names) OR NAME=:names)", new SimpleImmutableEntry<>("names", "name_1"), new SimpleImmutableEntry<>("names", "name_2"));
    }

    @Test
    public void testVoidStoredProcedure() throws Exception {
        db.call("{call CREATETESTROW2(?)}", "new_name").invoke();
        assertTrue(Long.valueOf(11L).equals(db.select("SELECT COUNT(*) FROM TEST").single((rs) -> rs.getLong(1)).get()));
    }

    @Test(expected = SQLRuntimeException.class)
    public void testStoredProcedureNonEmptyResult() throws Exception {
        db.call("{call CREATETESTROW1(?)}", "new_name").invoke();
    }

    @Test
    public void testResultSetStoredProcedure() throws Exception {
/*        DB.call(conn, "{call CREATETESTROW1(?)}", "new_name").stream().forEach((rs) -> {
            try {
                System.out.println(String.format("ID='%s', NAME='%s'", rs.getInt(1), rs.getString(2)));
            } catch (SQLException e) {
                e.printStackTrace();
            }
        });*/
        assertTrue(db.call("{call CREATETESTROW1(?)}", "new_name").execute().count() == 13);
    }

    @Test
    public void testResultSetWithResultsStoredProcedure() throws Exception {
        List<String> name = new ArrayList<>(1);
        long count = db.call("call GETNAMEBYID(?, ?)", P.in(1), P.out(JDBCType.VARCHAR))
                .invoke((cs) -> cs.getString(2), name::add).execute().count();
        assertTrue(count == 0);
        assertTrue("name_1".equals(name.get(0)));
    }

    @Test
    public void testGetResult() throws Exception {
        String name = db.call("{call GETNAMEBYID(?,?)}", P.in(1), P.out(JDBCType.VARCHAR)).invoke((cs) -> cs.getString(2)).get();
        assertTrue("name_1".equals(name));
    }

    @Test
    public void testImmutable() throws Exception {
        db.select("SELECT * FROM TEST WHERE 1=1 AND ID=?", 1)
                .execute()
                .forEach(rs -> {
                    testImmutableAction(rs, ResultSet::next);
                    testImmutableAction(rs, (r) -> {
                        r.afterLast();
                        return null;
                    });
                    testImmutableAction(rs, (r) -> {
                        r.beforeFirst();
                        return null;
                    });
                    testImmutableAction(rs, ResultSet::previous);
                    testImmutableAction(rs, (r) -> r.absolute(1));
                    testImmutableAction(rs, (r) -> r.relative(1));
                    testImmutableAction(rs, (r) -> {
                        r.updateObject(1, "Updated_val");
                        return null;
                    });
                    // TODO test all unsupported actions
                });
    }

    private void testImmutableAction(ResultSet rs, TryFunction<ResultSet, ?, SQLException> action) {
        try {
            action.apply(rs);
        } catch (SQLException e) {
            assertTrue("Unsupported operation".equals(e.getMessage()));
        }
    }

    private void printDb() {
        db.select("SELECT * FROM TEST")
                .execute()
                .forEach(rs -> {
                    try {
                        System.out.println(String.format("ID=%s NAME=%s", rs.getInt(1), rs.getString(2)));
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                });
    }

    @Test
    public void testStoredProcedureRegexp() throws Exception {
        Field f = DB.class.getDeclaredField("STORED_PROCEDURE");
        f.setAccessible(true);
        Pattern STORED_PROCEDURE = (Pattern) f.get(null);
        Stream.of(
                new SimpleImmutableEntry<>("{call myProc()}", true),
                new SimpleImmutableEntry<>("call myProc()", true),
                new SimpleImmutableEntry<>("{call myProc}", true),
                new SimpleImmutableEntry<>("call myProc", true),
                new SimpleImmutableEntry<>("{?=call MyProc()}", true),
                new SimpleImmutableEntry<>("?=call myProc()", true),
                new SimpleImmutableEntry<>("{?=call MyProc}", true),
                new SimpleImmutableEntry<>("?=call myProc", true),
                new SimpleImmutableEntry<>("{call myProc(?)}", true),
                new SimpleImmutableEntry<>("call myProc(?)", true),
                new SimpleImmutableEntry<>("{?=call myProc(?)}", true),
                new SimpleImmutableEntry<>("?=call myProc(?)", true),
                new SimpleImmutableEntry<>("{call myProc(?,?)}", true),
                new SimpleImmutableEntry<>("call myProc(?,?)", true),
                new SimpleImmutableEntry<>("{?=call myProc(?,?)}", true),
                new SimpleImmutableEntry<>("?=call myProc(?,?)", true),
                new SimpleImmutableEntry<>("{call myProc(?,?,?)}", true),
                new SimpleImmutableEntry<>("call myProc(?,?,?)", true),
                new SimpleImmutableEntry<>("{?=call myProc(?,?,?)}", true),
                new SimpleImmutableEntry<>("?=call myProc(?,?,?)", true),
                new SimpleImmutableEntry<>("{}", false),
                new SimpleImmutableEntry<>("call ", false),
                new SimpleImmutableEntry<>("{call}", false),
                new SimpleImmutableEntry<>("call myProc(?,?,?,?,?)", true)
                // TODO more cases here
        ).forEach(testCase -> assertTrue(
                String.format("Test case '%s' failed", testCase.getKey()),
                testCase.getValue() == STORED_PROCEDURE.matcher(testCase.getKey()).matches()
        ));
    }

    @Test(expected = Exception.class)
    public void testExceptionHandler() throws Exception {
        db.update("UPDATE TEST SET ID=? WHERE ID=?", 111, 1)
                .poolable(() -> true)
                .timeout(() -> 0)
                .execute()
                .onException(e -> {
                    throw new Exception("TEST EXCEPTION");
                });
    }

}