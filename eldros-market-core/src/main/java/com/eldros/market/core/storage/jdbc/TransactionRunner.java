package com.eldros.market.core.storage.jdbc;

import com.eldros.market.core.storage.StorageException;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Runs work in one transaction on the writer connection: commit on success, rollback on any failure. Engine thread
 * only (docs/03 §4). {@link SQLException}s surface as {@link StorageException}; runtime exceptions pass through.
 */
public final class TransactionRunner {

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private final Connection connection;

    public TransactionRunner(Connection connection) {
        this.connection = connection;
    }

    public <T> T inTransaction(SqlWork<T> work) {
        try {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error e) {
                rollbackQuietly(e);
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new StorageException("transaction failed", e);
        }
    }

    private void rollbackQuietly(Throwable cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }
}
