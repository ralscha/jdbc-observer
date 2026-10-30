package ch.rasc.jdbcobserver.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ConnectionInterceptorTest {

	@Test
	void redactsOracleCredentialsBeforeDatabaseDescription() {
		assertEquals("jdbc:oracle:thin:scott/***@//localhost:1521/service",
				ConnectionInterceptor.redactUrl("jdbc:oracle:thin:scott/tiger@//localhost:1521/service"));
		assertEquals("jdbc:oracle:oci:scott/***@database",
				ConnectionInterceptor.redactUrl("jdbc:oracle:oci:scott/tiger@database"));
		assertEquals("jdbc:oracle:kprb:scott/***", ConnectionInterceptor.redactUrl("jdbc:oracle:kprb:scott/tiger"));
		assertEquals("jdbc:oracle:thin:scott/***@database?password=***&user=scott",
				ConnectionInterceptor.redactUrl("jdbc:oracle:thin:scott/tiger@database?password=secret&user=scott"));
		assertEquals("jdbc:oracle:thin:scott/***@database",
				ConnectionInterceptor.redactUrl("jdbc:oracle:thin:scott/\"ti@ger\"@database"));
		assertEquals("jdbc:oracle:thin:\"sc/ott\"/***@database",
				ConnectionInterceptor.redactUrl("jdbc:oracle:thin:\"sc/ott\"/\"ti\"\"@ger\"@database"));
	}

	@Test
	void redactsSecretsInNestedAddressProperties() {
		assertEquals("jdbc:test?password=***&user=scott",
				ConnectionInterceptor.redactUrl("jdbc:test?password=(secret&user=scott"));
		assertEquals("jdbc:test:address=(host=localhost)(password=***)",
				ConnectionInterceptor.redactUrl("jdbc:test:address=(host=localhost)(password=secret)"));
		assertEquals("jdbc:oracle:thin:@(DESCRIPTION=(SECURITY=(PASSWORD=***))(HOST=localhost))", ConnectionInterceptor
			.redactUrl("jdbc:oracle:thin:@(DESCRIPTION=(SECURITY=(PASSWORD=secret))(HOST=localhost))"));
	}

	@Test
	void preservesOracleUrlsWithoutEmbeddedCredentials() {
		assertEquals("jdbc:oracle:thin:@//localhost:1521/service",
				ConnectionInterceptor.redactUrl("jdbc:oracle:thin:@//localhost:1521/service"));
		assertEquals("jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=localhost))(CONNECT_DATA=(SERVICE_NAME=db)))",
				ConnectionInterceptor.redactUrl(
						"jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=localhost))(CONNECT_DATA=(SERVICE_NAME=db)))"));
	}

}
