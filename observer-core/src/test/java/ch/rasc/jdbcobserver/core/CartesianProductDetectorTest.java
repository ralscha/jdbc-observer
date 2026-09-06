package ch.rasc.jdbcobserver.core;

import static ch.rasc.jdbcobserver.core.CartesianProductDetector.Finding.EXPLICIT_CROSS_JOIN;
import static ch.rasc.jdbcobserver.core.CartesianProductDetector.Finding.NONE;
import static ch.rasc.jdbcobserver.core.CartesianProductDetector.Finding.UNCONSTRAINED_COMMA_JOIN;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CartesianProductDetectorTest {

	@Test
	void detectsExplicitCrossJoin() {
		assertEquals(EXPLICIT_CROSS_JOIN,
				CartesianProductDetector.detect("select * from customer c CROSS /* intentional */ JOIN country x"));
	}

	@Test
	void detectsSimpleUnconstrainedCommaJoin() {
		assertEquals(UNCONSTRAINED_COMMA_JOIN,
				CartesianProductDetector.detect("select * from sales.customer c, public.country as x order by c.id"));
	}

	@Test
	void detectsCartesianProductInsideSubquery() {
		assertEquals(UNCONSTRAINED_COMMA_JOIN, CartesianProductDetector
			.detect("select * from customer where id in (select c.id from customer c, country x)"));
	}

	@Test
	void acceptsCommaJoinWithSameLevelWhereClause() {
		assertEquals(NONE,
				CartesianProductDetector.detect("select * from customer c, country x where c.country_id = x.id"));
	}

	@Test
	void ignoresCommasInsideFunctionsAndComplexTableExpressions() {
		assertEquals(NONE, CartesianProductDetector.detect("select coalesce(a, b) from customer"));
		assertEquals(NONE, CartesianProductDetector.detect("select * from unnest(array[1, 2]) value, customer c"));
	}

	@Test
	void ignoresKeywordsInValuesCommentsAndIdentifiers() {
		assertEquals(NONE, CartesianProductDetector.detect("select 'cross join', \"cross\" from customer"));
		assertEquals(NONE, CartesianProductDetector.detect("select * from customer /* cross join country */"));
		assertEquals(NONE, CartesianProductDetector.detect("select * from catalog.cross join country on true"));
	}

	@Test
	void doesNotLetLaterStatementsOrSetOperationsHideACommaJoin() {
		assertEquals(UNCONSTRAINED_COMMA_JOIN,
				CartesianProductDetector.detect("select * from a, b; select * from c where id = 1"));
		assertEquals(UNCONSTRAINED_COMMA_JOIN,
				CartesianProductDetector.detect("select a.id from a, b except select id from c where id = 1"));
		assertEquals(NONE, CartesianProductDetector.detect("select * from a, b\u2026"));
	}

	@Test
	void acceptsNormalQualifiedJoinsAndEmptyInput() {
		assertEquals(NONE,
				CartesianProductDetector.detect("select * from customer c join country x on c.country_id = x.id"));
		assertEquals(NONE, CartesianProductDetector.detect(""));
		assertEquals(NONE, CartesianProductDetector.detect(null));
	}

}
