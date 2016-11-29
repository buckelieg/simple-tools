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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@ParametersAreNonnullByDefault
public final class Queries {

    private static final Pattern NAMED_PARAMETER = Pattern.compile(":\\w*\\B?");
    // Java regexp do not support conditional regexps. We will enumerate all possible variants.
    private static final Pattern STORED_PROCEDURE = Pattern.compile(
            String.format(
                    "%s|%s|%s|%s|%s|%s",
                    "(\\?\\s*=\\s*)?call\\s+\\w+\\s*(\\(\\s*)\\)",
                    "(\\?\\s*=\\s*)?call\\s+\\w+\\s*((\\(\\s*)\\?\\s*)(,\\s*\\?)*\\)",
                    "(\\?\\s*=\\s*)?call\\s+\\w+",
                    "\\{\\s*(\\?\\s*=\\s*)?call\\s+\\w+\\s*\\}",
                    "\\{\\s*(\\?\\s*=\\s*)?call\\s+\\w+\\s*((\\(\\s*)\\?\\s*)(,\\s*\\?)*\\)\\s*\\}",
                    "\\{\\s*(\\?\\s*=\\s*)?call\\s+\\w+\\s*(\\(\\s*)\\)\\s*\\}"
            )
    );

    private Queries() {
    }

    /**
     * Calls stored procedure. Supplied params are considered as IN typed parameters
     *
     * @param conn   The Connection to operate on
     * @param query  procedure call string
     * @param params procedure IN parameters
     * @return procedure call builder
     * @see ProcedureCall
     */
    @Nonnull
    public static ProcedureCall call(Connection conn, String query, Object... params) {
        return call(conn, query, Arrays.stream(params).map(P::in).collect(Collectors.toList()).toArray(new P<?>[params.length]));
    }

    /**
     * Calls stored procedure.
     *
     * @param conn   The Connection to operate on
     * @param query  procedure call string
     * @param params procedure parameters as declared (IN/OUT/INOUT)
     * @return procedure call builder
     * @see ProcedureCall
     */
    @Nonnull
    public static ProcedureCall call(Connection conn, String query, P<?>... params) {
        try {
            String lowerQuery = validateQuery(query, null);
            P<?>[] preparedParams = params;
            int namedParams = Arrays.stream(params).filter(p -> !p.getName().isEmpty()).collect(Collectors.toList()).size();
            if (namedParams == params.length) {
                Map.Entry<String, Object[]> preparedQuery = prepareQuery(
                        lowerQuery,
                        Arrays.stream(params).map(p -> Pair.of(p.getName(), new P<?>[]{p})).collect(Collectors.toList())
                );
                lowerQuery = preparedQuery.getKey();
                preparedParams = (P<?>[]) preparedQuery.getValue();
            } else if (0 < namedParams && namedParams < params.length) {
                throw new IllegalArgumentException(
                        String.format(
                                "Cannot combine named parameters(count=%s) with unnamed ones(count=%s).",
                                namedParams, params.length - namedParams
                        )
                );
            }
            if (!STORED_PROCEDURE.matcher(lowerQuery).matches()) {
                throw new IllegalArgumentException(String.format("Query '%s' is not a valid procedure call statement", query));
            }
            CallableStatement cs = requireOpened(conn).prepareCall(lowerQuery);
            for (int i = 1; i <= preparedParams.length; i++) {
                P<?> p = preparedParams[i - 1];
                if (p.isOut() || p.isInOut()) {
                    cs.registerOutParameter(i, Objects.requireNonNull(p.getType(), String.format("Parameter '%s' must have SQLType set.", p)));
                }
                if (p.isIn() || p.isInOut()) {
                    cs.setObject(i, p.getValue());
                }
            }
            return new ProcedureCallQuery(cs);
        } catch (SQLException e) {
            throw new RuntimeException(
                    String.format(
                            "Could not execute statement '%s' on connection '%s' due to '%s'",
                            query, conn, e.getMessage()
                    ), e
            );
        }
    }

    /**
     * Executes SELECT statement on provided Connection
     *
     * @param conn   The Connection to operate on.
     * @param query  SELECT query to execute. Can be WITH query
     * @param params query parameters on the declared order of '?'
     * @return select query builder
     * @see Select
     */
    @Nonnull
    public static Select select(Connection conn, String query, Object... params) {
        try {
            PreparedStatement ps = requireOpened(conn).prepareStatement(validateQuery(query, (lowerQuery) -> {
                if (!(lowerQuery.startsWith("select") || lowerQuery.startsWith("with"))) {
                    throw new IllegalArgumentException(String.format("Query '%s' is not a select statement", query));
                }
            }));
            setParameters(ps, params);
            return new SelectQuery(ps);
        } catch (SQLException e) {
            throw new RuntimeException(
                    String.format(
                            "Could not execute statement '%s' on connection '%s' due to '%s'",
                            query, conn, e.getMessage()
                    ), e
            );
        }
    }

    /**
     * Executes SELECT statement on provided Connection
     *
     * @param conn        The Connection to operate on.
     * @param query       SELECT query to execute. Can be WITH query
     * @param namedParams query named parameters. Parameter name in the form of :name
     * @return select query builder
     * @see Select
     */
    @Nonnull
    public static Select select(Connection conn, String query, Map<String, ?> namedParams) {
        return select(conn, query, namedParams.entrySet());
    }

    /**
     * Executes SELECT statement on provided Connection
     *
     * @param conn        The Connection to operate on.
     * @param query       SELECT query to execute. Can be WITH query
     * @param namedParams query named parameters. Parameter name in the form of :name
     * @return select query builder
     * @see Select
     */
    @Nonnull
    @SafeVarargs
    public static <T extends Map.Entry<String, ?>> Select select(Connection conn, String query, T... namedParams) {
        return select(conn, query, Arrays.asList(namedParams));
    }

    public static int update(Connection conn, String query, Object[]... batch) throws SQLException {
        boolean autoCommit = conn.getAutoCommit();
        int rowsAffected = 0;
        try {
            PreparedStatement ps = requireOpened(conn).prepareStatement(validateQuery(query, (lowerQuery) -> {
                if (!(lowerQuery.startsWith("insert") || lowerQuery.startsWith("update") || lowerQuery.startsWith("delete"))) {
                    throw new IllegalArgumentException(String.format("Query '%s' is not valid DML statement", query));
                }
            }));
            conn.setAutoCommit(false);
            for (Object[] params : batch) {
                setParameters(ps, params);
                rowsAffected += ps.executeUpdate();
            }
            ps.close();
            conn.commit();
            conn.setAutoCommit(autoCommit);
            return rowsAffected;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        }
    }

    /**
     * Executes one of DML statements: INSERT, UPDATE or DELETE.
     *
     * @param conn   The Connection to operate on.
     * @param query  INSERT/UPDATE/DELETE query to execute.
     * @param params query parameters on the declared order of '?'
     * @return affected rows
     */
    public static int update(Connection conn, String query, Object... params) {
        try {
            return update(conn, query, new Object[][]{params});
        } catch (SQLException e) {
            throw new RuntimeException(
                    String.format(
                            "Could not execute statement '%s' on connection '%s' due to '%s'",
                            query, conn, e.getMessage()
                    ), e
            );
        }
    }

    /**
     * Executes one of DML statements: INSERT, UDATE or DELETE.
     *
     * @param conn        The Connection to operate on.
     * @param query       INSERT/UPDATE/DELETE query to execute.
     * @param namedParams query named parameters. Parameter name in the form of :name
     * @return affected rows
     */
    @SafeVarargs
    public static <T extends Map.Entry<String, ?>> int update(Connection conn, String query, T... namedParams) {
        return update(conn, query, Arrays.asList(namedParams));
    }

    /**
     * Executes one of DML statements: INSERT, UDATE or DELETE.
     *
     * @param conn        The Connection to operate on.
     * @param query       INSERT/UPDATE/DELETE query to execute.
     * @param namedParams query named parameters. Parameter name in the form of :name
     * @return affected rows
     */

    public static int update(Connection conn, String query, Map<String, ?> namedParams) {
        return update(conn, query, namedParams.entrySet());
    }

    private static Select select(Connection conn, String query, Iterable<? extends Map.Entry<String, ?>> namedParams) {
        Map.Entry<String, Object[]> preparedQuery = prepareQuery(query, namedParams);
        return select(conn, preparedQuery.getKey(), preparedQuery.getValue());
    }

    private static int update(Connection conn, String query, Iterable<? extends Map.Entry<String, ?>> namedParams) {
        Map.Entry<String, Object[]> preparedQuery = prepareQuery(query, namedParams);
        return update(conn, preparedQuery.getKey(), preparedQuery.getValue());
    }

    private static Map.Entry<String, Object[]> prepareQuery(String query, Iterable<? extends Map.Entry<String, ?>> namedParams) {
        String lowerQuery = validateQuery(query, null);
        Map<Integer, Object> indicesToValues = new TreeMap<>();
        Map<String, ?> transformedParams = StreamSupport.stream(namedParams.spliterator(), false).collect(Collectors.toMap(
                k -> k.getKey().startsWith(":") ? k.getKey().toLowerCase() : String.format(":%s", k.getKey().toLowerCase()),
                Map.Entry::getValue
        ));
        Matcher matcher = NAMED_PARAMETER.matcher(lowerQuery);
        int idx = 0;
        while (matcher.find()) {
            Object val = transformedParams.get(matcher.group());
            if (val != null) {
                for (Object o : asIterable(val)) {
                    indicesToValues.put(++idx, o);
                }
            }
        }
        for (Map.Entry<String, ?> e : transformedParams.entrySet()) {
            lowerQuery = lowerQuery.replaceAll(
                    e.getKey(),
                    StreamSupport.stream(asIterable(e.getValue()).spliterator(), false).map(o -> "?").collect(Collectors.joining(", "))
            );
        }
        return Pair.of(lowerQuery, indicesToValues.values().toArray(new Object[indicesToValues.size()]));
    }

    private static Iterable<?> asIterable(Object o) {
        Iterable<?> iterable;
        if (o.getClass().isArray()) {
            iterable = Arrays.asList((Object[]) o);
        } else if (o instanceof Iterable) {
            iterable = (Iterable<?>) o;
        } else {
            iterable = Collections.singletonList(o);
        }
        return iterable;
    }

    private static Connection requireOpened(Connection conn) throws SQLException {
        if (Objects.requireNonNull(conn, "Connection to Database has top be provided").isClosed()) {
            throw new SQLException(String.format("Connection '%s' is closed", conn));
        }
        return conn;
    }

    private static String validateQuery(String query, @Nullable Consumer<String> validator) {
        String lowerQuery = Objects.requireNonNull(query, "SQL query has to be provided").trim().toLowerCase();
        if (validator != null) {
            validator.accept(lowerQuery);
        }
        return lowerQuery;
    }

    private static void setParameters(PreparedStatement ps, Object... params) throws SQLException {
        int pNum = 0;
        for (Object p : params) {
            ps.setObject(++pNum, p);
        }
    }

}