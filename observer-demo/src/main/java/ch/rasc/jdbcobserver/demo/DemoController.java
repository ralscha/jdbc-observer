package ch.rasc.jdbcobserver.demo;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/demo")
class DemoController {

	private final DemoService service;

	DemoController(DemoService service) {
		this.service = service;
	}

	@GetMapping
	DemoIndex index() {
		return new DemoIndex("JDBC Observer demo", List.of("/demo/n-plus-one", "/demo/fixed"));
	}

	@GetMapping("/n-plus-one")
	List<DemoService.AuthorSummary> nPlusOne() {
		return this.service.nPlusOne();
	}

	@GetMapping("/fixed")
	List<DemoService.AuthorSummary> fixed() {
		return this.service.fixed();
	}

	record DemoIndex(String name, List<String> endpoints) {
	}

}
