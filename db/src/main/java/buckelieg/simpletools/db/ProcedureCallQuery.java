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

import org.apache.log4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.sql.CallableStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Consumer;

@ParametersAreNonnullByDefault
final class ProcedureCallQuery extends SelectQuery<CallableStatement> implements ProcedureCall {

    private static final Logger LOG = Logger.getLogger(ProcedureCallQuery.class);

    private Try<CallableStatement, ?, SQLException> storedProcedureResultsHandler;
    private Consumer callback;

    ProcedureCallQuery(CallableStatement statement) {
        super(statement);
    }

    @Nonnull
    @Override
    public <T> Select withResultHandler(Try<CallableStatement, T, SQLException> mapper, Consumer<T> consumer) {
        this.storedProcedureResultsHandler = Objects.requireNonNull(mapper, "Mapper must be provided");
        this.callback = Objects.requireNonNull(consumer, "Callback must be provided");
        return this;
    }

    @Override
    protected void doExecute() throws SQLException {
        if (statement.execute()) {
            this.rs = statement.getResultSet();
        }
    }

    @SuppressWarnings("unchecked")
    protected boolean doMove() throws SQLException {
        boolean moved = super.doMove();
        if (!moved) {
            try {
                if (statement.getMoreResults()) {
                    closeResultSet();
                    rs = statement.getResultSet();
                    return super.doMove();
                }
            } catch (SQLException e) {
                logSQLException("Could not move result set on", e);
            }
            try {
                if (storedProcedureResultsHandler != null && callback != null) {
                    callback.accept(storedProcedureResultsHandler.doTry((CallableStatement) statement));
                }
            } finally {
                close();
            }
        }
        return moved;
    }

    private void closeResultSet() {
        try {
            if (rs != null && !rs.isClosed()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug(String.format("Closing ResultSet '%s'", rs));
                }
                rs.close();
            }
        } catch (SQLException e) {
            logSQLException("Could not close ResultSet", e);
        }
    }

}
