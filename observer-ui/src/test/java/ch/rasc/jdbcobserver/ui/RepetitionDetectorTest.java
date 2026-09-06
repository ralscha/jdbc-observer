package ch.rasc.jdbcobserver.ui;

import static ch.rasc.jdbcobserver.ui.RepetitionDetector.Pattern.AUTOCOMMIT_WRITE_LOOP;
import static ch.rasc.jdbcobserver.ui.RepetitionDetector.Pattern.BATCH_CANDIDATE;
import static ch.rasc.jdbcobserver.ui.RepetitionDetector.Pattern.NONE;
import static ch.rasc.jdbcobserver.ui.RepetitionDetector.Pattern.N_PLUS_ONE;
import static ch.rasc.jdbcobserver.ui.RepetitionDetector.Pattern.REDUNDANT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Map;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;

import ch.rasc.jdbcobserver.core.SqlEvent;

class RepetitionDetectorTest {

	@Test
	void classifiesIdenticalReadsAsRedundant() {
		var detector = new RepetitionDetector();
		var started = Instant.parse("2026-01-01T00:00:00Z");
		for (int index = 1; index <= 5; index++) {
			detector.add(event(index, started.plusMillis(index * 10L), SqlEvent.Kind.QUERY, "select child",
					Map.of(1, "42"), 1, "find"));
		}

		assertDetection(detector, 1, REDUNDANT, 5);
		assertDetection(detector, 5, REDUNDANT, 5);
	}

	@Test
	void classifiesReadsWithDifferentParametersAsNPlusOne() {
		var detector = new RepetitionDetector();
		var started = Instant.parse("2026-01-01T00:00:00Z");
		for (int index = 1; index <= 5; index++) {
			detector.add(event(index, started.plusMillis(index * 10L), SqlEvent.Kind.QUERY, "select child",
					Map.of(1, Integer.toString(index)), 1, "find"));
		}

		assertDetection(detector, 1, N_PLUS_ONE, 5);
		assertDetection(detector, 5, N_PLUS_ONE, 5);
	}

	@Test
	void reclassifiesAReadWindowWhenParametersStartVarying() {
		var detector = new RepetitionDetector();
		detector.configure(3, 1_000, java.util.List.of());
		var started = Instant.parse("2026-01-01T00:00:00Z");
		for (int index = 1; index <= 3; index++) {
			detector.add(event(index, started.plusMillis(index), SqlEvent.Kind.QUERY, "select child", Map.of(1, "42"),
					1, "find"));
		}
		assertDetection(detector, 1, REDUNDANT, 3);

		detector.add(event(4, started.plusMillis(4), SqlEvent.Kind.QUERY, "select child", Map.of(1, "43"), 1, "find"));

		assertDetection(detector, 1, N_PLUS_ONE, 4);
		assertDetection(detector, 4, N_PLUS_ONE, 4);
	}

	@Test
	void classifiesParameterVaryingWritesAsBatchCandidates() {
		var detector = new RepetitionDetector();
		var started = Instant.parse("2026-01-01T00:00:00Z");
		for (int index = 1; index <= 5; index++) {
			detector.add(event(index, started.plusMillis(index * 10L), SqlEvent.Kind.UPDATE, "update child",
					Map.of(1, Integer.toString(index)), 1, "save"));
		}

		assertDetection(detector, 1, BATCH_CANDIDATE, 5);
		assertDetection(detector, 5, BATCH_CANDIDATE, 5);
	}

	@Test
	void upgradesAutocommitWritesToWriteLoops() {
		var detector = new RepetitionDetector();
		var started = Instant.parse("2026-01-01T00:00:00Z");
		for (int index = 1; index <= 5; index++) {
			detector.add(event(index, started.plusMillis(index * 10L), SqlEvent.Kind.UPDATE, "update child",
					Map.of(1, Integer.toString(index)), 0, "save", true));
		}

		assertDetection(detector, 1, AUTOCOMMIT_WRITE_LOOP, 5);
		assertDetection(detector, 5, AUTOCOMMIT_WRITE_LOOP, 5);
	}

	@Test
	void classifiesIdenticalWritesAsRedundant() {
		var detector = new RepetitionDetector();
		var started = Instant.parse("2026-01-01T00:00:00Z");
		for (int index = 1; index <= 5; index++) {
			detector.add(event(index, started.plusMillis(index * 10L), SqlEvent.Kind.UPDATE, "delete child",
					Map.of(1, "42"), 1, "delete"));
		}

		assertDetection(detector, 5, REDUNDANT, 5);
	}

	@Test
	void usesConcreteSqlToDistinguishStatementInvocations() {
		var detector = new RepetitionDetector();
		var started = Instant.parse("2026-01-01T00:00:00Z");
		for (int index = 1; index <= 5; index++) {
			detector.add(new SqlEvent(index, 0, 1, started.plusMillis(index), "worker", "connection",
					SqlEvent.Kind.QUERY, "", "select * from child where id = " + index, Map.of(), Map.of(), 1, 0, 0, 1,
					true, "", 0, false, 2, "jdbc:test", "", "select * from child where id = ?",
					"example.Repository.find(Repository.java:42)", ""));
		}

		assertDetection(detector, 5, N_PLUS_ONE, 5);
	}

	@Test
	void classifiesGenericExecuteReadsAndWritesIncludingCtes() {
		var detector = new RepetitionDetector();
		var started = Instant.parse("2026-01-01T00:00:00Z");
		for (int index = 1; index <= 5; index++) {
			detector.add(event(index, started.plusMillis(index), SqlEvent.Kind.EXECUTE,
					"/* lookup */ with ids as (select 1) select * from child where id = ?", Map.of(1, "" + index), 1,
					"find"));
			detector.add(event(index + 5, started.plusMillis(index), SqlEvent.Kind.EXECUTE,
					"with ids as (select 1) update child set name = ?", Map.of(1, "" + index), 0, "save", true));
			detector.add(event(index + 10, started.plusMillis(index), SqlEvent.Kind.EXECUTE, "call unknown(?)",
					Map.of(1, "" + index), 0, "call", true));
		}
		assertDetection(detector, 5, N_PLUS_ONE, 5);
		assertDetection(detector, 10, AUTOCOMMIT_WRITE_LOOP, 5);
		assertDetection(detector, 15, NONE, 0);
	}

	@Test
	void ignoresExecutionsThatAreAlreadyBatched() {
		var detector = new RepetitionDetector();
		var started = Instant.parse("2026-01-01T00:00:00Z");
		for (int index = 1; index <= 5; index++) {
			detector.add(event(index, started.plusMillis(index), SqlEvent.Kind.BATCH, "update child",
					Map.of(1, Integer.toString(index)), 1, "save"));
		}

		assertDetection(detector, 5, NONE, 0);
		assertEquals(0, detector.trackedEventCount());
	}

	@Test
	void keepsCallSitesThreadsConnectionsAndTransactionsSeparate() {
		var started = Instant.parse("2026-01-01T00:00:00Z");

		var transactions = new RepetitionDetector();
		for (int index = 1; index <= 5; index++) {
			transactions.add(event(index, started.plusMillis(index), SqlEvent.Kind.QUERY, "select child",
					Map.of(1, Integer.toString(index)), index, "find", "worker", "connection", false));
		}
		assertDetection(transactions, 5, NONE, 0);

		var callSites = new RepetitionDetector();
		for (int index = 1; index <= 5; index++) {
			callSites.add(event(index, started.plusMillis(index), SqlEvent.Kind.QUERY, "select child",
					Map.of(1, Integer.toString(index)), 1, "find-" + index, "worker", "connection", false));
		}
		assertDetection(callSites, 5, NONE, 0);

		var threads = new RepetitionDetector();
		for (int index = 1; index <= 5; index++) {
			threads.add(event(index, started.plusMillis(index), SqlEvent.Kind.QUERY, "select child",
					Map.of(1, Integer.toString(index)), 1, "find", "worker-" + index, "connection", false));
		}
		assertDetection(threads, 5, NONE, 0);

		var connections = new RepetitionDetector();
		for (int index = 1; index <= 5; index++) {
			connections.add(event(index, started.plusMillis(index), SqlEvent.Kind.QUERY, "select child",
					Map.of(1, Integer.toString(index)), 1, "find", "worker", "connection-" + index, false));
		}
		assertDetection(connections, 5, NONE, 0);

		var autocommitModes = new RepetitionDetector();
		for (int index = 1; index <= 5; index++) {
			autocommitModes.add(event(index, started.plusMillis(index), SqlEvent.Kind.UPDATE, "update child",
					Map.of(1, Integer.toString(index)), 0, "save", index % 2 == 0));
		}
		assertDetection(autocommitModes, 5, NONE, 0);
	}

	@Test
	void onlyCombinesExecutionsInsideTheConfiguredWindow() {
		var detector = new RepetitionDetector();
		detector.configure(3, 100, java.util.List.of());
		for (int index = 1; index <= 3; index++) {
			detector.add(event(index, Instant.ofEpochMilli(index * 200L), SqlEvent.Kind.QUERY, "select child",
					Map.of(1, Integer.toString(index)), 1, "find"));
		}

		assertDetection(detector, 1, NONE, 0);
		assertDetection(detector, 3, NONE, 0);
	}

	@Test
	void reconfiguresAndReevaluatesRetainedEvents() {
		var detector = new RepetitionDetector();
		var events = LongStream.rangeClosed(1, 3)
			.mapToObj(id -> event(id, Instant.ofEpochMilli(id * 10), SqlEvent.Kind.QUERY, "select child",
					Map.of(1, Long.toString(id)), 1, "find"))
			.toList();
		events.forEach(detector::add);
		assertDetection(detector, 1, NONE, 0);

		detector.configure(3, 100, events);

		assertDetection(detector, 1, N_PLUS_ONE, 3);
		assertEquals(3, detector.threshold());
		assertEquals(100, detector.windowMillis());
	}

	private static void assertDetection(RepetitionDetector detector, long eventId, RepetitionDetector.Pattern pattern,
			int repetitions) {
		var detection = detector.detection(eventId);
		assertEquals(pattern, detection.pattern());
		assertEquals(repetitions, detection.repetitions());
	}

	private static SqlEvent event(long id, Instant timestamp, SqlEvent.Kind kind, String fingerprint,
			Map<Integer, String> parameters, long transactionId, String callSite) {
		return event(id, timestamp, kind, fingerprint, parameters, transactionId, callSite, "worker", "connection",
				false);
	}

	private static SqlEvent event(long id, Instant timestamp, SqlEvent.Kind kind, String fingerprint,
			Map<Integer, String> parameters, long transactionId, String callSite, boolean autoCommit) {
		return event(id, timestamp, kind, fingerprint, parameters, transactionId, callSite, "worker", "connection",
				autoCommit);
	}

	private static SqlEvent event(long id, Instant timestamp, SqlEvent.Kind kind, String fingerprint,
			Map<Integer, String> parameters, long transactionId, String callSite, String thread, String connection,
			boolean autoCommit) {
		return new SqlEvent(id, 0, transactionId, timestamp, thread, connection, kind, fingerprint, fingerprint,
				parameters, Map.of(), 1, 0, 0, 1, true, "", 0, autoCommit, 2, "jdbc:test", "", fingerprint,
				"example.Repository." + callSite + "(Repository.java:42)", "");
	}

}
