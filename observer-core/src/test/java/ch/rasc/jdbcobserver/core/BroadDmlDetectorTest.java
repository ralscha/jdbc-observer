package ch.rasc.jdbcobserver.core;

import static ch.rasc.jdbcobserver.core.BroadDmlDetector.Finding.DELETE_WITHOUT_WHERE;
import static ch.rasc.jdbcobserver.core.BroadDmlDetector.Finding.NONE;
import static ch.rasc.jdbcobserver.core.BroadDmlDetector.Finding.UPDATE_WITHOUT_WHERE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BroadDmlDetectorTest {

	@Test
	void detectsUpdateAndDeleteWithoutWhere() {
		assertEquals(UPDATE_WITHOUT_WHERE, BroadDmlDetector.detect("UPDATE customer SET active = false"));
		assertEquals(DELETE_WITHOUT_WHERE, BroadDmlDetector.detect("delete from audit_log"));
	}

	@Test
	void acceptsUpdateAndDeleteWithSameLevelWhere() {
		assertEquals(NONE, BroadDmlDetector.detect("update customer set active = false where id = 42"));
		assertEquals(NONE, BroadDmlDetector.detect("delete from audit_log where created_at < ? returning id"));
	}

	@Test
	void doesNotMistakeNestedWhereForDmlConstraint() {
		assertEquals(UPDATE_WITHOUT_WHERE, BroadDmlDetector
			.detect("update customer set score = (select max(score) from history where history.id = customer.id)"));
		assertEquals(DELETE_WITHOUT_WHERE,
				BroadDmlDetector.detect("delete from customer using (select id from inactive where age > 90) old"));
	}

	@Test
	void handlesCtesAtTheCorrectStatementLevel() {
		assertEquals(UPDATE_WITHOUT_WHERE, BroadDmlDetector
			.detect("with ids as (select id from customer where active = false) update customer set archived = true"));
		assertEquals(NONE, BroadDmlDetector.detect(
				"with ids as (select id from customer) update customer set archived = true where id in (select id from ids)"));
		assertEquals(DELETE_WITHOUT_WHERE,
				BroadDmlDetector.detect("with removed as (delete from audit_log returning id) select * from removed"));
	}

	@Test
	void ignoresWhereTextOutsideTheStatementClause() {
		assertEquals(UPDATE_WITHOUT_WHERE,
				BroadDmlDetector.detect("update customer set note = 'where id = 1' /* where active */"));
		assertEquals(DELETE_WITHOUT_WHERE, BroadDmlDetector.detect("delete from \"where\""));
	}

	@Test
	void inspectsEachStatementInACompoundExecution() {
		assertEquals(DELETE_WITHOUT_WHERE,
				BroadDmlDetector.detect("update customer set active = false where id = 1; delete from audit_log"));
	}

	@Test
	void ignoresCommandsThatExpressDifferentIntent() {
		assertEquals(NONE, BroadDmlDetector.detect("truncate table audit_log"));
		assertEquals(NONE, BroadDmlDetector.detect("insert into audit_log(message) values ('update where')"));
		assertEquals(NONE, BroadDmlDetector.detect("create trigger touched before update on customer"));
		assertEquals(NONE, BroadDmlDetector.detect("select * from customer"));
		assertEquals(NONE, BroadDmlDetector.detect(""));
		assertEquals(NONE, BroadDmlDetector.detect(null));
	}

	@Test
	void doesNotClassifyTruncatedSqlAsBroadDml() {
		assertEquals(NONE, BroadDmlDetector.detect("update customer set note = ?\u2026"));
	}

}
