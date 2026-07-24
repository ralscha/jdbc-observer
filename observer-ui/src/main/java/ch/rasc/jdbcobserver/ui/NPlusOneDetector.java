package ch.rasc.jdbcobserver.ui;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import ch.rasc.jdbcobserver.core.SqlEvent;

final class NPlusOneDetector {

	private int threshold = Math.max(2, Integer.getInteger("jdbcObserver.nPlusOneThreshold", 5));

	private long windowMillis = Math.max(1, Long.getLong("jdbcObserver.nPlusOneWindowMillis", 1_000L));

	private final Map<Key, ArrayDeque<SqlEvent>> windows = new HashMap<>();

	private final Map<Long, Integer> repetitions = new HashMap<>();

	void add(SqlEvent event) {
		if (event.fingerprint().isBlank() || event.callSite().isBlank() || !isStatement(event))
			return;
		var key = new Key(event.fingerprint(), event.callSite(), event.thread(), event.connection());
		var window = this.windows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
		var oldest = event.timestamp().toEpochMilli() - this.windowMillis;
		while (!window.isEmpty() && window.getFirst().timestamp().toEpochMilli() < oldest)
			window.removeFirst();
		window.addLast(event);
		if (window.size() >= this.threshold) {
			int count = window.size();
			window.forEach(item -> this.repetitions.put(item.id(), count));
		}
	}

	int repetitions(long eventId) {
		return this.repetitions.getOrDefault(eventId, 0);
	}

	void remove(SqlEvent event) {
		this.repetitions.remove(event.id());
		if (event.fingerprint().isBlank() || event.callSite().isBlank() || !isStatement(event)) {
			return;
		}
		var key = new Key(event.fingerprint(), event.callSite(), event.thread(), event.connection());
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
		if (threshold < 2)
			throw new IllegalArgumentException("N+1 threshold must be at least 2");
		if (windowMillis < 1)
			throw new IllegalArgumentException("N+1 window must be positive");
		this.threshold = threshold;
		this.windowMillis = windowMillis;
		clear();
		events.forEach(this::add);
	}

	void clear() {
		this.windows.clear();
		this.repetitions.clear();
	}

	private static boolean isStatement(SqlEvent event) {
		if (event.kind() == SqlEvent.Kind.QUERY) {
			return event.sql().trim().toLowerCase().startsWith("select");
		}
		return switch (event.kind()) {
			case UPDATE, BATCH -> true;
			default -> false;
		};
	}

	private record Key(String fingerprint, String callSite, String thread, String connection) {
	}

}
