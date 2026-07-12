package ch.rasc.jdbcobserver.agent;

import ch.rasc.jdbcobserver.core.EventCodec;
import ch.rasc.jdbcobserver.core.SqlEvent;
import java.io.*;
import java.net.*;
import java.sql.*;
import java.util.ArrayList;
import org.h2.jdbcx.JdbcDataSource;

public final class SmokeApplication {

	public static void main(String[] args) throws Exception {
		try (var socket = new Socket(InetAddress.getLoopbackAddress(), 4561);
				var input = new DataInputStream(socket.getInputStream())) {
			socket.setSoTimeout(5_000);
			EventCodec.readHeader(input);
			try (var connection = DriverManager.getConnection("jdbc:h2:mem:observer");
					var statement = connection.prepareStatement("select ? as answer")) {
				if (statement.getConnection() != connection || connection.getMetaData().getConnection() != connection)
					throw new AssertionError("JDBC relationships escaped the observed connection");
				statement.setInt(1, 42);
				try (var result = statement.executeQuery()) {
					if (result.getStatement() != statement)
						throw new AssertionError("result set did not retain its observed statement");
					if (!result.next() || result.getInt(1) != 42)
						throw new AssertionError("query failed");
				}
				connection.setAutoCommit(false);
				connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
				try (var transactionStatement = connection.createStatement();
						var result = transactionStatement.executeQuery("select 1")) {
					result.next();
				}
				var rollbackPoint = connection.setSavepoint("rollback-point");
				try (var transactionStatement = connection.createStatement();
						var result = transactionStatement.executeQuery("select 2")) {
					result.next();
				}
				connection.rollback(rollbackPoint);
				var releasePoint = connection.setSavepoint("release-point");
				connection.releaseSavepoint(releasePoint);
				connection.commit();
			}
			SqlEvent connectionEvent = null;
			SqlEvent queryEvent = null;
			SqlEvent resultSetEvent = null;
			var transactionEvents = new ArrayList<SqlEvent>();
			boolean connectionClosed = false;
			int connectionEvents = 0;
			while (!connectionClosed) {
				var event = EventCodec.read(input);
				if (event.transactionId() != 0)
					transactionEvents.add(event);
				switch (event.kind()) {
					case CONNECTION -> {
						connectionEvent = event;
						connectionEvents++;
					}
					case CONNECTION_CLOSE -> connectionClosed = true;
					case QUERY -> {
						if (queryEvent == null)
							queryEvent = event;
					}
					case RESULT_SET -> {
						if (resultSetEvent == null)
							resultSetEvent = event;
					}
					default -> {
					}
				}
			}
			if (!connectionEvent.connectionUrl().startsWith("jdbc:h2:mem:observer"))
				throw new AssertionError("connection metadata missing: " + connectionEvent);
			if (connectionEvents != 1)
				throw new AssertionError("connection was observed " + connectionEvents + " times");
			if (!queryEvent.sql().contains("42") || !queryEvent.success()
					|| !"setInt".equals(queryEvent.parameterMethods().get(1)))
				throw new AssertionError("unexpected query event: " + queryEvent);
			if (!queryEvent.fingerprint().equals("select ? as answer")
					|| !queryEvent.callSite().contains("SmokeApplication.main") || queryEvent.stackTrace().isBlank())
				throw new AssertionError("attribution missing: " + queryEvent);
			if (resultSetEvent.parentId() != queryEvent.id() || resultSetEvent.rows() != 1
					|| resultSetEvent.resultSetUseNanos() <= 0)
				throw new AssertionError("unexpected result-set event: " + resultSetEvent);
			var transactionKinds = transactionEvents.stream()
				.map(SqlEvent::kind)
				.collect(java.util.stream.Collectors.toSet());
			for (var required : java.util.Set.of(SqlEvent.Kind.TRANSACTION_BEGIN, SqlEvent.Kind.AUTOCOMMIT_CHANGE,
					SqlEvent.Kind.ISOLATION_CHANGE, SqlEvent.Kind.QUERY, SqlEvent.Kind.SAVEPOINT,
					SqlEvent.Kind.SAVEPOINT_ROLLBACK, SqlEvent.Kind.SAVEPOINT_RELEASE, SqlEvent.Kind.COMMIT)) {
				if (!transactionKinds.contains(required))
					throw new AssertionError("missing transaction event " + required + ": " + transactionKinds);
			}
			if (transactionEvents.stream().mapToLong(SqlEvent::transactionId).distinct().count() != 1)
				throw new AssertionError("transaction events do not share one identity: " + transactionEvents);

			var dataSource = new JdbcDataSource();
			dataSource.setURL("jdbc:h2:mem:observer-pool");
			try (var connection = dataSource.getConnection();
					var statement = connection.prepareStatement("select ? as pooled_answer")) {
				statement.setInt(1, 7);
				try (var result = statement.executeQuery()) {
					result.next();
				}
			}
			int pooledConnections = 0;
			int pooledQueries = 0;
			boolean pooledConnectionClosed = false;
			while (!pooledConnectionClosed) {
				var event = EventCodec.read(input);
				if (event.kind() == SqlEvent.Kind.CONNECTION)
					pooledConnections++;
				if (event.kind() == SqlEvent.Kind.QUERY && event.rawSql().contains("pooled_answer"))
					pooledQueries++;
				if (event.kind() == SqlEvent.Kind.CONNECTION_CLOSE)
					pooledConnectionClosed = true;
			}
			if (pooledConnections != 1 || pooledQueries != 1)
				throw new AssertionError("nested DataSource/Driver observation: connections=" + pooledConnections
						+ ", queries=" + pooledQueries);
			System.out.println("SMOKE_OK SQL telemetry, attribution, and complete transaction timeline");
		}
	}

}
