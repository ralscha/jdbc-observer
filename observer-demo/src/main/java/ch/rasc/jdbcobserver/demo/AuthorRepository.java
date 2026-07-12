package ch.rasc.jdbcobserver.demo;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface AuthorRepository extends JpaRepository<Author, Long> {

	List<Author> findAllByOrderByIdAsc();

	@EntityGraph(attributePaths = "books")
	@Query("select distinct author from Author author order by author.id")
	List<Author> findAllWithBooks();

}
