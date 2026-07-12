package ch.rasc.jdbcobserver.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.rasc.jdbcobserver.core.SqlEvent;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvExportTest {

	@TempDir
	java.nio.file.Path temporaryDirectory;

	@Test
	void exportsCorrelationConnectionMetadataAndSafeSpreadsheetText() throws Exception {
		var target = this.temporaryDirectory.resolve("events.csv");
		var event = new SqlEvent(7, 6, 5, Instant.parse("2026-01-01T00:00:00.123456789Z"), "thread", "c1",
				SqlEvent.Kind.QUERY, "=danger\n\"quoted\"", "select 1", Map.of(1, "1"), Map.of(1, "setInt"), 1, 2, 3, 4,
				true, "", 5, false, 8, "jdbc:test", "{user=demo}", "select ?", "site", "stack");

		ObserverApp.writeCsv(target, List.of(event));

		String csv = Files.readString(target);
		assertTrue(csv.startsWith("id,parent_id,transaction_id"));
		assertTrue(csv.contains("connection_url,connection_properties"));
		assertTrue(csv.contains("7,6,5"));
		assertTrue(csv.contains("\"'=danger\n\"\"quoted\"\"\""));
		assertTrue(csv.contains("\"jdbc:test\",\"{user=demo}\""));
	}

}
