package ch.rasc.jdbcobserver.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ch.rasc.jdbcobserver.core.SqlEvent;
import ch.rasc.jdbcobserver.ui.TransactionTimelineDialog.TransactionSummary;

class TransactionSummaryTest {

	@Test
	void delayedResultSetEventsDoNotReopenCompletedTransactions() {
		var events = List.of(event(1, SqlEvent.Kind.TRANSACTION_BEGIN, true), event(2, SqlEvent.Kind.COMMIT, true),
				event(3, SqlEvent.Kind.RESULT_SET, true));

		var summary = TransactionSummary.create(1, events, Instant.ofEpochSecond(10));

		assertFalse(summary.active());
		assertEquals("COMMIT", summary.status());
		assertEquals(1_000, summary.durationMillis());
		assertEquals(0, summary.idleMillis());
		assertEquals(3, summary.events().size());
	}

	@Test
	void failedCommitsRemainActiveUntilSuccessfulRollback() {
		var begin = event(1, SqlEvent.Kind.TRANSACTION_BEGIN, true);
		var failedCommit = event(2, SqlEvent.Kind.COMMIT, false);
		var now = Instant.ofEpochSecond(10);
		var active = TransactionSummary.create(1, List.of(begin, failedCommit), now);
		assertTrue(active.active());
		assertEquals(9_000, active.durationMillis());
		assertEquals(8_000, active.idleMillis());

		var completed = TransactionSummary.create(1,
				List.of(begin, failedCommit, event(3, SqlEvent.Kind.ROLLBACK, true)), now);
		assertFalse(completed.active());
		assertEquals("ROLLBACK", completed.status());
		assertEquals(2_000, completed.durationMillis());
	}

	@Test
	void clockSkewDoesNotProduceNegativeDurations() {
		var summary = TransactionSummary.create(1, List.of(event(10, SqlEvent.Kind.TRANSACTION_BEGIN, true)),
				Instant.ofEpochSecond(1));

		assertEquals(0, summary.durationMillis());
		assertEquals(0, summary.idleMillis());
	}

	private static SqlEvent event(long second, SqlEvent.Kind kind, boolean success) {
		return new SqlEvent(second, 0, 1, Instant.ofEpochSecond(second), "worker", "connection", kind, "", "", Map.of(),
				Map.of(), 0, 0, 0, -1, success, "", 0, false, 2, "", "", "", "", "");
	}

}
