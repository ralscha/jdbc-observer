package ch.rasc.jdbcobserver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ControlCodecTest {

	@Test
	void roundTripsThrottleCommandsIncludingClear() throws IOException {
		var bytes = new ByteArrayOutputStream();
		try (var output = new DataOutputStream(bytes)) {
			ControlCodec.writeHeader(output);
			ControlCodec.writeThrottleMillis(output, 275);
			ControlCodec.writeThrottleMillis(output, 0);
		}

		try (var input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			ControlCodec.readHeader(input);
			assertEquals(275, ControlCodec.readThrottleMillis(input));
			assertEquals(0, ControlCodec.readThrottleMillis(input));
		}
	}

	@Test
	void roundTripsTypedThrottleAndExplainCommands() throws IOException {
		var bytes = new ByteArrayOutputStream();
		try (var output = new DataOutputStream(bytes)) {
			ControlCodec.writeHeader(output);
			ControlCodec.writeThrottleMillis(output, 125);
			ControlCodec.writeExplainRequest(output, 42, "c7", "select * from book");
		}

		try (var input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			ControlCodec.readHeader(input);
			assertEquals(new ControlCodec.Throttle(125), ControlCodec.readCommand(input));
			assertEquals(new ControlCodec.ExplainRequest(42, "c7", "select * from book"),
					ControlCodec.readCommand(input));
		}
	}

	@Test
	void rejectsInvalidThrottleValuesAndCommands() throws IOException {
		var bytes = new ByteArrayOutputStream();
		try (var output = new DataOutputStream(bytes)) {
			output.writeByte(99);
			output.writeInt(1);
		}

		assertThrows(IllegalArgumentException.class,
				() -> ControlCodec.writeThrottleMillis(new DataOutputStream(new ByteArrayOutputStream()), -1));
		try (var input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			assertThrows(IOException.class, () -> ControlCodec.readThrottleMillis(input));
		}
	}

}
