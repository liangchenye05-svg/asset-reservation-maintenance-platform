package com.example.equipmentmanagement.testsupport;

import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

/** Default context tests cannot open a network connection. Test scope only. */
public final class NoDatabaseDriver implements Driver {
    public static final String URL = "jdbc:equipment-test:disabled";
    @Override public Connection connect(String url, Properties properties) throws SQLException {
        throw new SQLNonTransientConnectionException("Database access disabled in default tests");
    }
    @Override public boolean acceptsURL(String url) { return URL.equals(url); }
    @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) { return new DriverPropertyInfo[0]; }
    @Override public int getMajorVersion() { return 1; }
    @Override public int getMinorVersion() { return 0; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() { return Logger.getLogger(NoDatabaseDriver.class.getName()); }
}
