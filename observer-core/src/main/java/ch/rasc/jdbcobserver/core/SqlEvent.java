package ch.rasc.jdbcobserver.core;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record SqlEvent(long id, long parentId, long transactionId, Instant timestamp, String thread, String connection,
		Kind kind, String rawSql, String sql, Map<Integer, String> parameters, Map<Integer, String> parameterMethods,
		long durationNanos, long fetchNanos, long resultSetUseNanos, long rows, boolean success, String error,
		int queryTimeout, boolean autoCommit, int transactionIsolation, String connectionUrl,
		String connectionProperties, String fingerprint, String callSite, String stackTrace) {

	public enum Kind {

		CONNECTION(1), CONNECTION_CLOSE(2), TRANSACTION_BEGIN(3), QUERY(4), UPDATE(5), EXECUTE(6), BATCH(7),
		RESULT_SET(8), SAVEPOINT(9), SAVEPOINT_ROLLBACK(10), SAVEPOINT_RELEASE(11), AUTOCOMMIT_CHANGE(12),
		ISOLATION_CHANGE(13), COMMIT(14), ROLLBACK(15);

		private final int wireCode;

		Kind(int wireCode) {
			this.wireCode = wireCode;
		}

		public int wireCode() {
			return this.wireCode;
		}

		public boolean isSqlStatement() {
			return this == QUERY || this == UPDATE || this == EXECUTE || this == BATCH;
		}

	}

	public SqlEvent {
		timestamp = Objects.requireNonNull(timestamp, "timestamp");
		kind = Objects.requireNonNull(kind, "kind");
		thread = value(thread);
		connection = value(connection);
		rawSql = value(rawSql);
		sql = value(sql);
		error = value(error);
		connectionUrl = value(connectionUrl);
		connectionProperties = value(connectionProperties);
		fingerprint = value(fingerprint);
		callSite = value(callSite);
		stackTrace = value(stackTrace);
		parameters = snapshot(parameters, "parameters");
		parameterMethods = snapshot(parameterMethods, "parameterMethods");
	}

	private static String value(String value) {
		return value == null ? "" : value;
	}

	private static Map<Integer, String> snapshot(Map<Integer, String> source, String name) {
		Objects.requireNonNull(source, name);
		var result = new LinkedHashMap<Integer, String>();
		for (var entry : source.entrySet()) {
			var index = Objects.requireNonNull(entry.getKey(), name + " key");
			if (index < 1) {
				throw new IllegalArgumentException(name + " index must be positive: " + index);
			}
			result.put(index, Objects.requireNonNull(entry.getValue(), name + " value"));
		}
		return Collections.unmodifiableMap(result);
	}

	public double durationMillis() {
		return durationNanos / 1_000_000.0;
	}

	public double fetchMillis() {
		return fetchNanos / 1_000_000.0;
	}

	public double resultSetUseMillis() {
		return resultSetUseNanos / 1_000_000.0;
	}

}
