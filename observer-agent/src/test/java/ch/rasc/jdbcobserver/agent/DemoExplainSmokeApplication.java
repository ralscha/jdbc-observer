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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

public final class DemoExplainSmokeApplication {

	private DemoExplainSmokeApplication() {
	}

	public static void main(String[] args) throws Exception {
		try (var server = new ServerSocket()) {
			server.setReuseAddress(true);
			server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 4561));
			try (var socket = server.accept();
					var input = new DataInputStream(socket.getInputStream());
					var output = new DataOutputStream(socket.getOutputStream())) {
				socket.setSoTimeout(15_000);
				EventCodec.readHeader(input);
				ControlCodec.writeHeader(output);
				ControlCodec.writeThrottleMillis(output, 0);
				output.flush();

				var request = HttpRequest.newBuilder(java.net.URI.create("http://localhost:8080/demo/fixed"))
					.timeout(Duration.ofSeconds(10))
					.GET()
					.build();
				try (var client = HttpClient.newHttpClient()) {
					var response = client.send(request, HttpResponse.BodyHandlers.discarding());
					if (response.statusCode() != 200) {
						throw new AssertionError("Demo returned HTTP " + response.statusCode());
					}
				}

				SqlEvent query = null;
				boolean connectionClosed = false;
				while (query == null || !connectionClosed) {
					var message = TransportCodec.read(input);
					if (message instanceof TransportCodec.EventMessage eventMessage) {
						var event = eventMessage.event();
						if (query == null && event.kind() == SqlEvent.Kind.QUERY
								&& event.sql().toLowerCase(Locale.ROOT).contains(" from author")) {
							query = event;
						}
						if (query != null && event.connection().equals(query.connection())
								&& event.kind() == SqlEvent.Kind.CONNECTION_CLOSE) {
							connectionClosed = true;
						}
					}
				}

				ControlCodec.writeExplainRequest(output, 1, query.connection(), query.sql());
				output.flush();
				while (true) {
					var message = TransportCodec.read(input);
					if (message instanceof TransportCodec.EventMessage event) {
						throw new AssertionError("EXPLAIN replacement connection leaked telemetry: " + event.event());
					}
					if (message instanceof TransportCodec.ExplainResponse response) {
						if (!response.success()) {
							throw new AssertionError("Demo EXPLAIN failed: " + response.error());
						}
						if (!response.plan().toLowerCase(Locale.ROOT).contains("author")) {
							throw new AssertionError("Unexpected demo plan: " + response.plan());
						}
						System.out.println("DEMO_EXPLAIN_OK after the pooled connection was returned");
						return;
					}
				}
			}
		}
	}

}
