package ch.rasc.jdbcobserver.core;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.StreamCorruptedException;
import java.util.Objects;

public final class ControlCodec {

	public static final int MAX_THROTTLE_MILLIS = 3_600_000;

	private static final int MAGIC = 0x4a4f4243;

	private static final int VERSION = 2;

	private static final int THROTTLE = 1;

	private static final int EXPLAIN = 2;

	private ControlCodec() {
	}

	public static void writeHeader(DataOutput out) throws IOException {
		out.writeInt(MAGIC);
		out.writeInt(VERSION);
	}

	public static void readHeader(DataInput in) throws IOException {
		if (in.readInt() != MAGIC || in.readInt() != VERSION) {
			throw new StreamCorruptedException("Unsupported JDBC Observer control protocol");
		}
	}

	public static void writeThrottleMillis(DataOutput out, int milliseconds) throws IOException {
		validateThrottleMillis(milliseconds);
		out.writeByte(THROTTLE);
		out.writeInt(milliseconds);
	}

	public static void writeExplainRequest(DataOutput out, long requestId, String connectionId, String sql)
			throws IOException {
		var request = new ExplainRequest(requestId, connectionId, sql);
		out.writeByte(EXPLAIN);
		out.writeLong(request.requestId());
		EventCodec.writeString(out, request.connectionId());
		EventCodec.writeString(out, request.sql());
	}

	public static Command readCommand(DataInput in) throws IOException {
		int command = in.readUnsignedByte();
		try {
			return switch (command) {
				case THROTTLE -> new Throttle(in.readInt());
				case EXPLAIN -> new ExplainRequest(in.readLong(), EventCodec.readString(in), EventCodec.readString(in));
				default -> throw new StreamCorruptedException("Unknown JDBC Observer control command: " + command);
			};
		}
		catch (IllegalArgumentException ex) {
			var corrupted = new StreamCorruptedException(ex.getMessage());
			corrupted.initCause(ex);
			throw corrupted;
		}
	}

	public static int readThrottleMillis(DataInput in) throws IOException {
		var command = readCommand(in);
		if (command instanceof Throttle throttle) {
			return throttle.milliseconds();
		}
		throw new StreamCorruptedException("Expected a throttle command");
	}

	private static void validateThrottleMillis(int milliseconds) {
		if (milliseconds < 0 || milliseconds > MAX_THROTTLE_MILLIS) {
			throw new IllegalArgumentException(
					"Throttle must be between 0 and " + MAX_THROTTLE_MILLIS + " milliseconds");
		}
	}

	public sealed interface Command permits Throttle, ExplainRequest {

	}

	public record Throttle(int milliseconds) implements Command {

		public Throttle {
			validateThrottleMillis(milliseconds);
		}

	}

	public record ExplainRequest(long requestId, String connectionId, String sql) implements Command {

		public ExplainRequest {
			if (requestId <= 0) {
				throw new IllegalArgumentException("EXPLAIN request ID must be positive");
			}
			connectionId = Objects.requireNonNull(connectionId, "connectionId");
			sql = Objects.requireNonNull(sql, "sql");
			if (connectionId.isBlank()) {
				throw new IllegalArgumentException("EXPLAIN connection ID must not be blank");
			}
			if (sql.isBlank()) {
				throw new IllegalArgumentException("EXPLAIN SQL must not be blank");
			}
		}

	}

}
