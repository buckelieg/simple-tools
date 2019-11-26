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
package buckelieg.fn.db;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.concurrent.NotThreadSafe;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static buckelieg.fn.db.Utils.*;
import static java.sql.Statement.RETURN_GENERATED_KEYS;
import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Stream.of;

@SuppressWarnings("unchecked")
@NotThreadSafe
@ParametersAreNonnullByDefault
class UpdateQuery extends AbstractQuery<PreparedStatement> implements Update {

    protected Object[][] batch;
    protected boolean isLarge;
    protected boolean isBatch;
    protected boolean isPoolable;
    protected boolean isEscaped = true;
    protected int timeout;
    protected final String query;
    protected TransactionIsolation isolationLevel = TransactionIsolation.SERIALIZABLE;

    private UpdateQuery(TrySupplier<PreparedStatement, SQLException> prepareStatement, Connection connection, String query, Object[]... batch) {
        super(connection, query, (Object) batch);
        this.batch = requireNonNull(batch, "Batch must be provided");
        this.query = query;
        this.statement = jdbcTry(prepareStatement);
    }

    UpdateQuery(Connection connection, String query, Object[]... batch) {
        this(() -> connection.prepareStatement(query), connection, query, batch);
    }

    UpdateQuery(@Nullable int[] colIndices, Connection connection, String query, Object[]... batch) {
        this(
                () -> colIndices == null || colIndices.length == 0 ?
                        connection.prepareStatement(query, RETURN_GENERATED_KEYS) :
                        connection.prepareStatement(query, colIndices),
                connection, query, batch
        );
    }

    UpdateQuery(@Nullable String[] colNames, Connection connection, String query, Object[]... batch) {
        this(
                () -> colNames == null || colNames.length == 0 ?
                        connection.prepareStatement(query, RETURN_GENERATED_KEYS) :
                        connection.prepareStatement(query, colNames),
                connection, query, batch
        );
    }

    @Override
    public Update large(boolean isLarge) {
        this.isLarge = isLarge;
        return this;
    }

    @Override
    public Update batched(boolean isBatch) {
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
    public Update timeout(int timeout) {
        this.timeout = timeout;
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
        return setSkipWarnings(skipWarnings);
    }

    @Nonnull
    @Override
    public Update transacted(TransactionIsolation isolationLevel) {
        this.isolationLevel = requireNonNull(isolationLevel, "Transaction isolation level must be provided");
        return this;
    }

    @Nonnull
    @Override
    public Update print(Consumer<String> printer) {
        return log(printer);
    }

    @Nonnull
    @Override
    public Long execute(TryConsumer<Stream<ResultSet>, SQLException> generatedValuesHandler) {
        return jdbcTry(() -> doInTransaction(connection, isolationLevel, TryFunction.of(this::doExecute).andThen(count -> {
            setStatementParameter(s -> requireNonNull(generatedValuesHandler, "Generated values handler must be provided").accept(StreamSupport.stream(new ResultSetSpliterator(s::getGeneratedKeys), false).onClose(this::close)));
            return count;
        })));
    }

    @Nonnull
    @Override
    public Long execute(TryConsumer<Stream<ResultSet>, SQLException> generatedValuesHandler, String... colNames) {
        return new UpdateQuery(colNames, connection, query, batch).execute(generatedValuesHandler);
    }

    @Nonnull
    @Override
    public Long execute(TryConsumer<Stream<ResultSet>, SQLException> generatedValuesHandler, int... colIndices) {
        return new UpdateQuery(colIndices, connection, query, batch).execute(generatedValuesHandler);
    }

    /**
     * Executes this DML query returning affected row count.
     * If this query represents a batch then affected rows are summarized for all batches.
     *
     * @return affected rows count
     */
    @Nonnull
    public Long execute() {
        return jdbcTry(() -> batch.length > 1 ? doInTransaction(connection, isolationLevel, this::doExecute) : doExecute(connection));
    }

    private long doExecute(Connection conn) throws SQLException {
        setPoolable(isPoolable);
        setTimeout(timeout);
        setEscapeProcessing(isEscaped);
        return isBatch && conn.getMetaData().supportsBatchUpdates() ? executeBatch() : executeSimple();
    }

    private long executeSimple() {
        return of(batch).reduce(
                0L,
                (rowsAffected, params) -> rowsAffected += jdbcTry(() -> isLarge ? withStatement(s -> setStatementParameters(s, params).executeLargeUpdate()) : (long) withStatement(s -> setStatementParameters(s, params).executeUpdate())),
                Long::sum
        );
    }

    private long executeBatch() {
        return of(batch)
                .map(params -> withStatement(statement -> {
                    setStatementParameters(statement, params).addBatch();
                    return statement;
                }))
                .reduce(
                        0L,
                        (rowsAffected, stmt) ->
                                rowsAffected += stream(
                                        jdbcTry(() -> isLarge ? stmt.executeLargeBatch() : stream(stmt.executeBatch()).asLongStream().toArray())
                                ).sum(),
                        Long::sum
                );
    }

    @Override
    PreparedStatement prepareStatement(Connection connection, String query, Object... params) {
        return null;
    }

    @Override
    final String asSQL(String query, Object... params) {
        return stream(params)
                .flatMap(p -> of((Object[]) p))
                .map(p -> super.asSQL(query, (Object[]) p))
                .collect(joining(STATEMENT_DELIMITER));
    }
}
