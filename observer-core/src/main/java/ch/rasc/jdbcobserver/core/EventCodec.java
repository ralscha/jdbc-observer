package ch.rasc.jdbcobserver.core;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.StreamCorruptedException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class EventCodec {

	public static final int MAGIC = 0x4a4f4253;

	public static final int VERSION = 1;

	private static final int MAX_STRING_BYTES = 16 * 1024 * 1024;

	private static final int MAX_MAP_ENTRIES = 100_000;

	private static final SqlEvent.Kind[] KINDS_BY_CODE = kindsByCode();

	private EventCodec() {
	}

	public static void writeHeader(DataOutput out) throws IOException {
		writeHeader(out, new UUID(0, 0));
	}

	public static void writeHeader(DataOutput out, UUID sessionId) throws IOException {
		out.writeInt(MAGIC);
		out.writeInt(VERSION);
		out.writeLong(sessionId.getMostSignificantBits());
		out.writeLong(sessionId.getLeastSignificantBits());
	}

	public static UUID readHeader(DataInput in) throws IOException {
		if (in.readInt() != MAGIC || in.readInt() != VERSION) {
			throw new StreamCorruptedException("Unsupported JDBC Observer protocol");
		}
		return new UUID(in.readLong(), in.readLong());
	}

	public static void write(DataOutput out, SqlEvent event) throws IOException {
		out.writeLong(event.id());
		out.writeLong(event.parentId());
		out.writeLong(event.transactionId());
		out.writeLong(event.timestamp().getEpochSecond());
		out.writeInt(event.timestamp().getNano());
		writeString(out, event.thread());
		writeString(out, event.connection());
		out.writeByte(event.kind().wireCode());
		writeString(out, event.rawSql());
		writeString(out, event.sql());
		writeMap(out, event.parameters());
		writeMap(out, event.parameterMethods());
		out.writeLong(event.durationNanos());
		out.writeLong(event.fetchNanos());
		out.writeLong(event.resultSetUseNanos());
		out.writeLong(event.rows());
		out.writeBoolean(event.success());
		writeString(out, event.error());
		out.writeInt(event.queryTimeout());
		out.writeBoolean(event.autoCommit());
		out.writeInt(event.transactionIsolation());
		writeString(out, event.connectionUrl());
		writeString(out, event.connectionProperties());
		writeString(out, event.fingerprint());
		writeString(out, event.callSite());
		writeString(out, event.stackTrace());
	}

	public static SqlEvent read(DataInput in) throws IOException {
		long id = in.readLong();
		long parentId = in.readLong();
		long transactionId = in.readLong();
		Instant timestamp = readInstant(in);
		String thread = readString(in);
		String connection = readString(in);
		SqlEvent.Kind kind = readKind(in);
		return new SqlEvent(id, parentId, transactionId, timestamp, thread, connection, kind, readString(in),
				readString(in), readMap(in), readMap(in), in.readLong(), in.readLong(), in.readLong(), in.readLong(),
				in.readBoolean(), readString(in), in.readInt(), in.readBoolean(), in.readInt(), readString(in),
				readString(in), readString(in), readString(in), readString(in));
	}

	private static void writeMap(DataOutput out, Map<Integer, String> values) throws IOException {
		if (values.size() > MAX_MAP_ENTRIES) {
			throw new IOException("Too many JDBC parameter values: " + values.size());
		}
		out.writeInt(values.size());
		for (var entry : values.entrySet()) {
			out.writeInt(entry.getKey());
			writeString(out, entry.getValue());
		}
	}

	private static Map<Integer, String> readMap(DataInput in) throws IOException {
		var result = new LinkedHashMap<Integer, String>();
		int size = in.readInt();
		if (size < 0 || size > MAX_MAP_ENTRIES) {
			throw corrupted("Invalid JDBC parameter count: " + size, null);
		}
		for (int i = 0; i < size; i++) {
			int index = in.readInt();
			if (index < 1 || result.putIfAbsent(index, readString(in)) != null) {
				throw corrupted("Invalid or duplicate JDBC parameter index: " + index, null);
			}
		}
		return result;
	}

	static void writeString(DataOutput out, String value) throws IOException {
		if (value.length() > MAX_STRING_BYTES) {
			throw new IOException("JDBC Observer text field exceeds " + MAX_STRING_BYTES + " bytes");
		}
		byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
		if (encoded.length > MAX_STRING_BYTES) {
			throw new IOException("JDBC Observer text field exceeds " + MAX_STRING_BYTES + " bytes");
		}
		out.writeInt(encoded.length);
		out.write(encoded);
	}

	static String readString(DataInput in) throws IOException {
		int length = in.readInt();
		if (length < 0 || length > MAX_STRING_BYTES) {
			throw corrupted("Invalid JDBC Observer text length: " + length, null);
		}
		byte[] encoded = new byte[length];
		in.readFully(encoded);
		try {
			return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(encoded))
				.toString();
		}
		catch (CharacterCodingException ex) {
			throw corrupted("Invalid UTF-8 in JDBC Observer stream", ex);
		}
	}

	private static Instant readInstant(DataInput in) throws IOException {
		long seconds = in.readLong();
		int nanos = in.readInt();
		if (nanos < 0 || nanos > 999_999_999) {
			throw corrupted("Invalid JDBC Observer timestamp nanoseconds: " + nanos, null);
		}
		try {
			return Instant.ofEpochSecond(seconds, nanos);
		}
		catch (DateTimeException ex) {
			throw corrupted("Invalid JDBC Observer timestamp", ex);
		}
	}

	private static SqlEvent.Kind readKind(DataInput in) throws IOException {
		int code = in.readUnsignedByte();
		var kind = KINDS_BY_CODE[code];
		if (kind == null) {
			throw corrupted("Unknown JDBC Observer event kind: " + code, null);
		}
		return kind;
	}

	private static SqlEvent.Kind[] kindsByCode() {
		var result = new SqlEvent.Kind[256];
		for (var kind : SqlEvent.Kind.values()) {
			if (kind.wireCode() < 0 || kind.wireCode() >= result.length) {
				throw new ExceptionInInitializerError("Invalid JDBC Observer event kind code " + kind.wireCode());
			}
			if (result[kind.wireCode()] != null) {
				throw new ExceptionInInitializerError("Duplicate JDBC Observer event kind code " + kind.wireCode());
			}
			result[kind.wireCode()] = kind;
		}
		return result;
	}

	private static StreamCorruptedException corrupted(String message, Exception cause) {
		var exception = new StreamCorruptedException(message);
		if (cause != null) {
			exception.initCause(cause);
		}
		return exception;
	}

}
