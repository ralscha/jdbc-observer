package ch.rasc.jdbcobserver.demo;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DemoService {

	private final AuthorRepository repository;

	DemoService(AuthorRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	List<AuthorSummary> nPlusOne() {
		return this.repository.findAllByOrderByIdAsc().stream().map(AuthorSummary::from).toList();
	}

	@Transactional(readOnly = true)
	List<AuthorSummary> fixed() {
		return this.repository.findAllWithBooks().stream().map(AuthorSummary::from).toList();
	}

	record AuthorSummary(Long id, String name, List<String> books) {

		static AuthorSummary from(Author author) {
			return new AuthorSummary(author.getId(), author.getName(),
					author.getBooks().stream().map(Book::getTitle).toList());
		}

	}

}
