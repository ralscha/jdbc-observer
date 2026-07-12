package ch.rasc.jdbcobserver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SqlEventTest {

	@Test
	void snapshotsParameterMapsInTheirOriginalOrder() {
		var parameters = new LinkedHashMap<Integer, String>();
		parameters.put(3, "third");
		parameters.put(1, "first");
		var event = event(Instant.EPOCH, SqlEvent.Kind.QUERY, parameters);
		parameters.clear();

		assertEquals(java.util.List.of(3, 1), event.parameters().keySet().stream().toList());
		assertThrows(UnsupportedOperationException.class, () -> event.parameters().put(2, "second"));
	}

	@Test
	void enforcesRequiredValuesAndNormalizesOptionalText() {
		assertThrows(NullPointerException.class, () -> event(null, SqlEvent.Kind.QUERY, Map.of()));
		assertThrows(NullPointerException.class, () -> event(Instant.EPOCH, null, Map.of()));
		assertThrows(IllegalArgumentException.class, () -> event(Instant.EPOCH, SqlEvent.Kind.QUERY, Map.of(0, "x")));
		var event = new SqlEvent(1, 0, 0, Instant.EPOCH, null, null, SqlEvent.Kind.CONNECTION, null, null, Map.of(),
				Map.of(), 0, 0, 0, -1, true, null, 0, true, 0, null, null, null, null, null);
		assertEquals("", event.thread());
		assertEquals("", event.connection());
	}

	@Test
	void identifiesOnlySqlStatementKinds() {
		var statementKinds = Set.of(SqlEvent.Kind.QUERY, SqlEvent.Kind.UPDATE, SqlEvent.Kind.EXECUTE,
				SqlEvent.Kind.BATCH);
		for (var kind : SqlEvent.Kind.values()) {
			assertEquals(statementKinds.contains(kind), kind.isSqlStatement(), kind.name());
		}
	}

	private static SqlEvent event(Instant timestamp, SqlEvent.Kind kind, Map<Integer, String> parameters) {
		return new SqlEvent(1, 0, 0, timestamp, "thread", "connection", kind, "", "", parameters, Map.of(), 0, 0, 0, -1,
				true, "", 0, true, 0, "", "", "", "", "");
	}

}
