package ch.rasc.jdbcobserver.agent;

import ch.rasc.jdbcobserver.core.ControlCodec;
import ch.rasc.jdbcobserver.core.EventCodec;
import ch.rasc.jdbcobserver.core.SqlEvent;
import ch.rasc.jdbcobserver.core.TransportCodec;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.function.Consumer;

final class AgentRuntime {

	private static final int EVENT_CAPACITY = 10_000;

	private static final int MAX_QUEUED_CHARACTERS = 16_000_000;

	private static final int RESPONSE_CAPACITY = 64;

	private static final BlockingDeque<QueuedEvent> EVENTS = new LinkedBlockingDeque<>(EVENT_CAPACITY);

	private static long queuedCharacters;

	private static final AtomicLong IDS = new AtomicLong();

	private static final AtomicLong DROPPED = new AtomicLong();

	private static final AtomicBoolean STARTED = new AtomicBoolean();

	private static final AtomicReference<ServerClient> SERVER_CLIENT = new AtomicReference<>();

	private static final CallThrottler THROTTLER = new CallThrottler();

	private static final Semaphore EXPLAIN_SLOTS = new Semaphore(2);

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

	static void configureThrottle(int milliseconds) {
		THROTTLER.configure(milliseconds);
	}

	static void throttleSqlExecution() {
		THROTTLER.throttle();
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
				exchangeWithUi(socket);
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

	private static void exchangeWithUi(Socket socket) throws IOException, InterruptedException {
		var connected = new AtomicBoolean(true);
		var responses = new ArrayBlockingQueue<TransportCodec.ExplainResponse>(RESPONSE_CAPACITY);
		Thread reader = null;
		try (var input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
				var output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
			reader = Thread.ofPlatform()
				.daemon()
				.name("jdbc-observer-control-reader")
				.start(() -> readClientControls(socket, input, connected, responses));
			writeEvents(output, connected, responses);
			throw new EOFException("UI disconnected");
		}
		finally {
			connected.set(false);
			close(socket);
			if (reader != null) {
				reader.interrupt();
			}
			configureThrottle(0);
		}
	}

	private static void readClientControls(Socket socket, DataInputStream input, AtomicBoolean connected,
			BlockingQueue<TransportCodec.ExplainResponse> responses) {
		try {
			readControls(input, AgentRuntime::configureThrottle,
					request -> executeExplain(request, responses, connected));
		}
		catch (IOException | RuntimeException ignored) {
		}
		finally {
			connected.set(false);
			close(socket);
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

	private static void writeEvents(DataOutputStream out, AtomicBoolean connected,
			BlockingQueue<TransportCodec.ExplainResponse> responses) throws IOException, InterruptedException {
		EventCodec.writeHeader(out, SESSION_ID);
		out.flush();
		while (connected.get() && !Thread.currentThread().isInterrupted()) {
			var response = responses.poll();
			if (response != null) {
				TransportCodec.writeExplainResponse(out, response);
				out.flush();
				continue;
			}
			var queued = EVENTS.pollFirst(100, TimeUnit.MILLISECONDS);
			if (queued == null) {
				continue;
			}
			synchronized (EVENTS) {
				queuedCharacters -= queued.characters();
			}
			try {
				TransportCodec.writeEvent(out, queued.event());
				out.flush();
			}
			catch (IOException | RuntimeException ex) {
				requeue(queued);
				throw ex;
			}
		}
	}

	static void readControls(DataInputStream input, IntConsumer throttleConsumer,
			Consumer<ControlCodec.ExplainRequest> explainConsumer) throws IOException {
		ControlCodec.readHeader(input);
		while (!Thread.currentThread().isInterrupted()) {
			switch (ControlCodec.readCommand(input)) {
				case ControlCodec.Throttle throttle -> throttleConsumer.accept(throttle.milliseconds());
				case ControlCodec.ExplainRequest request -> explainConsumer.accept(request);
			}
		}
	}

	private static void executeExplain(ControlCodec.ExplainRequest request,
			BlockingQueue<TransportCodec.ExplainResponse> responses, AtomicBoolean connected) {
		if (!EXPLAIN_SLOTS.tryAcquire()) {
			responses.offer(new TransportCodec.ExplainResponse(request.requestId(), false, "",
					"The agent is already processing the maximum number of EXPLAIN requests"));
			return;
		}
		Thread.ofVirtual().name("jdbc-observer-explain").start(() -> {
			try {
				if (connected.get()) {
					var response = ExplainService.explain(request);
					if (connected.get()) {
						responses.offer(response);
					}
				}
			}
			finally {
				EXPLAIN_SLOTS.release();
			}
		});
	}

	private static void close(Socket socket) {
		try {
			socket.close();
		}
		catch (IOException ignored) {
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

		private final AtomicBoolean connected = new AtomicBoolean(true);

		private final BlockingQueue<TransportCodec.ExplainResponse> responses = new ArrayBlockingQueue<>(
				RESPONSE_CAPACITY);

		private volatile Thread writer;

		private volatile Thread reader;

		private ServerClient(Socket socket) {
			this.socket = socket;
		}

		private void start() {
			this.reader = Thread.ofPlatform().daemon().name("jdbc-observer-control-reader").start(this::read);
			this.writer = Thread.ofPlatform().daemon().name("jdbc-observer-writer").start(this::write);
		}

		private void read() {
			try (var input = new DataInputStream(new BufferedInputStream(this.socket.getInputStream()))) {
				readControls(input, milliseconds -> {
					if (SERVER_CLIENT.get() == this) {
						configureThrottle(milliseconds);
					}
				}, request -> {
					if (SERVER_CLIENT.get() == this) {
						executeExplain(request, this.responses, this.connected);
					}
				});
			}
			catch (IOException | RuntimeException ex) {
				if (!(ex instanceof EOFException) && this.connected.get() && SERVER_CLIENT.get() == this) {
					System.err.println("[jdbc-observer] control channel failed: " + ex.getMessage());
				}
			}
			finally {
				close();
			}
		}

		private void write() {
			try (var out = new DataOutputStream(new BufferedOutputStream(this.socket.getOutputStream()))) {
				writeEvents(out, this.connected, this.responses);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			catch (IOException | RuntimeException ex) {
				if (!(ex instanceof EOFException) && this.connected.get() && SERVER_CLIENT.get() == this) {
					System.err.println("[jdbc-observer] UI disconnected: " + ex.getMessage());
				}
			}
			finally {
				if (SERVER_CLIENT.compareAndSet(this, null)) {
					configureThrottle(0);
				}
				close();
			}
		}

		private void close() {
			this.connected.set(false);
			AgentRuntime.close(this.socket);
			interrupt(this.writer);
			interrupt(this.reader);
		}

		private static void interrupt(Thread thread) {
			if (thread != null && thread != Thread.currentThread()) {
				thread.interrupt();
			}
		}

	}

	private record QueuedEvent(SqlEvent event, int characters) {
	}

}
