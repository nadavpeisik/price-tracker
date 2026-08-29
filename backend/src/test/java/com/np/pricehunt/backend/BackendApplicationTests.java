package com.np.pricehunt.backend;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@Disabled("Skipping context load test until infrastructure is fully stable")
@SpringBootTest
// Needed the moment this is re-enabled: application-test.properties supplies the dummy Groq api-key
// that stands in for ${GROQ_API_KEY}, so without the profile the context would fail on the placeholder.
@ActiveProfiles("test")
class BackendApplicationTests {

    @Test
    void contextLoads() {}
}
