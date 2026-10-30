package ch.rasc.jdbcobserver.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import javax.swing.JTable;
import javax.swing.RowFilter;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;
import javax.swing.table.TableRowSorter;

import org.junit.jupiter.api.Test;

import ch.rasc.jdbcobserver.core.SqlEvent;

class ObserverAppTest {

	@Test
	void preservesSelectedEventWhenNewEventsChangeSortedPositions() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			var model = new EventTableModel(3);
			model.addAll(List.of(event(1, SqlEvent.Kind.QUERY, true), event(2, SqlEvent.Kind.QUERY, true)));
			var table = new JTable(model);
			var sorter = new TableRowSorter<>(model);
			table.setRowSorter(sorter);
			sorter.setSortKeys(List.of(new RowSorter.SortKey(3, SortOrder.DESCENDING)));
			table.setRowSelectionInterval(0, 0);

			ObserverApp.appendEvents(model, table, List.of(event(3, SqlEvent.Kind.QUERY, true)));
			assertEquals(1, table.getSelectedRow());
			assertEquals(2, model.get(table.convertRowIndexToModel(table.getSelectedRow())).id());

			ObserverApp.appendEvents(model, table,
					List.of(event(4, SqlEvent.Kind.QUERY, true), event(5, SqlEvent.Kind.QUERY, true)));
			assertEquals(-1, table.getSelectedRow());
		});
	}

	@Test
	void clearsSelectionWhenResultSetFailureHidesTheSelectedStatement() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			var model = new EventTableModel(3);
			model.add(event(1, SqlEvent.Kind.QUERY, true));
			var table = new JTable(model);
			var sorter = new TableRowSorter<>(model);
			table.setRowSorter(sorter);
			sorter.setRowFilter(RowFilter.regexFilter("OK", 14));
			table.setRowSelectionInterval(0, 0);
			var failedResultSet = new SqlEvent(2, 1, 0, Instant.now(), "worker", "connection", SqlEvent.Kind.RESULT_SET,
					"", "", Map.of(), Map.of(), 0, 1, 2, 3, false, "fetch failed", 0, true, 2, "", "", "", "", "");

			ObserverApp.appendEvents(model, table, List.of(failedResultSet));

			assertEquals(-1, table.getSelectedRow());
			assertEquals(0, table.getRowCount());
			assertEquals(1, model.failedCount());
		});
	}

	@Test
	void combinesFailureSqlTextAndDurationFilters() {
		var failure = event(5, SqlEvent.Kind.QUERY, false);
		assertTrue(ObserverApp.matchesFilter(failure, "failed", 5, true, true));
		assertFalse(ObserverApp.matchesFilter(failure, "failed", 6, true, true));
		assertFalse(ObserverApp.matchesFilter(failure, "missing", 0, true, true));
		assertFalse(ObserverApp.matchesFilter(event(1, SqlEvent.Kind.QUERY, true), "", 0, true, true));
		assertFalse(ObserverApp.matchesFilter(event(1, SqlEvent.Kind.COMMIT, false), "", 0, true, true));
		assertTrue(ObserverApp.matchesFilter(event(1, SqlEvent.Kind.COMMIT, false), "", 0, false, true));
	}

	private static SqlEvent event(long id, SqlEvent.Kind kind, boolean success) {
		return new SqlEvent(id, 0, 0, Instant.ofEpochSecond(id), "worker", "connection", kind, "select 1", "select 1",
				Map.of(), Map.of(), id * 1_000_000, 0, 0, -1, success, success ? "" : "failed", 0, true, 2, "", "", "",
				"", "");
	}

}
