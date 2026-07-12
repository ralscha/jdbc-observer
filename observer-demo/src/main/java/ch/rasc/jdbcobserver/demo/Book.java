package ch.rasc.jdbcobserver.demo;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

@Entity
public class Book {

	@Id
	@GeneratedValue
	private Long id;

	private String title;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	private Author author;

	protected Book() {
	}

	Book(String title, Author author) {
		this.title = title;
		this.author = author;
	}

	String getTitle() {
		return this.title;
	}

}
