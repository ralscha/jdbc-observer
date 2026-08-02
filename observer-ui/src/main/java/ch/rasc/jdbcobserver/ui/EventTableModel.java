package ch.rasc.jdbcobserver.ui;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.table.AbstractTableModel;

import ch.rasc.jdbcobserver.core.CartesianProductDetector;
import ch.rasc.jdbcobserver.core.CartesianProductDetector.Finding;
import ch.rasc.jdbcobserver.core.SqlEvent;

final class EventTableModel extends AbstractTableModel {

	private static final String[] COLUMNS = { "Time", "Type", "Pattern", "Execute", "Fetch", "Result use", "Rows",
			"Timeout", "Auto commit", "Isolation", "Connection", "Thread", "Call site", "SQL", "Status" };

	private final List<SqlEvent> events = new ArrayList<>();

	private final int limit;

	private final NPlusOneDetector nPlusOneDetector = new NPlusOneDetector();

	private final Map<Long, Finding> cartesianProducts = new HashMap<>();

	private double totalDurationMillis;

	private long failedCount;

	private long rowCount;

	EventTableModel() {
		this(Math.max(1, Integer.getInteger("maxLoggedStatements", 20_000)));
	}

	EventTableModel(int limit) {
		if (limit < 1) {
			throw new IllegalArgumentException("History limit must be positive");
		}
		this.limit = limit;
	}

	void add(SqlEvent event) {
		addAll(List.of(event));
	}

	void addAll(Collection<SqlEvent> additions) {
		if (additions.isEmpty()) {
			return;
		}
		for (var event : additions) {
			if (event.kind() == SqlEvent.Kind.RESULT_SET && event.parentId() > 0 && mergeResultSet(event)) {
				continue;
			}
			this.events.add(event);
			this.nPlusOneDetector.add(event);
			var cartesianProduct = CartesianProductDetector.detect(sql(event));
			if (cartesianProduct != Finding.NONE) {
				this.cartesianProducts.put(event.id(), cartesianProduct);
			}
			addMetrics(event);
		}
		while (this.events.size() > this.limit) {
			var removed = this.events.removeFirst();
			this.nPlusOneDetector.remove(removed);
			this.cartesianProducts.remove(removed.id());
			removeMetrics(removed);
		}
		fireTableDataChanged();
	}

	void clear() {
		this.events.clear();
		this.nPlusOneDetector.clear();
		this.cartesianProducts.clear();
		this.totalDurationMillis = 0;
		this.failedCount = 0;
		this.rowCount = 0;
		fireTableDataChanged();
	}

	SqlEvent get(int row) {
		return this.events.get(row);
	}

	List<SqlEvent> all() {
		return List.copyOf(this.events);
	}

	double totalDurationMillis() {
		return this.totalDurationMillis;
	}

	long failedCount() {
		return this.failedCount;
	}

	long observedRowCount() {
		return this.rowCount;
	}

	int nPlusOneThreshold() {
		return this.nPlusOneDetector.threshold();
	}

	long nPlusOneWindowMillis() {
		return this.nPlusOneDetector.windowMillis();
	}

	void configureNPlusOne(int threshold, long windowMillis) {
		this.nPlusOneDetector.configure(threshold, windowMillis, this.events);
		fireTableDataChanged();
	}

	int trackedNPlusOneEventCount() {
		return this.nPlusOneDetector.trackedEventCount();
	}

	@Override
	public int getRowCount() {
		return this.events.size();
	}

	@Override
	public int getColumnCount() {
		return COLUMNS.length;
	}

	@Override
	public String getColumnName(int column) {
		return COLUMNS[column];
	}

	@Override
	public Class<?> getColumnClass(int column) {
		return switch (column) {
			case 0 -> Instant.class;
			case 1 -> String.class;
			case 3, 4, 5 -> Double.class;
			case 6 -> Long.class;
			case 7 -> Integer.class;
			case 8 -> Boolean.class;
			default -> String.class;
		};
	}

	@Override
	public Object getValueAt(int row, int column) {
		var event = this.events.get(row);
		return switch (column) {
			case 0 -> event.timestamp();
			case 1 -> typeLabel(event.kind());
			case 2 -> pattern(event);
			case 3 -> event.durationNanos() == 0 ? null : event.durationMillis();
			case 4 -> event.fetchNanos() == 0 ? null : event.fetchMillis();
			case 5 -> event.resultSetUseNanos() == 0 ? null : event.resultSetUseMillis();
			case 6 -> event.rows() < 0 ? null : event.rows();
			case 7 -> event.queryTimeout() == 0 ? null : event.queryTimeout();
			case 8 -> event.kind() == SqlEvent.Kind.RESULT_SET ? null : event.autoCommit();
			case 9 -> isolation(event.transactionIsolation());
			case 10 -> event.connection();
			case 11 -> event.thread();
			case 12 -> event.callSite();
			case 13 -> event.sql().isBlank() ? event.rawSql() : event.sql();
			default -> event.success() ? "OK" : "FAILED";
		};
	}

	private String pattern(SqlEvent event) {
		int repetitions = this.nPlusOneDetector.repetitions(event.id());
		String nPlusOne = repetitions == 0 ? "" : "N+1 \u00d7" + repetitions;
		String cartesianProduct = switch (this.cartesianProducts.getOrDefault(event.id(), Finding.NONE)) {
			case NONE -> "";
			case EXPLICIT_CROSS_JOIN -> "Cartesian (CROSS JOIN)";
			case UNCONSTRAINED_COMMA_JOIN -> "Cartesian (comma join)";
		};
		if (nPlusOne.isBlank()) {
			return cartesianProduct;
		}
		return cartesianProduct.isBlank() ? nPlusOne : nPlusOne + "; " + cartesianProduct;
	}

	private static String sql(SqlEvent event) {
		if (!event.rawSql().isBlank()) {
			return event.rawSql();
		}
		return event.sql().isBlank() ? event.fingerprint() : event.sql();
	}

	private void addMetrics(SqlEvent event) {
		this.totalDurationMillis += event.durationMillis();
		if (!event.success()) {
			this.failedCount++;
		}
		if (event.rows() > 0) {
			this.rowCount += event.rows();
		}
	}

	private void removeMetrics(SqlEvent event) {
		this.totalDurationMillis -= event.durationMillis();
		if (!event.success()) {
			this.failedCount--;
		}
		if (event.rows() > 0) {
			this.rowCount -= event.rows();
		}
	}

	private boolean mergeResultSet(SqlEvent resultSetEvent) {
		int statementIndex = findById(resultSetEvent.parentId());
		if (statementIndex < 0) {
			return false;
		}
		var statementEvent = this.events.get(statementIndex);
		if (!statementEvent.kind().isSqlStatement()) {
			return false;
		}
		var merged = merge(statementEvent, resultSetEvent);
		this.events.set(statementIndex, merged);
		removeMetrics(statementEvent);
		addMetrics(merged);
		return true;
	}

	private int findById(long eventId) {
		for (int index = this.events.size() - 1; index >= 0; index--) {
			if (this.events.get(index).id() == eventId) {
				return index;
			}
		}
		return -1;
	}

	private static SqlEvent merge(SqlEvent statementEvent, SqlEvent resultSetEvent) {
		var success = statementEvent.success() && resultSetEvent.success();
		var error = statementEvent.error();
		if (!resultSetEvent.error().isBlank()) {
			error = resultSetEvent.error();
		}
		var rows = resultSetEvent.rows() >= 0 ? resultSetEvent.rows() : statementEvent.rows();
		var fetchNanos = Math.max(statementEvent.fetchNanos(), resultSetEvent.fetchNanos());
		var resultSetUseNanos = Math.max(statementEvent.resultSetUseNanos(), resultSetEvent.resultSetUseNanos());
		var stackTrace = statementEvent.stackTrace().isBlank() ? resultSetEvent.stackTrace()
				: statementEvent.stackTrace();
		return new SqlEvent(statementEvent.id(), statementEvent.parentId(), statementEvent.transactionId(),
				statementEvent.timestamp(), statementEvent.thread(), statementEvent.connection(), statementEvent.kind(),
				statementEvent.rawSql(), statementEvent.sql(), statementEvent.parameters(),
				statementEvent.parameterMethods(), statementEvent.durationNanos(), fetchNanos, resultSetUseNanos, rows,
				success, error, statementEvent.queryTimeout(), statementEvent.autoCommit(),
				statementEvent.transactionIsolation(), statementEvent.connectionUrl(),
				statementEvent.connectionProperties(), statementEvent.fingerprint(), statementEvent.callSite(),
				stackTrace);
	}

	private static String isolation(int isolation) {
		return switch (isolation) {
			case 0 -> "None";
			case 1 -> "Read uncommitted";
			case 2 -> "Read committed";
			case 4 -> "Repeatable read";
			case 8 -> "Serializable";
			default -> Integer.toString(isolation);
		};
	}

	private static String typeLabel(SqlEvent.Kind kind) {
		return switch (kind) {
			case QUERY, UPDATE, EXECUTE, BATCH -> "Statement";
			case RESULT_SET -> "Result set";
			case CONNECTION -> "Connection open";
			case CONNECTION_CLOSE -> "Connection close";
			case TRANSACTION_BEGIN -> "Transaction begin";
			case SAVEPOINT -> "Savepoint";
			case SAVEPOINT_ROLLBACK -> "Savepoint rollback";
			case SAVEPOINT_RELEASE -> "Savepoint release";
			case AUTOCOMMIT_CHANGE -> "Auto commit change";
			case ISOLATION_CHANGE -> "Isolation change";
			case COMMIT -> "Commit";
			case ROLLBACK -> "Rollback";
		};
	}

}
