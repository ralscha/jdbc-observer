package ch.rasc.jdbcobserver.agent;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

class JdbcClassFileTransformerTest {

	@Test
	void transformsRealJdbcFactoriesButNotUnrelatedGetConnectionMethods() throws Exception {
		var instrumentation = (Instrumentation) Proxy.newProxyInstance(Instrumentation.class.getClassLoader(),
				new Class<?>[] { Instrumentation.class },
				(proxy, method, arguments) -> defaultValue(method.getReturnType()));
		var transformer = new JdbcClassFileTransformer(instrumentation);
		var loader = getClass().getClassLoader();

		byte[] transformed = transformer.transform(getClass().getModule(), loader, "testfixture/JdbcDataSourceFixture",
				null, null, bytes("testfixture/JdbcDataSourceFixture.class"));
		assertNotNull(transformed);
		assertNull(transformer.transform(getClass().getModule(), loader, "testfixture/UnrelatedConnectionFactory", null,
				null, bytes("testfixture/UnrelatedConnectionFactory.class")));

		var fixture = new FixtureLoader(loader).define("testfixture.JdbcDataSourceFixture", transformed);
		var instance = fixture.getConstructor().newInstance();
		assertNull(fixture.getMethod("getConnection").invoke(instance));
	}

	private byte[] bytes(String resource) throws IOException {
		try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
			if (input == null) {
				throw new IOException("Missing fixture " + resource);
			}
			return input.readAllBytes();
		}
	}

	private static Object defaultValue(Class<?> type) {
		if (type == boolean.class) {
			return false;
		}
		if (type == long.class) {
			return 0L;
		}
		if (type == int.class) {
			return 0;
		}
		return null;
	}

	private static final class FixtureLoader extends ClassLoader {

		private FixtureLoader(ClassLoader parent) {
			super(parent);
		}

		private Class<?> define(String name, byte[] bytes) {
			return defineClass(name, bytes, 0, bytes.length);
		}

	}

}
