package ch.rasc.jdbcobserver.demo;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import java.util.ArrayList;
import java.util.List;

@Entity
public class Author {

	@Id
	@GeneratedValue
	private Long id;

	private String name;

	@OneToMany(mappedBy = "author", cascade = CascadeType.ALL, orphanRemoval = true)
	private final List<Book> books = new ArrayList<>();

	protected Author() {
	}

	Author(String name) {
		this.name = name;
	}

	void addBook(String title) {
		this.books.add(new Book(title, this));
	}

	Long getId() {
		return this.id;
	}

	String getName() {
		return this.name;
	}

	List<Book> getBooks() {
		return this.books;
	}

}
