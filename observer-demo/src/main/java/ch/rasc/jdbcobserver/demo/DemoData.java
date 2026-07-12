package ch.rasc.jdbcobserver.demo;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class DemoData implements ApplicationRunner {

	private final AuthorRepository repository;

	DemoData(AuthorRepository repository) {
		this.repository = repository;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments arguments) {
		if (this.repository.count() != 0)
			return;
		for (int authorNumber = 1; authorNumber <= 8; authorNumber++) {
			var author = new Author("Author " + authorNumber);
			for (int bookNumber = 1; bookNumber <= 3; bookNumber++) {
				author.addBook("Book " + authorNumber + "." + bookNumber);
			}
			this.repository.save(author);
		}
	}

}
