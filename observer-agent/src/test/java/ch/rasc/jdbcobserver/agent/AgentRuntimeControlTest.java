package ch.rasc.jdbcobserver.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.rasc.jdbcobserver.core.ControlCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class AgentRuntimeControlTest {

	@Test
	void appliesEveryThrottleCommandFromTheControlStream() throws IOException {
		var bytes = new ByteArrayOutputStream();
		try (var output = new DataOutputStream(bytes)) {
			ControlCodec.writeHeader(output);
			ControlCodec.writeThrottleMillis(output, 80);
			ControlCodec.writeThrottleMillis(output, 0);
		}
		var received = new ArrayList<Integer>();

		try (var input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			assertThrows(EOFException.class, () -> AgentRuntime.readControls(input, received::add, request -> {
			}));
		}
		assertEquals(java.util.List.of(80, 0), received);
	}

	@Test
	void dispatchesExplainCommandsFromTheControlStream() throws IOException {
		var bytes = new ByteArrayOutputStream();
		try (var output = new DataOutputStream(bytes)) {
			ControlCodec.writeHeader(output);
			ControlCodec.writeExplainRequest(output, 11, "c3", "select 1");
		}
		var received = new ArrayList<ControlCodec.ExplainRequest>();

		try (var input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			assertThrows(EOFException.class, () -> AgentRuntime.readControls(input, milliseconds -> {
			}, received::add));
		}
		assertEquals(java.util.List.of(new ControlCodec.ExplainRequest(11, "c3", "select 1")), received);
	}

}
