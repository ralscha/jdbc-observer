package ch.rasc.jdbcobserver.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Map;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JdbcProxyTest {

	@Test
	void preservesJdbcRelationshipsAndDoesNotWrapResultSetsTwice() throws Exception {
		try (var raw = java.sql.DriverManager.getConnection("jdbc:h2:mem:proxy-test")) {
			var connection = JdbcProxy.wrapConnection(raw);
			assertSame(connection, connection.unwrap(Connection.class));
			assertSame(connection, connection.getMetaData().getConnection());
			try (var statement = connection.prepareStatement("select ? as answer")) {
				assertSame(connection, statement.getConnection());
				statement.setInt(1, 42);
				var first = statement.executeQuery();
				assertSame(statement, first.getStatement());
				assertSame(first, statement.getResultSet());
				assertTrue(first.next());
				assertFalse(first.next());
				assertTrue(longField(handler(first), "fetchNanos") > 0);
				first.close();
			}
		}
	}

	@Test
	void releasesResultSetsOnCloseAndStatementReuse() throws Exception {
		try (var raw = java.sql.DriverManager.getConnection("jdbc:h2:mem:result-reuse")) {
			var connection = JdbcProxy.wrapConnection(raw);
			try (var statement = connection.prepareStatement("select 42")) {
				for (int index = 0; index < 20; index++) {
					var result = statement.executeQuery();
					assertTrue(result.next());
					assertFalse(result.next());
					assertSame(result, statement.getResultSet());
					assertEquals(1, ((Map<?, ?>) field((Object) handler(statement), "resultSets")).size());
				}
				statement.getResultSet().close();
				assertTrue(((Map<?, ?>) field((Object) handler(statement), "resultSets")).isEmpty());
			}
		}
	}

	@Test
	void closeCurrentResultDoesNotReportResultsKeptOpenEarlier() throws Exception {
		var current = new AtomicInteger();
		var results = List.of(proxy(ResultSet.class, (p, m, a) -> defaultValue(m.getReturnType())),
				proxy(ResultSet.class, (p, m, a) -> defaultValue(m.getReturnType())));
		var raw = proxy(Statement.class, (p, method, arguments) -> switch (method.getName()) {
			case "execute" -> true;
			case "getResultSet" -> results.get(current.get());
			case "getMoreResults" -> current.incrementAndGet() < results.size();
			default -> defaultValue(method.getReturnType());
		});
		var statement = JdbcProxy.wrapConnection(fakeConnection(raw, false)).createStatement();
		statement.execute("call results()");
		var first = statement.getResultSet();
		assertTrue(statement.getMoreResults(Statement.KEEP_CURRENT_RESULT));
		var second = statement.getResultSet();
		assertFalse(statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT));
		assertFalse((Boolean) field((Object) handler(first), "resultSetReported"));
		assertTrue((Boolean) field((Object) handler(second), "resultSetReported"));
		statement.getMoreResults(Statement.CLOSE_ALL_RESULTS);
		assertTrue((Boolean) field((Object) handler(first), "resultSetReported"));
		assertTrue(((Map<?, ?>) field((Object) handler(statement), "resultSets")).isEmpty());
	}

	@Test
	void clearsCapturedBatchesAfterSuccessAndFailure() throws Exception {
		try (var raw = java.sql.DriverManager.getConnection("jdbc:h2:mem:batch-reuse")) {
			var connection = JdbcProxy.wrapConnection(raw);
			try (var statement = connection.createStatement()) {
				statement.execute("create table item(id int primary key)");
				statement.addBatch("insert into item values (1)");
				statement.executeBatch();
				assertTrue(((List<?>) field((Object) handler(statement), "batch")).isEmpty());
				statement.addBatch("insert into item values (1)");
				assertThrows(java.sql.BatchUpdateException.class, statement::executeLargeBatch);
				assertTrue(((List<?>) field((Object) handler(statement), "batch")).isEmpty());
				statement.addBatch("insert into item values (2)");
				assertEquals(List.of("insert into item values (2)"), field((Object) handler(statement), "batch"));
			}
		}
	}

	@Test
	void boundsCapturedBatchTextAsEntriesAreAdded() throws Exception {
		var raw = proxy(Statement.class, (p, m, a) -> defaultValue(m.getReturnType()));
		var statement = JdbcProxy.wrapConnection(fakeConnection(raw, false)).createStatement();
		for (int index = 0; index < 100; index++) {
			statement.addBatch("x".repeat(50_000));
		}
		assertTrue((Integer) field((Object) handler(statement), "batchCharacters") <= 1_000_000);
		assertTrue((Boolean) field((Object) handler(statement), "batchTruncated"));
		statement.clearBatch();
		assertEquals(0, field((Object) handler(statement), "batchCharacters"));
		assertFalse((Boolean) field((Object) handler(statement), "batchTruncated"));
	}

	@Test
	void rendersIndexedParametersInSqlOrderWithoutTouchingQuotedQuestionMarks() throws Exception {
		try (var raw = java.sql.DriverManager.getConnection("jdbc:h2:mem:parameter-test")) {
			var connection = JdbcProxy.wrapConnection(raw);
			try (var statement = connection
				.prepareStatement("select '?' as marker, ? as first_value, ? as second_value")) {
				statement.setInt(2, 22);
				statement.setNull(1, Types.INTEGER);
				var handler = handler(statement);
				assertEquals("select '?' as marker, NULL as first_value, 22 as second_value", invokeFormatSql(handler));
				assertEquals(Map.of(1, "NULL", 2, "22"), field(handler, "parameters"));
			}
		}
	}

	@Test
	void telemetryBookkeepingCannotBreakAWorkingSetterAndOnlyRunsAfterSuccess() throws Exception {
		var setterCalls = new AtomicInteger();
		var setterFails = new AtomicBoolean();
		var rawStatement = proxy(PreparedStatement.class, (proxy, method, arguments) -> {
			if (method.getName().equals("setObject") || method.getName().equals("setInt")) {
				setterCalls.incrementAndGet();
				if (setterFails.get()) {
					throw new SQLException("setter failed");
				}
				return null;
			}
			return defaultValue(method.getReturnType());
		});
		var rawConnection = fakeConnection(rawStatement, false);
		var observed = JdbcProxy.wrapConnection(rawConnection);
		var statement = observed.prepareStatement("select ?");
		var dangerous = new Object() {
			@Override
			public String toString() {
				throw new IllegalStateException("must not affect JDBC");
			}
		};

		statement.setObject(1, dangerous);
		assertEquals(1, setterCalls.get());
		assertTrue(field(handler(statement), "parameters").get(1).toString().contains(dangerous.getClass().getName()));

		setterFails.set(true);
		assertThrows(SQLException.class, () -> statement.setInt(2, 2));
		assertFalse(field(handler(statement), "parameters").containsKey(2));
	}

	@Test
	void failedCommitKeepsTheTransactionActive() throws Exception {
		var rawStatement = proxy(Statement.class, (proxy, method, arguments) -> {
			if (method.getName().equals("execute")) {
				return false;
			}
			return defaultValue(method.getReturnType());
		});
		var rawConnection = fakeConnection(rawStatement, true);
		var connection = JdbcProxy.wrapConnection(rawConnection);
		connection.setAutoCommit(false);
		connection.createStatement().execute("update example set value=1");
		var state = field((Object) handler(connection), "connection");
		long transactionId = invokeLong(state, "transactionId");

		assertTrue(transactionId > 0);
		assertThrows(SQLException.class, connection::commit);
		assertEquals(transactionId, invokeLong(state, "transactionId"));
	}

	@Test
	void redactsSecretsInUrlsAndProperties() throws Exception {
		assertEquals("jdbc:test?user=demo&password=***&token=***",
				ConnectionInterceptor.redactUrl("jdbc:test?user=demo&password=hidden&token=secret"));
		assertEquals("jdbc:test;credential=***;clientSecret=***;PWD=***;user=demo", ConnectionInterceptor
			.redactUrl("jdbc:test;credential=hidden;clientSecret=hidden;PWD=hidden;user=demo"));
		assertEquals("jdbc:test?access_token=***&pass%77ord=***&path=$1\\data",
				ConnectionInterceptor.redactUrl("jdbc:test?access_token=hidden&pass%77ord=hidden&path=$1\\data"));
		assertEquals("jdbc:test:(password=***)", ConnectionInterceptor.redactUrl("jdbc:test:(password=hidden)"));
		assertEquals("jdbc:test;password=***;user=demo",
				ConnectionInterceptor.redactUrl("jdbc:test;password={semi;colon}}secret};user=demo"));
		var properties = new Properties();
		properties.setProperty("user", "demo");
		properties.setProperty("password", "hidden");
		var method = ConnectionInterceptor.class.getDeclaredMethod("sanitizeProperties", Properties.class);
		method.setAccessible(true);
		assertEquals("{password=***, user=demo}", method.invoke(null, properties));
	}

	@Test
	void movesObservationFromAPhysicalConnectionToTheLogicalPoolBoundary() throws Exception {
		var rawStatement = proxy(PreparedStatement.class,
				(proxy, method, arguments) -> defaultValue(method.getReturnType()));
		var physical = JdbcProxy.wrapConnection(fakeConnection(rawStatement, false));
		var pooled = proxy(Connection.class, (proxy, method, arguments) -> {
			if (method.getName().equals("isWrapperFor") && arguments[0] instanceof Class<?> api) {
				return api.isInstance(physical) || physical.isWrapperFor(api);
			}
			if (method.getName().equals("unwrap") && arguments[0] instanceof Class<?> api && api.isInstance(physical)) {
				return api.cast(physical);
			}
			try {
				return method.invoke(physical, arguments);
			}
			catch (java.lang.reflect.InvocationTargetException ex) {
				throw ex.getCause();
			}
		});

		var logical = JdbcProxy.wrapConnection(pooled);

		var physicalState = field((Object) handler(physical), "connection");
		var logicalState = field((Object) handler(logical), "connection");
		assertFalse(invokeBoolean(physicalState, "active"));
		assertTrue(invokeBoolean(logicalState, "active"));
		assertSame(logical, logical.prepareStatement("select ?").getConnection());
	}

	private static Connection fakeConnection(Statement statement, boolean failCommit) {
		var autoCommit = new AtomicBoolean(true);
		var metadata = proxy(DatabaseMetaData.class, (proxy, method, arguments) -> method.getName().equals("getURL")
				? "jdbc:fake" : defaultValue(method.getReturnType()));
		return proxy(Connection.class, (proxy, method, arguments) -> switch (method.getName()) {
			case "getMetaData" -> metadata;
			case "getAutoCommit" -> autoCommit.get();
			case "setAutoCommit" -> {
				autoCommit.set((Boolean) arguments[0]);
				yield null;
			}
			case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
			case "prepareStatement", "createStatement" -> statement;
			case "commit" -> {
				if (failCommit) {
					throw new SQLException("commit failed");
				}
				yield null;
			}
			default -> defaultValue(method.getReturnType());
		});
	}

	private static JdbcProxy handler(Object value) {
		return (JdbcProxy) Proxy.getInvocationHandler(value);
	}

	@SuppressWarnings("unchecked")
	private static Map<Integer, String> field(JdbcProxy handler, String name) throws Exception {
		return (Map<Integer, String>) field((Object) handler, name);
	}

	private static Object field(Object target, String name) throws Exception {
		var field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static long longField(Object target, String name) throws Exception {
		return (Long) field(target, name);
	}

	private static long invokeLong(Object target, String name) throws Exception {
		var method = target.getClass().getDeclaredMethod(name);
		method.setAccessible(true);
		return (Long) method.invoke(target);
	}

	private static boolean invokeBoolean(Object target, String name) throws Exception {
		var method = target.getClass().getDeclaredMethod(name);
		method.setAccessible(true);
		return (Boolean) method.invoke(target);
	}

	private static String invokeFormatSql(JdbcProxy handler) throws Exception {
		var method = JdbcProxy.class.getDeclaredMethod("formatSql");
		method.setAccessible(true);
		return (String) method.invoke(handler);
	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> api, InvocationHandler handler) {
		return (T) Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[] { api }, handler);
	}

	private static Object defaultValue(Class<?> type) {
		if (!type.isPrimitive() || type == void.class) {
			return null;
		}
		if (type == boolean.class) {
			return false;
		}
		if (type == byte.class) {
			return (byte) 0;
		}
		if (type == short.class) {
			return (short) 0;
		}
		if (type == int.class) {
			return 0;
		}
		if (type == long.class) {
			return 0L;
		}
		if (type == float.class) {
			return 0F;
		}
		if (type == double.class) {
			return 0D;
		}
		return '\0';
	}

}
