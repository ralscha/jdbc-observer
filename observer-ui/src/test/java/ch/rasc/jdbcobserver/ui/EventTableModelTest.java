package ch.rasc.jdbcobserver.ui;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import ch.rasc.jdbcobserver.core.SqlEvent;

class EventTableModelTest {

	@Test
	void keepsHistoryDetectorAndMetricsBoundedTogether() {
		var model = new EventTableModel(3);
		for (int index = 1; index <= 20; index++) {
			model.add(event(index, "fingerprint-" + index, index % 2 == 0));
		}

		assertEquals(3, model.getRowCount());
		assertTrue(model.trackedRepetitionEventCount() <= 3);
		assertEquals(1, model.failedCount());
		assertEquals(0, model.observedRowCount());
		assertEquals(57.0, model.totalDurationMillis(), 0.000_001);
	}

	@Test
	void reportsTypesMatchingTheValuesReturnedByTheModel() {
		var model = new EventTableModel(10);
		model.add(event(1, "select ?", true));

		for (int column = 0; column < model.getColumnCount(); column++) {
			var value = model.getValueAt(0, column);
			assertTrue(value == null || model.getColumnClass(column).isInstance(value),
					"column " + column + " returns " + value + " but declares " + model.getColumnClass(column));
		}
	}

	@Test
	void mergesResultSetIntoItsParentStatementRow() {
		var model = new EventTableModel(10);
		model.add(event(1, "select ?", true));
		model.add(resultSetEvent(2, 1, 7, true));

		assertEquals(1, model.getRowCount());
		assertEquals("Statement", model.getValueAt(0, 1));
		assertEquals(1.0, (Double) model.getValueAt(0, 3), 0.000_001);
		assertEquals(2.0, (Double) model.getValueAt(0, 4), 0.000_001);
		assertEquals(4.0, (Double) model.getValueAt(0, 5), 0.000_001);
		assertEquals(7L, model.getValueAt(0, 6));
		assertEquals(7, model.observedRowCount());
	}

	@Test
	void accumulatesMultipleResultSetsAndPreservesUpdateCountsWhenReadingGeneratedKeys() {
		var model = new EventTableModel(10);
		model.add(event(1, "select ?", true));
		model.add(resultSetEvent(2, 1, 7, true));
		model.add(resultSetEvent(3, 1, 3, true));
		assertEquals(10L, model.getValueAt(0, 6));
		assertEquals(4.0, model.getValueAt(0, 4));

		for (var kind : java.util.List.of(SqlEvent.Kind.UPDATE, SqlEvent.Kind.EXECUTE, SqlEvent.Kind.BATCH)) {
			var write = new SqlEvent(4, 0, 0, Instant.now(), "worker", "connection", kind,
					"insert into item values (1), (2), (3)", "", Map.of(), Map.of(), 1, 0, 0, 3, true, "", 0, true, 2,
					"", "", "", "", "");
			model.clear();
			model.add(write);
			model.add(resultSetEvent(5, 4, 1, true));
			assertEquals(3L, model.getValueAt(0, 6));
			assertEquals(3L, model.observedRowCount());
		}
	}

	@Test
	void countsRowsReturnedByGenericWritesWithoutAnUpdateCount() {
		var model = new EventTableModel(1);
		model.add(event(1, SqlEvent.Kind.EXECUTE, "insert into item values (1) returning id", "", true));
		model.add(resultSetEvent(2, 1, 2, true));
		model.add(resultSetEvent(3, 1, 3, true));
		assertEquals(5L, model.getValueAt(0, 6));
		// Evicting and clearing history must also discard the update-count
		// classification.
		model.add(event(4, SqlEvent.Kind.UPDATE, "update item set id = 2", "", true));
		model.add(event(1, SqlEvent.Kind.QUERY, "select id from item", "", true));
		model.add(resultSetEvent(5, 1, 7, true));
		assertEquals(7L, model.getValueAt(0, 6));
		model.clear();
		model.add(event(4, SqlEvent.Kind.QUERY, "select id from item", "", true));
		model.add(resultSetEvent(6, 4, 9, true));
		assertEquals(9L, model.getValueAt(0, 6));
	}

	@Test
	void reportsCartesianProductSyntaxInThePatternColumn() {
		var model = new EventTableModel(10);
		model.add(event(1, "select * from customer cross join country", "cartesian", true));

		assertEquals("Cartesian (CROSS JOIN)", model.getValueAt(0, 2));
	}

	@Test
	void reportsTheSpecificRepetitionClassificationInThePatternColumn() {
		var model = new EventTableModel(10);
		model.configureRepetitionDetection(2, 10_000);
		model.add(event(1, "select * from child where id = 1", "select * from child where id = ?", true));
		model.add(event(2, "select * from child where id = 2", "select * from child where id = ?", true));

		assertEquals("N+1 \u00d72", model.getValueAt(0, 2));
		assertEquals("N+1 \u00d72", model.getValueAt(1, 2));
	}

	@Test
	void reportsRedundantBatchCandidateAndAutocommitWriteLoopLabels() {
		var model = new EventTableModel(10);
		model.configureRepetitionDetection(2, 10_000);
		model.add(event(1, SqlEvent.Kind.QUERY, "select * from child where id = 1", "select child", true));
		model.add(event(2, SqlEvent.Kind.QUERY, "select * from child where id = 1", "select child", true));
		model.add(event(3, SqlEvent.Kind.UPDATE, "update child set name = 'a' where id = 1",
				"update child set name = ? where id = ?", true, false));
		model.add(event(4, SqlEvent.Kind.UPDATE, "update child set name = 'b' where id = 2",
				"update child set name = ? where id = ?", true, false));
		model.add(event(5, SqlEvent.Kind.UPDATE, "update item set name = 'a' where id = 1",
				"update item set name = ? where id = ?", true, true));
		model.add(event(6, SqlEvent.Kind.UPDATE, "update item set name = 'b' where id = 2",
				"update item set name = ? where id = ?", true, true));

		assertEquals("Redundant \u00d72", model.getValueAt(0, 2));
		assertEquals("Redundant \u00d72", model.getValueAt(1, 2));
		assertEquals("Batch candidate \u00d72", model.getValueAt(2, 2));
		assertEquals("Batch candidate \u00d72", model.getValueAt(3, 2));
		assertEquals("Autocommit write loop \u00d72", model.getValueAt(4, 2));
		assertEquals("Autocommit write loop \u00d72", model.getValueAt(5, 2));
	}

	@Test
	void reportsBroadDmlInThePatternColumn() {
		var model = new EventTableModel(10);
		model.add(event(1, SqlEvent.Kind.UPDATE, "update customer set active = false", "update customer set active = ?",
				true));
		model.add(event(2, SqlEvent.Kind.UPDATE, "delete from audit_log", "delete from audit_log", true));

		assertEquals("UPDATE without WHERE", model.getValueAt(0, 2));
		assertEquals("DELETE without WHERE", model.getValueAt(1, 2));
	}

	@Test
	void doesNotAnalyzeLifecycleDetailsAsSql() {
		var model = new EventTableModel(10);
		model.add(event(1, SqlEvent.Kind.SAVEPOINT, "update", "", true));

		assertEquals("", model.getValueAt(0, 2));
	}

	private static SqlEvent event(long id, String fingerprint, boolean success) {
		return event(id, "select", fingerprint, success);
	}

	private static SqlEvent event(long id, String sql, String fingerprint, boolean success) {
		return event(id, SqlEvent.Kind.QUERY, sql, fingerprint, success);
	}

	private static SqlEvent event(long id, SqlEvent.Kind kind, String sql, String fingerprint, boolean success) {
		return event(id, kind, sql, fingerprint, success, true);
	}

	private static SqlEvent event(long id, SqlEvent.Kind kind, String sql, String fingerprint, boolean success,
			boolean autoCommit) {
		return new SqlEvent(id, 0, autoCommit ? 0 : 1, Instant.ofEpochSecond(id), "worker", "connection", kind, sql,
				sql, Map.of(), Map.of(), id * 1_000_000, 0, 0, -1, success, success ? "" : "failed", 0, autoCommit, 2,
				"jdbc:test", "", fingerprint, "example.Repository.find(Repository.java:42)", "");
	}

	private static SqlEvent resultSetEvent(long id, long parentId, long rows, boolean success) {
		return new SqlEvent(id, parentId, 1, Instant.ofEpochSecond(id), "worker", "connection",
				SqlEvent.Kind.RESULT_SET, "", "", Map.of(), Map.of(), 0, 2_000_000, 4_000_000, rows, success,
				success ? "" : "failed", 0, true, 2, "jdbc:test", "", "", "", "");
	}

}
