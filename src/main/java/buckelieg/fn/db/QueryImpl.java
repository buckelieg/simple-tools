package buckelieg.fn.db;

import javax.annotation.Nonnull;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import static buckelieg.fn.db.Utils.checkAnonymous;
import static buckelieg.fn.db.Utils.setStatementParameters;

@SuppressWarnings("unchecked")
final class QueryImpl extends AbstractQuery<Statement> {

    private boolean isPrepared;

    QueryImpl(Connection connection, String query, Object... params) {
        super(connection, query, params);
    }

    @Override
    Statement prepareStatement(Connection connection, String query, Object... params) throws SQLException {
        return (isPrepared = params != null && params.length != 0) ? setStatementParameters(connection.prepareStatement(query), params) : connection.createStatement();
    }

    @Override
    public Boolean execute() {
        return withStatement(s -> isPrepared ? ((PreparedStatement) s).execute() : s.execute(checkAnonymous(query)));
    }

    @Nonnull
    @Override
    public <Q extends Query> Q poolable(boolean poolable) {
        return setPoolable(poolable);
    }

    @Nonnull
    @Override
    public <Q extends Query> Q timeout(int timeout) {
        return setTimeout(timeout);
    }

    @Nonnull
    @Override
    public <Q extends Query> Q escaped(boolean escapeProcessing) {
        return setEscapeProcessing(escapeProcessing);
    }

    @Nonnull
    @Override
    public <Q extends Query> Q skipWarnings(boolean skipWarnings) {
        return setSkipWarnings(skipWarnings);
    }
}