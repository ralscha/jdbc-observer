package ch.rasc.jdbcobserver.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.rasc.jdbcobserver.core.SqlEvent;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

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

	private static SqlEvent event(long id, String fingerprint, boolean success) {
		return new SqlEvent(id, 0, 1, Instant.ofEpochSecond(id), "worker", "connection", SqlEvent.Kind.QUERY, "select",
				"select", Map.of(), Map.of(), id * 1_000_000, 0, 0, id, success, success ? "" : "failed", 0, true, 2,
				"jdbc:test", "", fingerprint, "example.Repository.find(Repository.java:42)", "");
	}

}
