package testfixture;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.ConnectionBuilder;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

public final class JdbcDataSourceFixture implements DataSource {

	@Override
	public Connection getConnection() {
		return null;
	}

	@Override
	public Connection getConnection(String username, String password) {
		return null;
	}

	@Override
	public PrintWriter getLogWriter() {
		return null;
	}

	@Override
	public void setLogWriter(PrintWriter out) {
	}

	@Override
	public void setLoginTimeout(int seconds) {
	}

	@Override
	public int getLoginTimeout() {
		return 0;
	}

	@Override
	public ConnectionBuilder createConnectionBuilder() throws SQLException {
		return DataSource.super.createConnectionBuilder();
	}

	@Override
	public Logger getParentLogger() throws SQLFeatureNotSupportedException {
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public <T> T unwrap(Class<T> api) throws SQLException {
		throw new SQLException("not a wrapper");
	}

	@Override
	public boolean isWrapperFor(Class<?> api) {
		return false;
	}

}
