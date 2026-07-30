package ch.rasc.jdbcobserver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventCodecTest {

	@Test
	void roundTripsLargeUnicodeEventsWithoutLosingPrecisionOrOrder() throws IOException {
		var parameters = new LinkedHashMap<Integer, String>();
		parameters.put(2, "Grüezi 👋");
		parameters.put(1, "x".repeat(70_000));
		var event = event(Instant.ofEpochSecond(1_700_000_000L, 123_456_789), parameters);

		var bytes = new ByteArrayOutputStream();
		var sessionId = UUID.randomUUID();
		try (var output = new DataOutputStream(bytes)) {
			EventCodec.writeHeader(output, sessionId);
			EventCodec.write(output, event);
		}

		try (var input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			assertEquals(sessionId, EventCodec.readHeader(input));
			var decoded = EventCodec.read(input);
			assertEquals(event, decoded);
			assertEquals(java.util.List.of(2, 1), decoded.parameters().keySet().stream().toList());
		}
	}

	@Test
	void rejectsUnknownEventKindsAsCorruptInput() throws IOException {
		var bytes = new ByteArrayOutputStream();
		try (var output = new DataOutputStream(bytes)) {
			output.writeLong(1);
			output.writeLong(0);
			output.writeLong(0);
			output.writeLong(0);
			output.writeInt(0);
			output.writeInt(0);
			output.writeInt(0);
			output.writeByte(255);
		}

		try (var input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			assertThrows(IOException.class, () -> EventCodec.read(input));
		}
	}

	@Test
	void rejectsUnsupportedProtocolVersions() throws IOException {
		var bytes = new ByteArrayOutputStream();
		try (var output = new DataOutputStream(bytes)) {
			output.writeInt(EventCodec.MAGIC);
			output.writeInt(2);
			output.writeLong(0);
			output.writeLong(0);
		}

		try (var input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			assertThrows(IOException.class, () -> EventCodec.readHeader(input));
		}
	}

	@Test
	void eventKindWireCodesAreStableAndUnique() {
		assertEquals(java.util.stream.IntStream.rangeClosed(1, 15).boxed().toList(),
				java.util.Arrays.stream(SqlEvent.Kind.values()).map(SqlEvent.Kind::wireCode).sorted().toList());
	}

	private static SqlEvent event(Instant timestamp, Map<Integer, String> parameters) {
		return new SqlEvent(7, 2, 3, timestamp, "worker", "c1", SqlEvent.Kind.QUERY, "select ?", "select 1", parameters,
				Map.of(1, "setString"), 11, 12, 13, 1, true, "", 5, false, 8, "jdbc:test", "user=test", "select ?",
				"Example.call(Example.java:1)", "frame");
	}

}
