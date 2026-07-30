package ch.rasc.jdbcobserver.agent;

import ch.rasc.jdbcobserver.core.SqlEvent;
import ch.rasc.jdbcobserver.core.SqlFingerprint;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

public final class JdbcProxy implements InvocationHandler {

	private static final int MAX_TEXT_LENGTH = 1_000_000;

	private static final int MAX_BATCH_ENTRIES = 10_000;

	private static final StackWalker STACK_WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

	private static final AtomicLong CONNECTION_IDS = new AtomicLong();

	private static final AtomicLong OBSERVATION_FAILURES = new AtomicLong();

	private final Object target;

	private final ConnectionState connection;

	private final String rawSql;

	private final long parentId;

	private final Object observedParent;

	private final NavigableMap<Integer, String> parameters = new TreeMap<>();

	private final NavigableMap<Integer, String> parameterMethods = new TreeMap<>();

	private final List<String> batch = new ArrayList<>();

	private final Map<ResultSet, JdbcProxy> resultSets = new IdentityHashMap<>();

	private long resultSetOpened = System.nanoTime();

	private long fetchNanos;

	private long resultRows;

	private boolean resultSetReported;

	private long lastExecutionId;

	private Object observedProxy;

	private JdbcProxy(Object target, ConnectionState connection, String rawSql, long parentId, Object observedParent) {
		this.target = target;
		this.connection = connection;
		this.rawSql = limit(rawSql);
		this.parentId = parentId;
		this.observedParent = observedParent;
	}

	public static Connection wrapConnection(Connection value, long creationNanos, String url, String properties) {
		return wrapConnection(value, creationNanos, url, properties, null);
	}

	static Connection wrapConnection(Connection value, long creationNanos, String url, String properties,
			Object source) {
		if (value == null || isObserved(value)) {
			return value;
		}
		try {
			deactivateNestedObservation(value);
			var state = ConnectionState.create(value, url, properties);
			var handler = new JdbcProxy(value, state, null, 0, null);
			var observed = proxy(value, Connection.class, handler);
			handler.observedProxy = observed;
			state.observedConnection = observed;
			if (AgentRuntime.enabled()) {
				ExplainService.register(state.id(), value, source);
			}
			publish(AgentRuntime.nextId(), 0, state, SqlEvent.Kind.CONNECTION, "", "", Map.of(), Map.of(),
					creationNanos, 0, 0, -1, true, "", 0);
			return observed;
		}
		catch (Exception | LinkageError ex) {
			reportObservationFailure("connection", ex);
			return value;
		}
	}

	public static Connection wrapConnection(Connection value) {
		return wrapConnection(value, 0, "", "");
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
		var name = method.getName();
		if (method.getDeclaringClass() == ObservedConnection.class && name.equals("jdbcObserverDeactivate")) {
			this.connection.deactivate();
			ExplainService.unregister(this.connection.id());
			return null;
		}
		if (name.equals("equals")) {
			return proxy == arguments[0];
		}
		if (name.equals("hashCode")) {
			return System.identityHashCode(proxy);
		}
		if (name.equals("toString")) {
			return "Observed[" + safeToString(this.target) + "]";
		}
		if (!this.connection.active()) {
			return call(method, arguments);
		}
		if (name.equals("unwrap") && arguments != null && arguments.length == 1 && arguments[0] instanceof Class<?> api
				&& api.isInstance(proxy)) {
			return proxy;
		}
		if (name.equals("isWrapperFor") && arguments != null && arguments.length == 1
				&& arguments[0] instanceof Class<?> api && api.isInstance(proxy)) {
			return true;
		}
		if (this.target instanceof Statement && name.equals("getConnection")) {
			return this.connection.observedConnection();
		}
		if (this.target instanceof ResultSet && name.equals("getStatement") && this.observedParent != null) {
			return this.observedParent;
		}
		if (this.target instanceof DatabaseMetaData && name.equals("getConnection")) {
			return this.connection.observedConnection();
		}
		if (this.target instanceof Connection) {
			return invokeConnection(method, arguments, name);
		}
		if (this.target instanceof ResultSet) {
			return invokeResultSet(method, arguments, name);
		}
		if (this.target instanceof PreparedStatement && isInputParameterSetter(name, arguments)) {
			var result = call(method, arguments);
			recordParameter(name, arguments);
			return result;
		}
		if (this.target instanceof Statement) {
			return invokeStatement(method, arguments, name);
		}
		return call(method, arguments);
	}

	private Object invokeConnection(Method method, Object[] arguments, String name) throws Throwable {
		if (name.equals("createStatement") || name.equals("prepareStatement") || name.equals("prepareCall")) {
			var statement = (Statement) call(method, arguments);
			var sql = arguments != null && arguments.length > 0 && arguments[0] instanceof String value ? value : null;
			var api = statement instanceof CallableStatement ? CallableStatement.class
					: statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
			return observe(statement, api, new JdbcProxy(statement, this.connection, sql, 0, null));
		}
		if (name.equals("getMetaData")) {
			var metadata = (DatabaseMetaData) call(method, arguments);
			return observe(metadata, DatabaseMetaData.class,
					new JdbcProxy(metadata, this.connection, null, 0, this.observedProxy));
		}
		if (name.equals("setAutoCommit")) {
			boolean before = this.connection.autoCommit();
			var result = call(method, arguments);
			boolean after = (Boolean) arguments[0];
			this.connection.autoCommit(after);
			if (before != after) {
				if (!after) {
					this.connection.reserveTransaction();
				}
				publishLifecycle(SqlEvent.Kind.AUTOCOMMIT_CHANGE, before + " -> " + after, true, "", 0);
			}
			if (after) {
				this.connection.finishTransaction();
			}
			return result;
		}
		if (name.equals("setTransactionIsolation")) {
			int before = this.connection.isolation();
			var result = call(method, arguments);
			int after = (Integer) arguments[0];
			this.connection.isolation(after);
			if (before != after) {
				this.connection.reserveTransaction();
				publishLifecycle(SqlEvent.Kind.ISOLATION_CHANGE, before + " -> " + after, true, "", 0);
			}
			return result;
		}
		if (name.equals("setSavepoint")) {
			this.connection.beginTransactionIfNeeded();
			long started = System.nanoTime();
			try {
				var savepoint = (Savepoint) call(method, arguments);
				publishLifecycle(SqlEvent.Kind.SAVEPOINT, savepointName(savepoint), true, "",
						System.nanoTime() - started);
				return savepoint;
			}
			catch (Throwable ex) {
				publishLifecycle(SqlEvent.Kind.SAVEPOINT, "", false, error(ex), System.nanoTime() - started);
				throw ex;
			}
		}
		if (name.equals("releaseSavepoint")) {
			return timedSavepoint(method, arguments, SqlEvent.Kind.SAVEPOINT_RELEASE, (Savepoint) arguments[0]);
		}
		if (name.equals("rollback") && arguments != null && arguments.length == 1
				&& arguments[0] instanceof Savepoint savepoint) {
			return timedSavepoint(method, arguments, SqlEvent.Kind.SAVEPOINT_ROLLBACK, savepoint);
		}
		if (name.equals("commit") || name.equals("rollback")) {
			var result = timed(method, arguments, name.equals("commit") ? SqlEvent.Kind.COMMIT : SqlEvent.Kind.ROLLBACK,
					"");
			this.connection.finishTransaction();
			return result;
		}
		if (name.equals("close") || name.equals("abort")) {
			if (this.connection.closed()) {
				return call(method, arguments);
			}
			long started = System.nanoTime();
			try {
				var result = call(method, arguments);
				this.connection.closed(true);
				ExplainService.unregister(this.connection.id());
				publishLifecycle(SqlEvent.Kind.CONNECTION_CLOSE, name, true, "", System.nanoTime() - started);
				this.connection.finishTransaction();
				return result;
			}
			catch (Throwable ex) {
				publishLifecycle(SqlEvent.Kind.CONNECTION_CLOSE, name, false, error(ex), System.nanoTime() - started);
				throw ex;
			}
		}
		return call(method, arguments);
	}

	private Object timedSavepoint(Method method, Object[] arguments, SqlEvent.Kind kind, Savepoint savepoint)
			throws Throwable {
		long started = System.nanoTime();
		try {
			var result = call(method, arguments);
			publishLifecycle(kind, savepointName(savepoint), true, "", System.nanoTime() - started);
			return result;
		}
		catch (Throwable ex) {
			publishLifecycle(kind, savepointName(savepoint), false, error(ex), System.nanoTime() - started);
			throw ex;
		}
	}

	private void publishLifecycle(SqlEvent.Kind kind, String detail, boolean success, String failure, long duration) {
		publish(AgentRuntime.nextId(), 0, this.connection, kind, "", detail, Map.of(), Map.of(), duration, 0, 0, -1,
				success, failure, 0);
	}

	private static String savepointName(Savepoint savepoint) {
		try {
			return savepoint.getSavepointName();
		}
		catch (Exception ex) {
			try {
				return "#" + savepoint.getSavepointId();
			}
			catch (Exception ignored) {
				return "savepoint";
			}
		}
	}

	private Object invokeStatement(Method method, Object[] arguments, String name) throws Throwable {
		if (name.equals("clearParameters")) {
			var result = call(method, arguments);
			this.parameters.clear();
			this.parameterMethods.clear();
			return result;
		}
		if (name.equals("clearBatch")) {
			var result = call(method, arguments);
			this.batch.clear();
			return result;
		}
		if (name.equals("addBatch")) {
			var result = call(method, arguments);
			if (this.batch.size() < MAX_BATCH_ENTRIES) {
				this.batch
					.add(arguments != null && arguments.length > 0 ? limit(String.valueOf(arguments[0])) : formatSql());
			}
			return result;
		}
		if (name.equals("executeBatch") || name.equals("executeLargeBatch")) {
			return timed(method, arguments, SqlEvent.Kind.BATCH, batchSql());
		}
		if (name.startsWith("execute")) {
			var kind = name.contains("Query") ? SqlEvent.Kind.QUERY
					: name.contains("Update") ? SqlEvent.Kind.UPDATE : SqlEvent.Kind.EXECUTE;
			var sql = arguments != null && arguments.length > 0 && arguments[0] instanceof String value ? value
					: formatSql();
			return timed(method, arguments, kind, sql);
		}
		if (name.equals("getMoreResults")) {
			if (arguments == null || arguments.length == 0
					|| !Integer.valueOf(Statement.KEEP_CURRENT_RESULT).equals(arguments[0])) {
				reportActiveResultSets();
			}
			return call(method, arguments);
		}
		if (name.equals("getResultSet") || name.equals("getGeneratedKeys")) {
			var result = call(method, arguments);
			return result instanceof ResultSet value ? wrapResultSet(value, this.lastExecutionId) : result;
		}
		if (name.equals("close")) {
			var result = call(method, arguments);
			reportActiveResultSets();
			return result;
		}
		return call(method, arguments);
	}

	private Object invokeResultSet(Method method, Object[] arguments, String name) throws Throwable {
		if (name.equals("next")) {
			long started = System.nanoTime();
			boolean hasNext;
			Throwable failure = null;
			try {
				hasNext = (Boolean) call(method, arguments);
			}
			catch (Throwable ex) {
				failure = ex;
				throw ex;
			}
			finally {
				this.fetchNanos += Math.max(0, System.nanoTime() - started);
				if (failure != null) {
					reportResultSet(false, error(failure));
				}
			}
			if (hasNext) {
				this.resultRows++;
			}
			else {
				reportResultSet();
			}
			return hasNext;
		}
		if (name.equals("close")) {
			var result = call(method, arguments);
			reportResultSet();
			return result;
		}
		return call(method, arguments);
	}

	private Object timed(Method method, Object[] arguments, SqlEvent.Kind kind, String sql) throws Throwable {
		if (isExecution(kind)) {
			this.connection.beginTransactionIfNeeded();
			reportActiveResultSets();
		}
		long id = AgentRuntime.nextId();
		long started = System.nanoTime();
		if (isExecution(kind)) {
			AgentRuntime.throttleSqlExecution();
		}
		long rows = -1;
		this.lastExecutionId = id;
		boolean success = false;
		String failure = "";
		try {
			var result = call(method, arguments);
			success = true;
			if (result instanceof Number number) {
				rows = number.longValue();
			}
			if (result instanceof int[] counts) {
				rows = affectedRows(counts);
			}
			if (result instanceof long[] counts) {
				rows = affectedRows(counts);
			}
			return result instanceof ResultSet value ? wrapResultSet(value, id) : result;
		}
		catch (Throwable ex) {
			failure = error(ex);
			throw ex;
		}
		finally {
			publish(id, 0, this.connection, kind, this.rawSql, limit(sql), this.parameters, this.parameterMethods,
					Math.max(0, System.nanoTime() - started), 0, 0, rows, success, failure, timeout());
		}
	}

	private ResultSet wrapResultSet(ResultSet value, long statementId) {
		var existing = this.resultSets.get(value);
		if (existing != null) {
			return (ResultSet) existing.observedProxy;
		}
		var handler = new JdbcProxy(value, this.connection, this.rawSql, statementId, this.observedProxy);
		var observed = observe(value, ResultSet.class, handler);
		if (observed == value) {
			return value;
		}
		handler.observedProxy = observed;
		this.resultSets.put(value, handler);
		return (ResultSet) observed;
	}

	private void reportActiveResultSets() {
		this.resultSets.values().forEach(JdbcProxy::reportResultSet);
	}

	private void reportResultSet() {
		reportResultSet(true, "");
	}

	private void reportResultSet(boolean success, String failure) {
		if (this.resultSetReported) {
			return;
		}
		this.resultSetReported = true;
		publish(AgentRuntime.nextId(), this.parentId, this.connection, SqlEvent.Kind.RESULT_SET, this.rawSql, "",
				Map.of(), Map.of(), 0, this.fetchNanos, Math.max(0, System.nanoTime() - this.resultSetOpened),
				this.resultRows, success, failure, 0);
	}

	private int timeout() {
		try {
			return ((Statement) this.target).getQueryTimeout();
		}
		catch (Exception ex) {
			return 0;
		}
	}

	private Object call(Method method, Object[] arguments) throws Throwable {
		try {
			return method.invoke(this.target, arguments);
		}
		catch (InvocationTargetException ex) {
			throw ex.getCause();
		}
	}

	private void recordParameter(String name, Object[] arguments) {
		int index = (Integer) arguments[0];
		this.parameters.put(index, name.equals("setNull") ? "NULL" : render(arguments[1]));
		this.parameterMethods.put(index, name);
	}

	private String formatSql() {
		if (this.rawSql == null || this.rawSql.isBlank()) {
			return "";
		}
		var result = new StringBuilder(Math.min(MAX_TEXT_LENGTH, this.rawSql.length() + 64));
		int parameter = 1;
		for (int index = 0; index < this.rawSql.length() && result.length() < MAX_TEXT_LENGTH;) {
			char current = this.rawSql.charAt(index);
			if (current == '\'' || current == '"' || current == '`') {
				index = copyQuoted(this.rawSql, index, current, result);
			}
			else if (current == '[') {
				index = copyBracketIdentifier(this.rawSql, index, result);
			}
			else if (current == '-' && has(this.rawSql, index + 1, '-')) {
				index = copyLineComment(this.rawSql, index, result);
			}
			else if (current == '/' && has(this.rawSql, index + 1, '*')) {
				index = copyBlockComment(this.rawSql, index, result);
			}
			else {
				if (current == '?') {
					result.append(this.parameters.getOrDefault(parameter++, "?"));
				}
				else {
					result.append(current);
				}
				index++;
			}
		}
		return limit(result.toString());
	}

	private String batchSql() {
		var result = new StringBuilder();
		for (var sql : this.batch) {
			if (!result.isEmpty()) {
				result.append(";\n");
			}
			if (result.length() + sql.length() > MAX_TEXT_LENGTH) {
				result.append("…");
				break;
			}
			result.append(sql);
		}
		return result.toString();
	}

	private static boolean isExecution(SqlEvent.Kind kind) {
		return switch (kind) {
			case QUERY, UPDATE, EXECUTE, BATCH -> true;
			default -> false;
		};
	}

	private static long affectedRows(int[] counts) {
		var known = Arrays.stream(counts).filter(value -> value >= 0).toArray();
		return known.length == 0 ? -1 : Arrays.stream(known).asLongStream().sum();
	}

	private static long affectedRows(long[] counts) {
		var known = Arrays.stream(counts).filter(value -> value >= 0).toArray();
		return known.length == 0 ? -1 : Arrays.stream(known).sum();
	}

	private static boolean isInputParameterSetter(String name, Object[] arguments) {
		return name.startsWith("set") && !name.equals("setFetchDirection") && !name.equals("setFetchSize")
				&& arguments != null && arguments.length >= 2 && arguments[0] instanceof Integer;
	}

	private static String render(Object value) {
		if (value == null) {
			return "NULL";
		}
		if (value instanceof Number || value instanceof Boolean) {
			return value.toString();
		}
		if (value instanceof byte[] bytes) {
			int length = Math.min(bytes.length, 64);
			return "X'" + java.util.HexFormat.of().formatHex(bytes, 0, length) + (bytes.length > length ? "…" : "")
					+ "'";
		}
		if (value instanceof InputStream || value instanceof Reader) {
			return "<stream>";
		}
		if (value instanceof Blob || value instanceof Clob || value instanceof NClob || value instanceof SQLXML
				|| value instanceof Array || value instanceof Ref || value instanceof RowId) {
			return "<" + value.getClass().getSimpleName() + ">";
		}
		var string = safeToString(value);
		if (string.length() > 500) {
			string = string.substring(0, 500) + "…";
		}
		return "'" + string.replace("'", "''") + "'";
	}

	private static String safeToString(Object value) {
		try {
			return String.valueOf(value);
		}
		catch (Exception | LinkageError ex) {
			return "<" + value.getClass().getName() + ">";
		}
	}

	private static String error(Throwable throwable) {
		try {
			var message = throwable.getMessage();
			return limit(message == null ? throwable.getClass().getName() : message);
		}
		catch (Exception | LinkageError ex) {
			return throwable.getClass().getName();
		}
	}

	private static void publish(long id, long parentId, ConnectionState connection, SqlEvent.Kind kind, String rawSql,
			String sql, Map<Integer, String> parameters, Map<Integer, String> methods, long duration, long fetch,
			long use, long rows, boolean success, String failure, int timeout) {
		if (!AgentRuntime.enabled()) {
			return;
		}
		try {
			AgentRuntime.publish(event(id, parentId, connection, kind, rawSql, sql, parameters, methods, duration,
					fetch, use, rows, success, failure, timeout));
		}
		catch (Exception | LinkageError ex) {
			reportObservationFailure("event", ex);
		}
	}

	private static SqlEvent event(long id, long parentId, ConnectionState connection, SqlEvent.Kind kind, String rawSql,
			String sql, Map<Integer, String> parameters, Map<Integer, String> methods, long duration, long fetch,
			long use, long rows, boolean success, String failure, int timeout) {
		String limitedRawSql = limit(rawSql);
		String limitedSql = limit(sql);
		var fingerprint = SqlFingerprint
			.normalize(limitedRawSql == null || limitedRawSql.isBlank() ? limitedSql : limitedRawSql);
		var frames = applicationFrames();
		var callSite = frames.isEmpty() ? "" : frames.getFirst().toString();
		long observedDuration = Math.max(duration, Math.max(fetch, use));
		var stackTrace = AgentRuntime.captureStackTrace(observedDuration, success) ? frames.stream()
			.map(StackWalker.StackFrame::toString)
			.collect(java.util.stream.Collectors.joining("\n")) : "";
		return new SqlEvent(id, parentId, connection.transactionId(), Instant.now(), Thread.currentThread().getName(),
				connection.id(), kind, limitedRawSql, limitedSql, parameters, methods, duration, fetch, use, rows,
				success, limit(failure), timeout, connection.autoCommit(), connection.isolation(), connection.url(),
				connection.properties(), fingerprint, callSite, stackTrace);
	}

	private static List<StackWalker.StackFrame> applicationFrames() {
		return STACK_WALKER.walk(stream -> stream.filter(frame -> {
			var name = frame.getClassName();
			return !isAgentFrame(name) && !name.startsWith("java.") && !name.startsWith("jdk.")
					&& !name.startsWith("sun.") && !name.startsWith("org.hibernate.")
					&& !name.startsWith("org.springframework.") && !name.startsWith("org.postgresql.")
					&& !name.startsWith("org.h2.") && !name.startsWith("com.zaxxer.hikari.")
					&& !name.startsWith("jakarta.persistence.");
		}).limit(32).toList());
	}

	private static boolean isAgentFrame(String name) {
		return name.equals(JdbcProxy.class.getName()) || name.startsWith(JdbcProxy.class.getName() + "$")
				|| name.equals(ConnectionInterceptor.class.getName())
				|| name.startsWith(ConnectionInterceptor.class.getName() + "$")
				|| name.equals(AgentRuntime.class.getName()) || name.startsWith(AgentRuntime.class.getName() + "$")
				|| name.equals(JdbcObserverAgent.class.getName())
				|| name.equals(JdbcClassFileTransformer.class.getName())
				|| name.startsWith(JdbcClassFileTransformer.class.getName() + "$");
	}

	private static boolean isObserved(Object value) {
		try {
			return Proxy.isProxyClass(value.getClass()) && Proxy.getInvocationHandler(value) instanceof JdbcProxy;
		}
		catch (Exception | LinkageError ex) {
			return false;
		}
	}

	private static void deactivateNestedObservation(Connection connection) {
		try {
			if (connection.isWrapperFor(ObservedConnection.class)) {
				var observed = connection.unwrap(ObservedConnection.class);
				if (observed != null) {
					observed.jdbcObserverDeactivate();
				}
			}
		}
		catch (Exception | LinkageError ignored) {
		}
	}

	private static <T> T proxy(Object value, Class<T> api, JdbcProxy handler) {
		return api.cast(observed(value, api, handler));
	}

	private static Object observe(Object value, Class<?> api, JdbcProxy handler) {
		try {
			var result = observed(value, api, handler);
			handler.observedProxy = result;
			return result;
		}
		catch (Exception | LinkageError ex) {
			reportObservationFailure(api.getSimpleName(), ex);
			return value;
		}
	}

	private static Object observed(Object value, Class<?> api, InvocationHandler handler) {
		return Proxy.newProxyInstance(proxyLoader(value.getClass().getClassLoader()), interfaces(value.getClass(), api),
				handler);
	}

	private static ClassLoader proxyLoader(ClassLoader targetLoader) {
		if (targetLoader != null) {
			try {
				if (Class.forName(ObservedConnection.class.getName(), false,
						targetLoader) == ObservedConnection.class) {
					return targetLoader;
				}
			}
			catch (ClassNotFoundException | LinkageError ignored) {
			}
		}
		return JdbcProxy.class.getClassLoader();
	}

	private static Class<?>[] interfaces(Class<?> type, Class<?> fallback) {
		var result = new LinkedHashSet<Class<?>>();
		collect(type, result);
		result.add(fallback);
		if (fallback == Connection.class) {
			result.add(ObservedConnection.class);
		}
		return result.stream()
			.filter(Class::isInterface)
			.filter(item -> Modifier.isPublic(item.getModifiers()))
			.toArray(Class<?>[]::new);
	}

	private static void collect(Class<?> type, Set<Class<?>> interfaces) {
		if (type == null) {
			return;
		}
		for (var item : type.getInterfaces()) {
			interfaces.add(item);
			collect(item, interfaces);
		}
		collect(type.getSuperclass(), interfaces);
	}

	private static void reportObservationFailure(String operation, Throwable ex) {
		long failures = OBSERVATION_FAILURES.incrementAndGet();
		if (failures == 1 || (failures & (failures - 1)) == 0) {
			System.err.println("[jdbc-observer] could not observe " + operation + " (" + ex.getClass().getSimpleName()
					+ "); JDBC call continues unchanged; failures=" + failures);
		}
	}

	private static String limit(String value) {
		if (value == null || value.length() <= MAX_TEXT_LENGTH) {
			return value;
		}
		return value.substring(0, MAX_TEXT_LENGTH) + "…";
	}

	private static int copyQuoted(String sql, int index, char quote, StringBuilder target) {
		target.append(quote);
		index++;
		while (index < sql.length() && target.length() < MAX_TEXT_LENGTH) {
			char current = sql.charAt(index++);
			target.append(current);
			if (current == '\\' && quote == '\'' && index < sql.length()) {
				target.append(sql.charAt(index++));
			}
			else if (current == quote) {
				if (has(sql, index, quote)) {
					target.append(sql.charAt(index++));
				}
				else {
					break;
				}
			}
		}
		return index;
	}

	private static int copyBracketIdentifier(String sql, int index, StringBuilder target) {
		while (index < sql.length() && target.length() < MAX_TEXT_LENGTH) {
			char current = sql.charAt(index++);
			target.append(current);
			if (current == ']' && !has(sql, index, ']')) {
				break;
			}
		}
		return index;
	}

	private static int copyLineComment(String sql, int index, StringBuilder target) {
		while (index < sql.length() && target.length() < MAX_TEXT_LENGTH) {
			char current = sql.charAt(index++);
			target.append(current);
			if (current == '\n' || current == '\r') {
				break;
			}
		}
		return index;
	}

	private static int copyBlockComment(String sql, int index, StringBuilder target) {
		while (index < sql.length() && target.length() < MAX_TEXT_LENGTH) {
			char current = sql.charAt(index++);
			target.append(current);
			if (current == '*' && has(sql, index, '/')) {
				target.append(sql.charAt(index++));
				break;
			}
		}
		return index;
	}

	private static boolean has(String value, int index, char expected) {
		return index >= 0 && index < value.length() && value.charAt(index) == expected;
	}

	private static final class ConnectionState {

		private final String id;

		private final String url;

		private final String properties;

		private volatile Connection observedConnection;

		private long transactionId;

		private long pendingTransactionId;

		private boolean autoCommit;

		private int isolation;

		private boolean closed;

		private boolean active = true;

		private ConnectionState(String id, String url, String properties, boolean autoCommit, int isolation) {
			this.id = id;
			this.url = url;
			this.properties = properties;
			this.autoCommit = autoCommit;
			this.isolation = isolation;
		}

		static ConnectionState create(Connection connection, String url, String properties) {
			String detectedUrl = url == null ? "" : url;
			try {
				if (detectedUrl.isBlank()) {
					detectedUrl = connection.getMetaData().getURL();
				}
			}
			catch (Exception ignored) {
			}
			boolean autoCommit = true;
			int isolation = Connection.TRANSACTION_NONE;
			try {
				autoCommit = connection.getAutoCommit();
			}
			catch (Exception ignored) {
			}
			try {
				isolation = connection.getTransactionIsolation();
			}
			catch (Exception ignored) {
			}
			return new ConnectionState("c" + Long.toUnsignedString(CONNECTION_IDS.incrementAndGet()),
					ConnectionInterceptor.redactUrl(detectedUrl), properties == null ? "" : limit(properties),
					autoCommit, isolation);
		}

		String id() {
			return this.id;
		}

		String url() {
			return this.url;
		}

		String properties() {
			return this.properties;
		}

		Connection observedConnection() {
			return this.observedConnection;
		}

		synchronized long transactionId() {
			return this.transactionId != 0 ? this.transactionId : this.pendingTransactionId;
		}

		synchronized void reserveTransaction() {
			if (!this.autoCommit && this.transactionId == 0 && this.pendingTransactionId == 0) {
				this.pendingTransactionId = AgentRuntime.nextId();
			}
		}

		synchronized void beginTransactionIfNeeded() {
			if (this.autoCommit || this.transactionId != 0) {
				return;
			}
			this.transactionId = this.pendingTransactionId != 0 ? this.pendingTransactionId : AgentRuntime.nextId();
			this.pendingTransactionId = 0;
			publish(this.transactionId, 0, this, SqlEvent.Kind.TRANSACTION_BEGIN, "", "", Map.of(), Map.of(), 0, 0, 0,
					-1, true, "", 0);
		}

		synchronized void finishTransaction() {
			this.transactionId = 0;
			this.pendingTransactionId = 0;
		}

		synchronized boolean autoCommit() {
			return this.autoCommit;
		}

		synchronized void autoCommit(boolean value) {
			this.autoCommit = value;
		}

		synchronized int isolation() {
			return this.isolation;
		}

		synchronized void isolation(int value) {
			this.isolation = value;
		}

		synchronized boolean closed() {
			return this.closed;
		}

		synchronized void closed(boolean value) {
			this.closed = value;
		}

		synchronized boolean active() {
			return this.active;
		}

		synchronized void deactivate() {
			if (!this.active) {
				return;
			}
			this.active = false;
			publish(AgentRuntime.nextId(), 0, this, SqlEvent.Kind.CONNECTION_CLOSE, "", "pooled handoff", Map.of(),
					Map.of(), 0, 0, 0, -1, true, "", 0);
			finishTransaction();
		}

	}

}
