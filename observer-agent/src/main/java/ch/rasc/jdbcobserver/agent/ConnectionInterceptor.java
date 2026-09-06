package ch.rasc.jdbcobserver.agent;

import java.sql.Connection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.StringJoiner;
import java.util.regex.Pattern;

public final class ConnectionInterceptor {

	private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

	private static final ThreadLocal<Integer> SUPPRESSION = ThreadLocal.withInitial(() -> 0);

	private static final Pattern URL_PROPERTY = Pattern.compile("(?<![\\w.%+-])([\\w.%+-]+)=");

	private ConnectionInterceptor() {
	}

	public static Invocation enter(Object source, boolean reusableSource) {
		int depth = DEPTH.get();
		DEPTH.set(depth + 1);
		return new Invocation(System.nanoTime(), depth == 0 && SUPPRESSION.get() == 0, source, reusableSource);
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
					redactUrl(url), sanitizeProperties(properties),
					invocation.reusableSource() ? invocation.source() : null);
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

	static <T> T withoutObservation(CheckedSupplier<T> supplier) throws Exception {
		int suppression = SUPPRESSION.get();
		SUPPRESSION.set(suppression + 1);
		try {
			return supplier.get();
		}
		finally {
			if (suppression == 0) {
				SUPPRESSION.remove();
			}
			else {
				SUPPRESSION.set(suppression);
			}
		}
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
		var matcher = URL_PROPERTY.matcher(url);
		var result = new StringBuilder();
		int copied = 0;
		while (matcher.find()) {
			String key = matcher.group(1);
			try {
				key = URLDecoder.decode(key, StandardCharsets.UTF_8);
			}
			catch (IllegalArgumentException ignored) {
			}
			int end = propertyValueEnd(url, matcher.end());
			if (isSecret(key)) {
				result.append(url, copied, matcher.end()).append("***");
			}
			else {
				result.append(url, copied, end);
			}
			copied = end;
			matcher.region(end, url.length());
		}
		return result.append(url, copied, url.length()).toString();
	}

	private static int propertyValueEnd(String url, int index) {
		boolean braced = index < url.length() && url.charAt(index) == '{';
		while (index < url.length()) {
			char current = url.charAt(index);
			if (braced && current == '}') {
				if (index + 1 < url.length() && url.charAt(index + 1) == '}') {
					index += 2;
					continue;
				}
				return index + 1;
			}
			if (!braced && (current == ';' || current == '&' || current == ')')) {
				return index;
			}
			index++;
		}
		return index;
	}

	private static boolean isSecret(String name) {
		String normalized = name.toLowerCase(Locale.ROOT);
		return normalized.contains("password") || normalized.contains("passwd") || normalized.equals("pwd")
				|| normalized.contains("secret") || normalized.contains("token") || normalized.contains("credential");
	}

	public static final class Invocation {

		private final long started;

		private final boolean outermost;

		private final Object source;

		private final boolean reusableSource;

		private boolean active = true;

		private Invocation(long started, boolean outermost, Object source, boolean reusableSource) {
			this.started = started;
			this.outermost = outermost;
			this.source = source;
			this.reusableSource = reusableSource;
		}

		private long started() {
			return this.started;
		}

		private boolean outermost() {
			return this.outermost;
		}

		private Object source() {
			return this.source;
		}

		private boolean reusableSource() {
			return this.reusableSource;
		}

		private boolean leave() {
			if (!this.active) {
				return false;
			}
			this.active = false;
			return true;
		}

	}

	@FunctionalInterface
	interface CheckedSupplier<T> {

		T get() throws Exception;

	}

}
