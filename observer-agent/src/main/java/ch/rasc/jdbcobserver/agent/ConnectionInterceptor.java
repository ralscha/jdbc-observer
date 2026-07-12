package ch.rasc.jdbcobserver.agent;

import java.sql.Connection;
import java.util.Locale;
import java.util.Properties;
import java.util.StringJoiner;

public final class ConnectionInterceptor {

	private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

	private ConnectionInterceptor() {
	}

	public static Invocation enter() {
		int depth = DEPTH.get();
		DEPTH.set(depth + 1);
		return new Invocation(System.nanoTime(), depth == 0);
	}

	public static Connection exit(Connection connection, Invocation invocation) {
		return exit(connection, invocation, "", null);
	}

	public static Connection exit(Connection connection, Invocation invocation, String url, Properties properties) {
		leave(invocation);
		if (connection == null || !invocation.outermost()) {
			return connection;
		}
		if (!AgentRuntime.enabled()) {
			return connection;
		}
		try {
			return JdbcProxy.wrapConnection(connection, Math.max(0, System.nanoTime() - invocation.started()),
					redactUrl(url), sanitizeProperties(properties));
		}
		catch (Exception | LinkageError ex) {
			System.err.println("[jdbc-observer] connection observation failed; returning the original connection: "
					+ ex.getClass().getSimpleName());
			return connection;
		}
	}

	public static void exitException(Invocation invocation) {
		leave(invocation);
	}

	private static void leave(Invocation invocation) {
		if (invocation == null || !invocation.leave()) {
			return;
		}
		int depth = DEPTH.get() - 1;
		if (depth <= 0) {
			DEPTH.remove();
		}
		else {
			DEPTH.set(depth);
		}
	}

	private static String sanitizeProperties(Properties properties) {
		if (properties == null || properties.isEmpty()) {
			return "";
		}
		var result = new StringJoiner(", ", "{", "}");
		properties.stringPropertyNames()
			.stream()
			.sorted()
			.forEach(name -> result
				.add(name + "=" + (isSecret(name) ? "***" : String.valueOf(properties.getProperty(name)))));
		return result.toString();
	}

	static String redactUrl(String url) {
		if (url == null || url.isBlank()) {
			return "";
		}
		return url.replaceAll("(?i)(password|passwd|pwd|secret|token)=([^;&]*)", "$1=***");
	}

	private static boolean isSecret(String name) {
		String normalized = name.toLowerCase(Locale.ROOT);
		return normalized.contains("password") || normalized.contains("passwd") || normalized.equals("pwd")
				|| normalized.contains("secret") || normalized.contains("token") || normalized.contains("credential");
	}

	public static final class Invocation {

		private final long started;

		private final boolean outermost;

		private boolean active = true;

		private Invocation(long started, boolean outermost) {
			this.started = started;
			this.outermost = outermost;
		}

		private long started() {
			return this.started;
		}

		private boolean outermost() {
			return this.outermost;
		}

		private boolean leave() {
			if (!this.active) {
				return false;
			}
			this.active = false;
			return true;
		}

	}

}
