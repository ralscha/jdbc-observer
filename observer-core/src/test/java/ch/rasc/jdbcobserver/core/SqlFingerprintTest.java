package ch.rasc.jdbcobserver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SqlFingerprintTest {

	@Test
	void normalizesLiteralValuesWithoutChangingIdentifiers() {
		assertEquals("select * from orders where customer_id = ? and status = ?",
				SqlFingerprint.normalize("SELECT * FROM orders WHERE customer_id = 42 AND status = 'PAID'"));
		assertEquals("select * from orders where customer_id = ? and status = ?",
				SqlFingerprint.normalize("select * from orders where customer_id = 99 and status = 'OPEN'"));
	}

	@Test
	void handlesEscapedQuotesAndDecimalNumbers() {
		assertEquals("insert into products(name, price) values (?, ?)",
				SqlFingerprint.normalize("insert into products(name, price) values ('Bob''s item', 12.50)"));
	}

	@Test
	void keepsArithmeticOperatorsSeparateFromNumericLiterals() {
		assertEquals("select ?+?, ?-?, ?", SqlFingerprint.normalize("select 1+2, 3-4, 5e-2"));
		assertEquals("select ?, ?, ?", SqlFingerprint.normalize("select .5, 0xCAFE, 0b1010"));
	}

	@Test
	void handlesCommentsDollarStringsAndQuotedIdentifiers() {
		assertEquals("select ? as value from \"Case  Sensitive\" where id=?",
				SqlFingerprint.normalize("SELECT $tag$secret$tag$ AS value /* hidden 123 */ FROM \"Case  Sensitive\" "
						+ "WHERE id=7 -- another 99\n"));
		assertEquals("select ?", SqlFingerprint.normalize("select $$a very long value$$"));
	}

	@Test
	void normalizesArrayElementsWithoutChangingBracketIdentifiers() {
		assertEquals("select array[?, ?], array [[?, ?], [?, ?]], [Order 42]",
				SqlFingerprint.normalize("SELECT ARRAY[1, 2], ARRAY [[3, 4], [5, 6]], [Order 42]"));
		assertEquals("select array[?, ?]", SqlFingerprint.normalize("select array['first', 'second']"));
		assertEquals("select[123] from[Order 42]", SqlFingerprint.normalize("SELECT[123] FROM[Order 42]"));
	}

	@Test
	void normalizesOracleAlternativeQuotedLiterals() {
		assertEquals("select ?, ?, ?, ?, ?, ? from dual", SqlFingerprint.normalize(
				"select q'[Bob's ?]', Q'{it's ?}', q'(it's ?)', q'<it's ?>', q'!it's ?!', nq'[it's ?]' from dual"));
	}

}
