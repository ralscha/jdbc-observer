package ch.rasc.jdbcobserver.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.rasc.jdbcobserver.core.SqlEvent;
import java.time.Instant;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class NPlusOneDetectorTest {

	@Test
	void marksARepeatedFingerprintFromTheSameCallSite() {
		var detector = new NPlusOneDetector();
		var started = Instant.parse("2026-01-01T00:00:00Z");
		for (int index = 1; index <= 5; index++) {
			detector.add(event(index, started.plusMillis(index * 10L), "select * from child where parent_id = ?"));
		}
		assertEquals(5, detector.repetitions(1));
		assertEquals(5, detector.repetitions(5));
	}

	@Test
	void doesNotCombineDifferentCallSites() {
		var detector = new NPlusOneDetector();
		for (int index = 1; index <= 5; index++) {
			detector.add(event(index, Instant.ofEpochMilli(index), "fingerprint-" + index));
		}
		assertEquals(0, detector.repetitions(5));
	}

	@Test
	void reconfiguresAndReevaluatesRetainedEvents() {
		var detector = new NPlusOneDetector();
		var events = LongStream.rangeClosed(1, 3)
			.mapToObj(id -> event(id, Instant.ofEpochMilli(id * 10), "same fingerprint"))
			.toList();
		events.forEach(detector::add);
		assertEquals(0, detector.repetitions(1));

		detector.configure(3, 100, events);

		assertEquals(3, detector.repetitions(1));
		assertEquals(3, detector.threshold());
		assertEquals(100, detector.windowMillis());
	}

	private static SqlEvent event(long id, Instant timestamp, String fingerprint) {
		return new SqlEvent(id, 0, 1, timestamp, "worker", "connection", SqlEvent.Kind.QUERY, "select", "select",
				Map.of(), Map.of(), 1, 0, 0, 1, true, "", 0, true, 2, "jdbc:test", "", fingerprint,
				"example.Repository.find(Repository.java:42)", "");
	}

}
