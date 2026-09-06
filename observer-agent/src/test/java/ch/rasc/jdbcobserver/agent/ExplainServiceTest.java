package ch.rasc.jdbcobserver.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.rasc.jdbcobserver.core.ControlCodec;
import java.sql.DriverManager;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class ExplainServiceTest {

	@Test
	void explainsSqlOnTheMatchingConnectionWithoutExecutingIt() throws Exception {
		try (var connection = DriverManager.getConnection("jdbc:h2:mem:explain;DB_CLOSE_DELAY=-1")) {
			try (var statement = connection.createStatement()) {
				statement.execute("create table book (id bigint primary key, title varchar(100))");
				statement.execute("insert into book values (1, 'one')");
			}
			ExplainService.register("c-test", connection, null);

			var response = ExplainService
				.explain(new ControlCodec.ExplainRequest(7, "c-test", "delete from book where id = 1;"));

			assertTrue(response.success(), response.error());
			assertTrue(response.plan().contains("DELETE"));
			try (var statement = connection.createStatement();
					var resultSet = statement.executeQuery("select count(*) from book")) {
				assertTrue(resultSet.next());
				assertEquals(1, resultSet.getInt(1));
			}
			ExplainService.unregister("c-test");
		}
	}

	@Test
	void rejectsMultipleStatementsAndMissingConnections() throws Exception {
		try (var connection = DriverManager.getConnection("jdbc:h2:mem:explain_reject")) {
			ExplainService.register("c-reject", connection, null);
			var multiple = ExplainService
				.explain(new ControlCodec.ExplainRequest(8, "c-reject", "select 1; delete from book"));
			assertFalse(multiple.success());
			assertTrue(multiple.error().contains("one SQL statement"));
			ExplainService.unregister("c-reject");
		}

		var missing = ExplainService.explain(new ControlCodec.ExplainRequest(9, "c-missing", "select 1"));
		assertFalse(missing.success());
		assertTrue(missing.error().contains("no longer available"));
	}

	@Test
	void explainingAnOldPooledConnectionDoesNotInvalidateItsCurrentBorrower() throws Exception {
		var dataSource = new JdbcDataSource();
		dataSource.setURL("jdbc:h2:mem:explain_pooled");
		var pooled = dataSource.getPooledConnection();
		try {
			var original = pooled.getConnection();
			ExplainService.register("c-pooled", original, pooled);
			var live = ExplainService.explain(new ControlCodec.ExplainRequest(11, "c-pooled", "select 1"));
			assertTrue(live.success(), live.error());
			original.close();
			ExplainService.unregister("c-pooled");
			try (var current = pooled.getConnection()) {
				var response = ExplainService.explain(new ControlCodec.ExplainRequest(12, "c-pooled", "select 1"));
				assertFalse(response.success());
				assertFalse(current.isClosed(), "EXPLAIN must not replace another application's logical connection");
				try (var statement = current.createStatement(); var result = statement.executeQuery("select 42")) {
					assertTrue(result.next());
					assertEquals(42, result.getInt(1));
				}
			}
		}
		finally {
			ExplainService.unregister("c-pooled");
			pooled.close();
		}
	}

	@Test
	void borrowsAReplacementConnectionFromTheOriginatingDataSource() throws Exception {
		var dataSource = new JdbcDataSource();
		dataSource.setURL("jdbc:h2:mem:explain_source;DB_CLOSE_DELAY=-1");
		try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
			statement.execute("create table author (id bigint primary key, name varchar(100))");
		}
		var original = dataSource.getConnection();
		ExplainService.register("c-source", original, dataSource);
		original.close();
		ExplainService.unregister("c-source");

		var response = ExplainService
			.explain(new ControlCodec.ExplainRequest(10, "c-source", "select * from author where id = 1"));

		assertTrue(response.success(), response.error());
		assertTrue(response.plan().toUpperCase(java.util.Locale.ROOT).contains("SELECT"));
		ExplainService.unregister("c-source");
	}

}
