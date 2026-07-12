package ch.rasc.jdbcobserver.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DemoServiceTest {

	@Test
	void nPlusOneAndOptimizedPathsReturnEquivalentOrderedResults() {
		var first = new Author("First");
		first.addBook("One");
		var second = new Author("Second");
		second.addBook("Two");
		var nPlusOneCalls = new AtomicInteger();
		var optimizedCalls = new AtomicInteger();
		var repository = (AuthorRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { AuthorRepository.class }, (proxy, method, arguments) -> switch (method.getName()) {
					case "findAllByOrderByIdAsc" -> {
						nPlusOneCalls.incrementAndGet();
						yield List.of(first, second);
					}
					case "findAllWithBooks" -> {
						optimizedCalls.incrementAndGet();
						yield List.of(first, second);
					}
					default -> throw new UnsupportedOperationException(method.getName());
				});
		var service = new DemoService(repository);

		assertEquals(service.nPlusOne(), service.fixed());
		assertEquals(1, nPlusOneCalls.get());
		assertEquals(1, optimizedCalls.get());
	}

}
