package ch.rasc.jdbcobserver.ui;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import ch.rasc.jdbcobserver.core.SqlEvent;
import ch.rasc.jdbcobserver.core.SqlText;

final class RepetitionDetector {

	enum Pattern {

		NONE, REDUNDANT, N_PLUS_ONE, BATCH_CANDIDATE, AUTOCOMMIT_WRITE_LOOP

	}

	record Detection(Pattern pattern, int repetitions) {

		private static final Detection NONE = new Detection(Pattern.NONE, 0);

	}

	private int threshold = Math.max(2,
			integerProperty("jdbcObserver.repetitionThreshold", "jdbcObserver.nPlusOneThreshold", 5));

	private long windowMillis = Math.max(1,
			longProperty("jdbcObserver.repetitionWindowMillis", "jdbcObserver.nPlusOneWindowMillis", 1_000L));

	private final Map<Key, ArrayDeque<SqlEvent>> windows = new HashMap<>();

	private final Map<Long, Detection> detections = new HashMap<>();

	void add(SqlEvent event) {
		var statementType = statementType(event);
		if (statementType == null || event.fingerprint().isBlank() || event.callSite().isBlank()) {
			return;
		}
		var key = new Key(event.fingerprint(), event.callSite(), event.thread(), event.connection(),
				event.transactionId(), event.autoCommit(), statementType);
		var window = this.windows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
		var oldest = event.timestamp().toEpochMilli() - this.windowMillis;
		while (!window.isEmpty() && window.getFirst().timestamp().toEpochMilli() < oldest) {
			window.removeFirst();
		}
		window.addLast(event);
		if (window.size() >= this.threshold) {
			var pattern = classify(window, statementType, event.autoCommit());
			var detection = new Detection(pattern, window.size());
			window.forEach(item -> this.detections.put(item.id(), detection));
		}
	}

	Detection detection(long eventId) {
		return this.detections.getOrDefault(eventId, Detection.NONE);
	}

	void remove(SqlEvent event) {
		this.detections.remove(event.id());
		var statementType = statementType(event);
		if (statementType == null || event.fingerprint().isBlank() || event.callSite().isBlank()) {
			return;
		}
		var key = new Key(event.fingerprint(), event.callSite(), event.thread(), event.connection(),
				event.transactionId(), event.autoCommit(), statementType);
		var window = this.windows.get(key);
		if (window == null) {
			return;
		}
		window.removeIf(item -> item.id() == event.id());
		if (window.isEmpty()) {
			this.windows.remove(key);
		}
	}

	int trackedEventCount() {
		return this.windows.values().stream().mapToInt(ArrayDeque::size).sum();
	}

	int threshold() {
		return this.threshold;
	}

	long windowMillis() {
		return this.windowMillis;
	}

	void configure(int threshold, long windowMillis, Iterable<SqlEvent> events) {
		if (threshold < 2) {
			throw new IllegalArgumentException("Repetition threshold must be at least 2");
		}
		if (windowMillis < 1) {
			throw new IllegalArgumentException("Repetition window must be positive");
		}
		this.threshold = threshold;
		this.windowMillis = windowMillis;
		clear();
		events.forEach(this::add);
	}

	void clear() {
		this.windows.clear();
		this.detections.clear();
	}

	private static Pattern classify(Iterable<SqlEvent> window, StatementType statementType, boolean autoCommit) {
		var invocations = new HashSet<Invocation>();
		for (var event : window) {
			invocations.add(invocation(event));
			if (invocations.size() > 1) {
				if (statementType == StatementType.READ) {
					return Pattern.N_PLUS_ONE;
				}
				return autoCommit ? Pattern.AUTOCOMMIT_WRITE_LOOP : Pattern.BATCH_CANDIDATE;
			}
		}
		return Pattern.REDUNDANT;
	}

	private static Invocation invocation(SqlEvent event) {
		if (!event.parameters().isEmpty()) {
			return new Invocation(event.parameters(), "");
		}
		var concreteSql = event.rawSql().isBlank() ? event.sql() : event.rawSql();
		return new Invocation(Map.of(), concreteSql.strip());
	}

	private static StatementType statementType(SqlEvent event) {
		return switch (event.kind()) {
			case QUERY -> StatementType.READ;
			case UPDATE -> StatementType.WRITE;
			case EXECUTE -> switch (SqlText.operation(event.rawSql().isBlank() ? event.sql() : event.rawSql())) {
				case "select", "values", "table" -> StatementType.READ;
				case "insert", "update", "delete", "merge" -> StatementType.WRITE;
				default -> null;
			};
			default -> null;
		};
	}

	private static int integerProperty(String name, String legacyName, int defaultValue) {
		return Integer.getInteger(name, Integer.getInteger(legacyName, defaultValue));
	}

	private static long longProperty(String name, String legacyName, long defaultValue) {
		return Long.getLong(name, Long.getLong(legacyName, defaultValue));
	}

	private enum StatementType {

		READ, WRITE

	}

	private record Invocation(Map<Integer, String> parameters, String concreteSql) {
	}

	private record Key(String fingerprint, String callSite, String thread, String connection, long transactionId,
			boolean autoCommit, StatementType statementType) {
	}

}
