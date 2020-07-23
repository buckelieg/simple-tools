/*
 * Copyright 2016- Anatoly Kutyakov
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
package buckelieg.jdbc;

import buckelieg.jdbc.fn.TryBiFunction;
import buckelieg.jdbc.fn.TryFunction;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.concurrent.NotThreadSafe;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static buckelieg.jdbc.Utils.*;
import static java.sql.Statement.RETURN_GENERATED_KEYS;
import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.of;

@SuppressWarnings("unchecked")
@NotThreadSafe
@ParametersAreNonnullByDefault
final class UpdateQuery extends AbstractQuery<Statement> implements Update {

    private static final TryFunction<ResultSet, ResultSet, SQLException> NOOP = rs -> rs;

    private final Object[][] batch;
    private boolean isLarge;
    private boolean isBatch;
    private int[] colIndices = null;
    private String[] colNames = null;
    private boolean useGeneratedKeys = false;

    UpdateQuery(Executor conveyor, Connection connection, String query, Object[]... batch) {
        super(conveyor, connection, query, (Object) batch);
        this.batch = batch;
    }

    @Override
    public Update large(boolean isLarge) {
        this.isLarge = isLarge;
        return this;
    }

    @Override
    public Update batch(boolean isBatch) {
        this.isBatch = isBatch;
        return this;
    }

    @Nonnull
    @Override
    public Update poolable(boolean poolable) {
        this.isPoolable = poolable;
        return this;
    }

    @Nonnull
    @Override
    public Update timeout(int timeout, TimeUnit unit) {
        this.timeout = timeout;
        this.unit = unit;
        return this;
    }

    @Nonnull
    @Override
    public Update escaped(boolean escapeProcessing) {
        this.isEscaped = escapeProcessing;
        return this;
    }

    @Nonnull
    @Override
    public Update skipWarnings(boolean skipWarnings) {
        this.skipWarnings = skipWarnings;
        return this;
    }

    @Nonnull
    @Override
    public Update print(Consumer<String> printer) {
        return log(printer);
    }

    @Nonnull
    @Override
    public <T, K> T execute(TryFunction<ResultSet, K, SQLException> valueMapper, TryFunction<Stream<K>, T, SQLException> generatedValuesHandler) {
        requireNonNull(valueMapper, "Generated values mapper must be provided!");
        requireNonNull(generatedValuesHandler, "Generated values handler must be provided");
        useGeneratedKeys = true;
        return jdbcTry(() -> TryBiFunction.<Connection, TryFunction<ResultSet, K, SQLException>, Object, SQLException>of(this::doExecute).andThen(generatedKeys -> generatedValuesHandler.apply(((Stream<K>) generatedKeys).onClose(this::close))).apply(connection, valueMapper));
    }

    @Nonnull
    @Override
    public <T, K> T execute(TryFunction<ResultSet, K, SQLException> valueMapper, TryFunction<Stream<K>, T, SQLException> generatedValuesHandler, String... colNames) {
        this.colNames = requireNonNull(colNames, "Column names must be provided");
        return execute(valueMapper, generatedValuesHandler);
    }

    @Nonnull
    @Override
    public <T, K> T execute(TryFunction<ResultSet, K, SQLException> valueMapper, TryFunction<Stream<K>, T, SQLException> generatedValuesHandler, int... colIndices) {
        this.colIndices = requireNonNull(colIndices, "Column indices must be provided");
        return execute(valueMapper, generatedValuesHandler);
    }

    @Nonnull
    public Long execute() {
        return (long) jdbcTry(() -> batch.length > 1 ? doInTransaction(false, () -> connection, null, conn -> doExecute(connection, NOOP)) : doExecute(connection, NOOP));
    }

    private <K> Object doExecute(Connection conn, TryFunction<ResultSet, K, SQLException> valueMapper) throws SQLException {
        if (useGeneratedKeys) {
            if (colNames != null && colNames.length != 0) {
                statement = conn.prepareStatement(query, colNames);
            } else if (colIndices != null && colIndices.length != 0) {
                statement = conn.prepareStatement(query, colIndices);
            } else {
                statement = conn.prepareStatement(query, RETURN_GENERATED_KEYS);
            }
        } else {
            statement = isPrepared ? conn.prepareStatement(query) : conn.createStatement();
        }
        setPoolable();
        setTimeout();
        setEscapeProcessing();
        return isBatch && conn.getMetaData().supportsBatchUpdates() ? useGeneratedKeys ? executeUpdateBatchWithGeneratedKeys(valueMapper) : executeUpdateBatch() : useGeneratedKeys ? executeUpdateWithGeneratedKeys(valueMapper) : executeUpdate();
    }

    private <K> Stream<K> executeUpdateWithGeneratedKeys(TryFunction<ResultSet, K, SQLException> valueMapper) {
        return of(batch).onClose(this::close).reduce(
                new ArrayList<K>(),
                (genKeys, params) ->jdbcTry(() -> {
                    if (isLarge) {
                        setStatementParameters((PreparedStatement) statement, params).executeLargeUpdate();
                    } else {
                        setStatementParameters((PreparedStatement) statement, params).executeUpdate();
                    }
                    genKeys.addAll(collectGeneratedKeys(statement, valueMapper));
                    return genKeys;
                }),
                (list1, list2) -> {
                    list1.addAll(list2);
                    return list1;
                }
        ).stream();
    }

    private <K> Stream<K> executeUpdateBatchWithGeneratedKeys(TryFunction<ResultSet, K, SQLException> valueMapper) {
        return streamBatch().reduce(
                new ArrayList<K>(),
                (genKeys, s) -> jdbcTry(() -> {
                    if (isLarge) {
                        s.executeLargeBatch();
                    } else {
                        s.executeBatch();
                    }
                    genKeys.addAll(collectGeneratedKeys(s, valueMapper));
                    return genKeys;
                }),
                (list1, list2) -> {
                    list1.addAll(list2);
                    return list1;
                }
        ).stream();
    }

    private long executeUpdate() {
        return of(batch).onClose(this::close).reduce(0L, (rowsAffected, params) -> rowsAffected += jdbcTry(() -> isLarge ? jdbcTry(() -> isPrepared ? setStatementParameters((PreparedStatement) statement, params).executeLargeUpdate() : statement.executeLargeUpdate(query)) : (long) jdbcTry(() -> isPrepared ? setStatementParameters((PreparedStatement) statement, params).executeUpdate() : statement.executeUpdate(query))), Long::sum);
    }

    private long executeUpdateBatch() {
        return streamBatch().reduce(0L, (rowsAffected, stmt) -> rowsAffected += stream(jdbcTry(() -> isLarge ? stmt.executeLargeBatch() : stream(stmt.executeBatch()).asLongStream().toArray())).sum(), Long::sum);
    }

    @Override
    final String asSQL(String query, Object... params) {
        return stream(params).flatMap(p -> of((Object[]) p)).map(p -> super.asSQL(query, (Object[]) p)).collect(joining(STATEMENT_DELIMITER));
    }

    private Stream<Statement> streamBatch() {
        return of(batch).onClose(this::close).map(params -> jdbcTry(() -> {
            if (isPrepared) {
                setStatementParameters((PreparedStatement) statement, params).addBatch();
            } else {
                statement.addBatch(query);
            }
            return statement;
        }));
    }

    private <T> List<T> collectGeneratedKeys(Statement s, TryFunction<ResultSet, T, SQLException> valueMapper) throws SQLException {
        return rsStream(s.getGeneratedKeys(), valueMapper).collect(toList());
    }

}
