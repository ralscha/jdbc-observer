package ch.rasc.jdbcobserver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SqlTextTest {

	@Test
	void preservesQuotedTextAndNestedCommentsWhileRenderingParameters() {
		String sql = "select $$?$$, $tag$? ' $tag$, [a]]?], \"a\"\"?\", `a``?`, '?' /* outer /* ? */ ? */ -- ?\n, ?";
		assertEquals(sql.substring(0, sql.length() - 1) + "42", SqlText.renderParameters(sql, Map.of(1, "42"), 1_000));
	}

	@Test
	void rendersEscapedPostgresOperatorsWithoutConsumingParameters() {
		assertEquals("select document ? 'key', document ?| array['x'] where id = 42",
				SqlText.renderParameters("select document ?? ?, document ??| array[?] where id = ?",
						Map.of(1, "'key'", 2, "'x'", 3, "42"), 1_000));
	}

	@Test
	void doesNotTreatDollarSignsInIdentifiersOrPositionalParametersAsQuotes() {
		assertEquals("select name$tag$, $1, 42",
				SqlText.renderParameters("select name$tag$, $1, ?", Map.of(1, "42"), 100));
		assertEquals("select name$tag$, ?", SqlFingerprint.normalize("SELECT name$tag$, 42"));
	}

	@Test
	void marksTruncationInsideParametersAndQuotedRegions() {
		assertEquals("sele\u2026", SqlText.renderParameters("select ?", Map.of(1, "42"), 4));
		assertEquals("select 12\u2026", SqlText.renderParameters("select ?", Map.of(1, "12345"), 9));
		assertEquals("select 'x\u2026", SqlText.renderParameters("select 'xyz'", Map.of(), 9));
		assertEquals("select 42", SqlText.renderParameters("select ?", Map.of(1, "42"), 9));
		assertEquals("select ?", SqlText.renderParameters("select ?", Map.of(), 100));
		assertThrows(IllegalArgumentException.class, () -> SqlText.renderParameters("select ?", Map.of(), 0));
	}

	@Test
	void findsTheMainOperationAfterCommentsAndCtes() {
		assertEquals("select", SqlText.operation("/* update */ SELECT * from item"));
		assertEquals("update", SqlText.operation("with ids as (select id from item) UPDATE item set active = true"));
		assertEquals("delete",
				SqlText.operation("WITH \"update\"(id) AS (VALUES (1)), other AS (SELECT 2) DELETE FROM item"));
		assertEquals("call", SqlText.operation("call procedure()"));
		assertEquals("", SqlText.operation("-- no SQL"));
		assertEquals("", SqlText.operation(null));
	}

}
