package ch.rasc.jdbcobserver.agent;

import ch.rasc.jdbcobserver.core.ControlCodec;
import ch.rasc.jdbcobserver.core.EventCodec;
import ch.rasc.jdbcobserver.core.SqlEvent;
import ch.rasc.jdbcobserver.core.TransportCodec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.sql.DriverManager;

public final class ReverseThrottleSmokeApplication {

	private ReverseThrottleSmokeApplication() {
	}

	public static void main(String[] args) throws Exception {
		try (var server = new ServerSocket()) {
			server.setReuseAddress(true);
			server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 4561));
			try (var socket = server.accept();
					var input = new DataInputStream(socket.getInputStream());
					var output = new DataOutputStream(socket.getOutputStream())) {
				socket.setSoTimeout(5_000);
				EventCodec.readHeader(input);
				ControlCodec.writeHeader(output);
				ControlCodec.writeThrottleMillis(output, 40);
				output.flush();
				Thread.sleep(100);

				try (var connection = DriverManager.getConnection("jdbc:h2:mem:reverse-throttle")) {
					try (var statement = connection.createStatement();
							var result = statement.executeQuery("select 1")) {
						result.next();
					}

					SqlEvent query;
					do {
						query = readEvent(input);
					}
					while (query.kind() != SqlEvent.Kind.QUERY);
					if (query.durationMillis() < 40) {
						throw new AssertionError("reverse-mode throttle was not applied: " + query.durationMillis());
					}
					ControlCodec.writeExplainRequest(output, 1, query.connection(), query.sql());
					output.flush();
					TransportCodec.ExplainResponse response = null;
					while (response == null) {
						var message = TransportCodec.read(input);
						if (message instanceof TransportCodec.ExplainResponse value) {
							response = value;
						}
					}
					if (!response.success()) {
						throw new AssertionError("reverse-mode EXPLAIN failed: " + response.error());
					}
				}
				ControlCodec.writeThrottleMillis(output, 0);
				output.flush();
				System.out.println("REVERSE_SMOKE_OK SQL throttling and EXPLAIN over the reverse control channel");
			}
		}
	}

	private static SqlEvent readEvent(DataInputStream input) throws Exception {
		while (true) {
			var message = TransportCodec.read(input);
			if (message instanceof TransportCodec.EventMessage event) {
				return event.event();
			}
		}
	}

}
