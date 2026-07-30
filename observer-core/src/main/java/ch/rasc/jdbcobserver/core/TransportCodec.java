package ch.rasc.jdbcobserver.core;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.StreamCorruptedException;
import java.util.Objects;

public final class TransportCodec {

	private static final int EVENT = 1;

	private static final int EXPLAIN_RESPONSE = 2;

	private TransportCodec() {
	}

	public static void writeEvent(DataOutput out, SqlEvent event) throws IOException {
		out.writeByte(EVENT);
		EventCodec.write(out, event);
	}

	public static void writeExplainResponse(DataOutput out, ExplainResponse response) throws IOException {
		out.writeByte(EXPLAIN_RESPONSE);
		out.writeLong(response.requestId());
		out.writeBoolean(response.success());
		EventCodec.writeString(out, response.plan());
		EventCodec.writeString(out, response.error());
	}

	public static Message read(DataInput in) throws IOException {
		int type = in.readUnsignedByte();
		try {
			return switch (type) {
				case EVENT -> new EventMessage(EventCodec.read(in));
				case EXPLAIN_RESPONSE -> new ExplainResponse(in.readLong(), in.readBoolean(), EventCodec.readString(in),
						EventCodec.readString(in));
				default -> throw new StreamCorruptedException("Unknown JDBC Observer message: " + type);
			};
		}
		catch (IllegalArgumentException ex) {
			var corrupted = new StreamCorruptedException(ex.getMessage());
			corrupted.initCause(ex);
			throw corrupted;
		}
	}

	public sealed interface Message permits EventMessage, ExplainResponse {

	}

	public record EventMessage(SqlEvent event) implements Message {

		public EventMessage {
			Objects.requireNonNull(event, "event");
		}

	}

	public record ExplainResponse(long requestId, boolean success, String plan, String error) implements Message {

		public ExplainResponse {
			if (requestId <= 0) {
				throw new IllegalArgumentException("EXPLAIN request ID must be positive");
			}
			plan = Objects.requireNonNull(plan, "plan");
			error = Objects.requireNonNull(error, "error");
		}

	}

}
