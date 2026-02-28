package com.np.pricehunt.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
		"spring.autoconfigure.exclude=" +
				"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration," +
				"org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration," +
				"org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration," +
				"org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration," +
				"org.springframework.boot.data.jpa.autoconfigure.JpaRepositoriesAutoConfiguration," +
				"org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration," +
				"org.springframework.ai.autoconfigure.ollama.OllamaAutoConfiguration",
		"spring.docker.compose.enabled=false"
})
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
