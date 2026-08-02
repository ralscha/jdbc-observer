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
		assertTrue(model.trackedNPlusOneEventCount() <= 3);
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
	void reportsCartesianProductSyntaxInThePatternColumn() {
		var model = new EventTableModel(10);
		model.add(event(1, "select * from customer cross join country", "cartesian", true));

		assertEquals("Cartesian (CROSS JOIN)", model.getValueAt(0, 2));
	}

	private static SqlEvent event(long id, String fingerprint, boolean success) {
		return event(id, "select", fingerprint, success);
	}

	private static SqlEvent event(long id, String sql, String fingerprint, boolean success) {
		return new SqlEvent(id, 0, 1, Instant.ofEpochSecond(id), "worker", "connection", SqlEvent.Kind.QUERY, sql, sql,
				Map.of(), Map.of(), id * 1_000_000, 0, 0, -1, success, success ? "" : "failed", 0, true, 2, "jdbc:test",
				"", fingerprint, "example.Repository.find(Repository.java:42)", "");
	}

	private static SqlEvent resultSetEvent(long id, long parentId, long rows, boolean success) {
		return new SqlEvent(id, parentId, 1, Instant.ofEpochSecond(id), "worker", "connection",
				SqlEvent.Kind.RESULT_SET, "", "", Map.of(), Map.of(), 0, 2_000_000, 4_000_000, rows, success,
				success ? "" : "failed", 0, true, 2, "jdbc:test", "", "", "", "");
	}

}
