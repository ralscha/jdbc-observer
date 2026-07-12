package ch.rasc.jdbcobserver.agent;

import ch.rasc.jdbcobserver.core.EventCodec;
import ch.rasc.jdbcobserver.core.SqlEvent;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class AgentRuntime {

	private static final int EVENT_CAPACITY = 10_000;

	private static final int MAX_QUEUED_CHARACTERS = 16_000_000;

	private static final BlockingDeque<QueuedEvent> EVENTS = new LinkedBlockingDeque<>(EVENT_CAPACITY);

	private static long queuedCharacters;

	private static final AtomicLong IDS = new AtomicLong();

	private static final AtomicLong DROPPED = new AtomicLong();

	private static final AtomicBoolean STARTED = new AtomicBoolean();

	private static final AtomicReference<ServerClient> SERVER_CLIENT = new AtomicReference<>();

	private static final UUID SESSION_ID = UUID.randomUUID();

	private static volatile int port = 4561;

	private static volatile String host = "127.0.0.1";

	private static volatile boolean clientMode;

	private static volatile boolean enabled;

	private static volatile long stackTraceThresholdNanos = TimeUnit.MILLISECONDS.toNanos(100);

	private static volatile boolean fullStackTraces = true;

	private AgentRuntime() {
	}

	static void start(String args) {
		if (!STARTED.compareAndSet(false, true)) {
			return;
		}
		parse(args);
		if (clientMode) {
			enabled = true;
			Thread.ofPlatform().daemon().name("jdbc-observer-client").start(AgentRuntime::connect);
			return;
		}
		try {
			var server = new ServerSocket();
			server.setReuseAddress(true);
			server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
			enabled = true;
			Thread.ofPlatform().daemon().name("jdbc-observer-server").start(() -> serve(server));
		}
		catch (IOException ex) {
			System.err.println("[jdbc-observer] disabled: cannot bind loopback port " + port + ": " + ex.getMessage());
		}
	}

	static int port() {
		return port;
	}

	static boolean enabled() {
		return enabled;
	}

	static long nextId() {
		return IDS.incrementAndGet();
	}

	static boolean captureStackTrace(long durationNanos, boolean success) {
		return fullStackTraces && (!success || durationNanos >= stackTraceThresholdNanos);
	}

	static void publish(SqlEvent event) {
		if (!enabled) {
			return;
		}
		var queued = new QueuedEvent(event, estimatedCharacters(event));
		if (queued.characters() > MAX_QUEUED_CHARACTERS) {
			recordDropped();
			return;
		}
		synchronized (EVENTS) {
			while (EVENTS.remainingCapacity() == 0 || queuedCharacters + queued.characters() > MAX_QUEUED_CHARACTERS) {
				var removed = EVENTS.pollFirst();
				if (removed == null) {
					break;
				}
				queuedCharacters -= removed.characters();
				recordDropped();
			}
			EVENTS.offerLast(queued);
			queuedCharacters += queued.characters();
		}
	}

	private static void parse(String args) {
		if (args == null || args.isBlank()) {
			return;
		}
		for (var entry : args.split(",")) {
			var pair = entry.split("=", 2);
			if (pair.length != 2) {
				warnArgument(entry);
				continue;
			}
			String name = pair[0].trim();
			String value = pair[1].trim();
			try {
				switch (name) {
					case "port" -> {
						int configuredPort = Integer.parseInt(value);
						if (configuredPort < 1 || configuredPort > 65_535) {
							throw new IllegalArgumentException("port must be between 1 and 65535");
						}
						port = configuredPort;
					}
					case "host" -> {
						if (value.isBlank()) {
							throw new IllegalArgumentException("host must not be blank");
						}
						host = value;
					}
					case "mode" -> {
						if (!value.equalsIgnoreCase("server") && !value.equalsIgnoreCase("client")) {
							throw new IllegalArgumentException("mode must be server or client");
						}
						clientMode = value.equalsIgnoreCase("client");
					}
					case "stackTraceThresholdMs" -> {
						long milliseconds = Long.parseLong(value);
						if (milliseconds < 0) {
							throw new IllegalArgumentException("stackTraceThresholdMs must not be negative");
						}
						stackTraceThresholdNanos = TimeUnit.MILLISECONDS.toNanos(milliseconds);
					}
					case "stackTrace" -> {
						if (!value.equalsIgnoreCase("on") && !value.equalsIgnoreCase("off")) {
							throw new IllegalArgumentException("stackTrace must be on or off");
						}
						fullStackTraces = value.equalsIgnoreCase("on");
					}
					default -> warnArgument(entry);
				}
			}
			catch (IllegalArgumentException ex) {
				System.err.println("[jdbc-observer] ignoring agent argument '" + entry + "': " + ex.getMessage());
			}
		}
	}

	private static void warnArgument(String entry) {
		System.err.println("[jdbc-observer] ignoring unknown agent argument '" + entry + "'");
	}

	private static void connect() {
		while (!Thread.currentThread().isInterrupted()) {
			try (var socket = new Socket()) {
				socket.setKeepAlive(true);
				socket.connect(new InetSocketAddress(host, port), 2_000);
				try (var out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
					writeEvents(out);
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			catch (Exception ex) {
				try {
					Thread.sleep(1_500);
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
				}
			}
		}
	}

	private static void serve(ServerSocket server) {
		try (server) {
			while (!Thread.currentThread().isInterrupted()) {
				var socket = server.accept();
				socket.setKeepAlive(true);
				var client = new ServerClient(socket);
				var previous = SERVER_CLIENT.getAndSet(client);
				if (previous != null) {
					previous.close();
				}
				client.start();
			}
		}
		catch (Exception ex) {
			if (!server.isClosed()) {
				System.err.println("[jdbc-observer] server failed: " + ex);
			}
		}
	}

	private static void writeEvents(DataOutputStream out) throws IOException, InterruptedException {
		EventCodec.writeHeader(out, SESSION_ID);
		out.flush();
		while (!Thread.currentThread().isInterrupted()) {
			var queued = EVENTS.takeFirst();
			synchronized (EVENTS) {
				queuedCharacters -= queued.characters();
			}
			try {
				EventCodec.write(out, queued.event());
				out.flush();
			}
			catch (IOException | RuntimeException ex) {
				requeue(queued);
				throw ex;
			}
		}
	}

	private static void requeue(QueuedEvent event) {
		synchronized (EVENTS) {
			while (EVENTS.remainingCapacity() == 0 || queuedCharacters + event.characters() > MAX_QUEUED_CHARACTERS) {
				var removed = EVENTS.pollLast();
				if (removed == null) {
					break;
				}
				queuedCharacters -= removed.characters();
				recordDropped();
			}
			EVENTS.offerFirst(event);
			queuedCharacters += event.characters();
		}
	}

	private static int estimatedCharacters(SqlEvent event) {
		long result = event.thread().length() + event.connection().length() + event.rawSql().length()
				+ event.sql().length() + event.error().length() + event.connectionUrl().length()
				+ event.connectionProperties().length() + event.fingerprint().length() + event.callSite().length()
				+ event.stackTrace().length();
		for (var entry : event.parameters().entrySet()) {
			result += entry.getValue().length() + 8L;
		}
		for (var entry : event.parameterMethods().entrySet()) {
			result += entry.getValue().length() + 8L;
		}
		return (int) Math.min(Integer.MAX_VALUE, result);
	}

	private static void recordDropped() {
		long dropped = DROPPED.incrementAndGet();
		if (dropped == 1 || (dropped & (dropped - 1)) == 0) {
			System.err.println("[jdbc-observer] telemetry queue full; dropped " + dropped + " events");
		}
	}

	private static final class ServerClient {

		private final Socket socket;

		private volatile Thread writer;

		private ServerClient(Socket socket) {
			this.socket = socket;
		}

		private void start() {
			this.writer = Thread.ofPlatform().daemon().name("jdbc-observer-writer").start(this::write);
		}

		private void write() {
			try (this.socket; var out = new DataOutputStream(new BufferedOutputStream(this.socket.getOutputStream()))) {
				writeEvents(out);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			catch (Exception ex) {
				if (!(ex instanceof EOFException) && SERVER_CLIENT.get() == this) {
					System.err.println("[jdbc-observer] UI disconnected: " + ex.getMessage());
				}
			}
			finally {
				SERVER_CLIENT.compareAndSet(this, null);
			}
		}

		private void close() {
			try {
				this.socket.close();
			}
			catch (IOException ignored) {
			}
			var thread = this.writer;
			if (thread != null) {
				thread.interrupt();
			}
		}

	}

	private record QueuedEvent(SqlEvent event, int characters) {
	}

}
