package com.eldros.market.core.storage.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

/** Opens a connection to the market database. The paper module supplies it, so core never picks a JDBC driver (D10). */
@FunctionalInterface
public interface ConnectionFactory {

    Connection open() throws SQLException;
}
