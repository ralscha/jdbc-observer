package ch.rasc.jdbcobserver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TransportCodecTest {

	@Test
	void roundTripsEventsAndExplainResponses() throws IOException {
		var event = new SqlEvent(1, 0, 0, Instant.EPOCH, "main", "c1", SqlEvent.Kind.QUERY, "select ?", "select 1",
				Map.of(1, "1"), Map.of(1, "setInt"), 10, 0, 0, 1, true, "", 0, true, 2, "jdbc:test", "", "select ?",
				"Example.run", "");
		var response = new TransportCodec.ExplainResponse(9, true, "TABLE SCAN", "");
		var bytes = new ByteArrayOutputStream();
		try (var output = new DataOutputStream(bytes)) {
			TransportCodec.writeEvent(output, event);
			TransportCodec.writeExplainResponse(output, response);
		}

		try (var input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			assertEquals(new TransportCodec.EventMessage(event), TransportCodec.read(input));
			assertEquals(response, TransportCodec.read(input));
		}
	}

	@Test
	void rejectsUnknownMessages() throws IOException {
		var bytes = new ByteArrayOutputStream();
		try (var output = new DataOutputStream(bytes)) {
			output.writeByte(99);
		}
		try (var input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			assertThrows(IOException.class, () -> TransportCodec.read(input));
		}
	}

}
