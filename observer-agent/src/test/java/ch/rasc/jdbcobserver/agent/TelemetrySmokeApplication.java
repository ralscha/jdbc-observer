package ch.rasc.jdbcobserver.agent;

import ch.rasc.jdbcobserver.core.ControlCodec;
import ch.rasc.jdbcobserver.core.EventCodec;
import ch.rasc.jdbcobserver.core.SqlEvent;
import ch.rasc.jdbcobserver.core.TransportCodec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.sql.BatchUpdateException;
import java.sql.DriverManager;
import java.util.ArrayList;

public final class TelemetrySmokeApplication {

	private TelemetrySmokeApplication() {
	}

	public static void main(String[] args) throws Exception {
		try (var socket = new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(args[0]));
				var input = new DataInputStream(socket.getInputStream());
				var output = new DataOutputStream(socket.getOutputStream())) {
			socket.setSoTimeout(5_000);
			EventCodec.readHeader(input);
			ControlCodec.writeHeader(output);
			output.flush();
			try (var connection = DriverManager.getConnection("jdbc:h2:mem:telemetry");
					var statement = connection.createStatement()) {
				statement.execute("create table item(id int primary key)");
				statement.execute("insert into item values (1), (2)");
				statement.addBatch("insert into item values (3)");
				statement.executeBatch();
				statement.addBatch("insert into item values (4)");
				statement.executeLargeBatch();
				statement.addBatch("insert into item values (5)");
				statement.addBatch("insert into item values (1)");
				try {
					statement.executeBatch();
					throw new AssertionError("Duplicate key should fail the batch");
				}
				catch (BatchUpdateException expected) {
					// The first insert succeeded, and its count must survive the failure.
				}
				statement.executeBatch();
				try (var prepared = connection.prepareStatement("insert into item values (?)")) {
					prepared.setInt(1, 6);
					prepared.addBatch();
					prepared.executeBatch();
					prepared.setInt(1, 7);
					prepared.addBatch();
					prepared.executeLargeBatch();
				}
			}
			var events = new ArrayList<SqlEvent>();
			while (true) {
				if (TransportCodec.read(input) instanceof TransportCodec.EventMessage message) {
					events.add(message.event());
					if (message.event().kind() == SqlEvent.Kind.CONNECTION_CLOSE) {
						break;
					}
				}
			}
			var insert = events.stream()
				.filter(event -> event.sql().equals("insert into item values (1), (2)"))
				.findFirst()
				.orElseThrow();
			if (insert.kind() != SqlEvent.Kind.EXECUTE || insert.rows() != 2) {
				throw new AssertionError("Generic execute lost its update count: " + insert);
			}
			var batches = events.stream().filter(event -> event.kind() == SqlEvent.Kind.BATCH).toList();
			if (batches.size() != 6) {
				throw new AssertionError("Expected six batch events: " + batches);
			}
			String[] sql = { "insert into item values (3)", "insert into item values (4)",
					"insert into item values (5);\ninsert into item values (1)", "", "insert into item values (6)",
					"insert into item values (7)" };
			for (int index = 0; index < batches.size(); index++) {
				var batch = batches.get(index);
				if (!batch.sql().equals(sql[index]) || batch.success() != (index != 2)
						|| batch.rows() != (index == 3 ? 0 : 1)) {
					throw new AssertionError("Incorrect batch telemetry at " + index + ": " + batch);
				}
			}
			System.out.println("TELEMETRY_SMOKE_OK batch reuse, partial failures, empty batches, and execute counts");
		}
	}

}
