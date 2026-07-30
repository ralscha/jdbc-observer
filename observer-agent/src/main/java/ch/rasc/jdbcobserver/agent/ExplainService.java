package ch.rasc.jdbcobserver.agent;

import ch.rasc.jdbcobserver.core.ControlCodec;
import ch.rasc.jdbcobserver.core.TransportCodec;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import javax.sql.PooledConnection;

final class ExplainService {

	private static final int MAX_PLAN_CHARACTERS = 1_000_000;

	private static final int MAX_PLAN_ROWS = 10_000;

	private static final int MAX_TARGETS = 10_000;

	private static final Map<String, Target> TARGETS = new LinkedHashMap<>(256, 0.75f, true);

	private ExplainService() {
	}

	static synchronized void register(String connectionId, Connection connection, Object source) {
		Objects.requireNonNull(connectionId, "connectionId");
		Objects.requireNonNull(connection, "connection");
		if (!TARGETS.containsKey(connectionId) && TARGETS.size() >= MAX_TARGETS) {
			TARGETS.remove(TARGETS.keySet().iterator().next());
		}
		var reusableSource = source instanceof DataSource || source instanceof PooledConnection ? source : null;
		TARGETS.put(connectionId, new Target(connection, reusableSource));
	}

	static synchronized void unregister(String connectionId) {
		var target = TARGETS.get(connectionId);
		if (target == null) {
			return;
		}
		target.connection = null;
		if (target.source.get() == null) {
			TARGETS.remove(connectionId);
		}
	}

	static TransportCodec.ExplainResponse explain(ControlCodec.ExplainRequest request) {
		var target = target(request.connectionId());
		if (target == null) {
			return failure(request, "Connection " + request.connectionId()
					+ " is no longer available. Run the statement again and retry.");
		}
		Connection connection = target.connection();
		boolean borrowed = false;
		try {
			if (connection == null || connection.isClosed()) {
				connection = open(target.source());
				borrowed = true;
			}
			String sql = singleStatement(request.sql());
			try (var statement = connection.createStatement()) {
				configure(statement);
				if (!statement.execute("EXPLAIN " + sql)) {
					return success(request, "EXPLAIN completed without returning a result set.");
				}
				try (var resultSet = statement.getResultSet()) {
					return success(request, render(resultSet));
				}
			}
		}
		catch (Exception ex) {
			return failure(request, error(ex));
		}
		finally {
			if (borrowed) {
				close(connection);
			}
		}
	}

	private static synchronized TargetSnapshot target(String connectionId) {
		var target = TARGETS.get(connectionId);
		if (target == null) {
			return null;
		}
		var source = target.source.get();
		if (target.connection == null && source == null) {
			TARGETS.remove(connectionId);
			return null;
		}
		return new TargetSnapshot(target.connection, source);
	}

	private static Connection open(Object source) throws Exception {
		if (source == null) {
			throw new SQLException("The original connection is closed and its JDBC source is no longer available");
		}
		return ConnectionInterceptor.withoutObservation(() -> switch (source) {
			case DataSource dataSource -> dataSource.getConnection();
			case PooledConnection pooledConnection -> pooledConnection.getConnection();
			default -> throw new SQLException("The original JDBC source cannot create another connection");
		});
	}

	private static void close(Connection connection) {
		if (connection == null) {
			return;
		}
		try {
			connection.close();
		}
		catch (SQLException ignored) {
		}
	}

	private static void configure(Statement statement) {
		try {
			statement.setQueryTimeout(10);
		}
		catch (SQLException ignored) {
		}
		try {
			statement.setMaxRows(MAX_PLAN_ROWS);
		}
		catch (SQLException ignored) {
		}
	}

	private static String render(ResultSet resultSet) throws SQLException {
		var metadata = resultSet.getMetaData();
		int columns = metadata.getColumnCount();
		var result = new StringBuilder();
		for (int column = 1; column <= columns; column++) {
			appendSeparator(result, column);
			append(result, metadata.getColumnLabel(column));
		}
		result.append('\n');
		int rows = 0;
		boolean truncated = false;
		while (rows < MAX_PLAN_ROWS && resultSet.next()) {
			rows++;
			for (int column = 1; column <= columns; column++) {
				appendSeparator(result, column);
				append(result, resultSet.getString(column));
			}
			result.append('\n');
			if (result.length() >= MAX_PLAN_CHARACTERS) {
				truncated = true;
				break;
			}
		}
		if (!truncated && rows == MAX_PLAN_ROWS && resultSet.next()) {
			truncated = true;
		}
		if (truncated) {
			result.setLength(Math.min(result.length(), MAX_PLAN_CHARACTERS));
			result.append("\n… plan output truncated …\n");
		}
		return result.toString();
	}

	private static void appendSeparator(StringBuilder target, int column) {
		if (column > 1) {
			target.append('\t');
		}
	}

	private static void append(StringBuilder target, String value) {
		if (value == null) {
			target.append("NULL");
			return;
		}
		for (int index = 0; index < value.length() && target.length() < MAX_PLAN_CHARACTERS; index++) {
			char current = value.charAt(index);
			target.append(current == '\r' || current == '\n' || current == '\t' ? ' ' : current);
		}
	}

	private static String singleStatement(String sql) {
		String result = sql.strip();
		if (result.endsWith(";")) {
			result = result.substring(0, result.length() - 1).stripTrailing();
		}
		if (result.isBlank()) {
			throw new IllegalArgumentException("The selected SQL is empty");
		}
		if (result.indexOf(';') >= 0) {
			throw new IllegalArgumentException(
					"EXPLAIN is limited to one SQL statement; embedded semicolons are not accepted");
		}
		return result;
	}

	private static TransportCodec.ExplainResponse success(ControlCodec.ExplainRequest request, String plan) {
		return new TransportCodec.ExplainResponse(request.requestId(), true, plan, "");
	}

	private static TransportCodec.ExplainResponse failure(ControlCodec.ExplainRequest request, String error) {
		return new TransportCodec.ExplainResponse(request.requestId(), false, "", error);
	}

	private static String error(Exception exception) {
		try {
			var message = exception.getMessage();
			return message == null || message.isBlank() ? exception.getClass().getName() : message;
		}
		catch (RuntimeException ex) {
			return exception.getClass().getName();
		}
	}

	private static final class Target {

		private Connection connection;

		private final WeakReference<Object> source;

		private Target(Connection connection, Object source) {
			this.connection = connection;
			this.source = new WeakReference<>(source);
		}

	}

	private record TargetSnapshot(Connection connection, Object source) {
	}

}
