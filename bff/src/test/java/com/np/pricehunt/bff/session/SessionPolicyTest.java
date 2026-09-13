package com.np.pricehunt.bff.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.np.pricehunt.bff.config.SecurityConfig;
import com.np.pricehunt.bff.controller.MeController;
import com.np.pricehunt.bff.testsupport.BffIntegrationTest;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

/** Every row of the {@link SessionExpiryFilter} state table, plus the inactivity clamp. */
class SessionPolicyTest extends BffIntegrationTest {

    private static final String SECURITY_CONTEXT_ATTRIBUTE = "SPRING_SECURITY_CONTEXT";

    @Test
    void absoluteExpiryPassed_is401_rowGone_evenThoughInactivityWasBumped() throws Exception {
        Browser browser = login();
        clock.advance(Duration.ofHours(23));
        browser.perform(get(MeController.PATH)).andExpect(status().isOk());
        assertThat(sessionRows()).isEqualTo(1);

        clock.advance(Duration.ofHours(2));
        MvcResult result = browser.perform(get(MeController.PATH)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .anyMatch(header -> header.startsWith(SESSION_COOKIE + "=") && header.contains("Max-Age=0"));
        assertThat(sessionRows()).isZero();
    }

    @Test
    void inactivityBound_isTheSessionsOwn() throws Exception {
        login();
        Integer maxInactive =
                jdbc.queryForObject("SELECT max_inactive_interval FROM bff_sessions.spring_session", Integer.class);
        assertThat(maxInactive).isEqualTo((int) Duration.ofHours(24).toSeconds());
        // Pre-login rows carry the property default instead.
        new Browser().perform(get(SecurityConfig.LOGIN_PATH)).andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForList("SELECT max_inactive_interval FROM bff_sessions.spring_session", Integer.class))
                .containsExactlyInAnyOrder((int) Duration.ofHours(24).toSeconds(), (int)
                        Duration.ofMinutes(30).toSeconds());
    }

    @Test
    void clamp_keepsDatabaseExpiryWithinTheAbsoluteBound() throws Exception {
        Browser browser = login();
        clock.advance(Duration.ofHours(23));
        browser.perform(get(MeController.PATH)).andExpect(status().isOk());

        Long expiryMillis = jdbc.queryForObject("SELECT expiry_time FROM bff_sessions.spring_session", Long.class);
        Instant absolute = (Instant) deserialize(attributeBytes(SessionAttributes.ABSOLUTE_EXPIRES_AT));
        // Spring Session stamps expiry from its own wall clock, so allow the test's real elapsed time.
        assertThat(Instant.ofEpochMilli(expiryMillis)).isBeforeOrEqualTo(absolute.plusSeconds(5));
        Integer maxInactive =
                jdbc.queryForObject("SELECT max_inactive_interval FROM bff_sessions.spring_session", Integer.class);
        assertThat(maxInactive).isLessThanOrEqualTo((int) Duration.ofHours(1).toSeconds());
    }

    @Test
    void unreadableSecurityContext_is401NotA500_rowGone() throws Exception {
        Browser browser = login();
        jdbc.update(
                "UPDATE bff_sessions.spring_session_attributes SET attribute_bytes = ? WHERE attribute_name = ?",
                bytesOfAClassThatNoLongerExists(),
                SECURITY_CONTEXT_ATTRIBUTE);

        browser.perform(get(MeController.PATH)).andExpect(status().isUnauthorized());
        assertThat(sessionRows()).isZero();
    }

    @Test
    void authenticatedRowWithoutAbsoluteBound_isInvalidated() throws Exception {
        Browser browser = login();
        jdbc.update(
                "DELETE FROM bff_sessions.spring_session_attributes WHERE attribute_name = ?",
                SessionAttributes.ABSOLUTE_EXPIRES_AT);
        browser.perform(get(MeController.PATH)).andExpect(status().isUnauthorized());
        assertThat(sessionRows()).isZero();
    }

    @Test
    void preLoginSession_isLeftAloneByAnAnonymousRequest() throws Exception {
        Browser browser = new Browser();
        browser.perform(get(SecurityConfig.LOGIN_PATH)).andExpect(status().is3xxRedirection());
        browser.perform(get("/bff/oauth2/authorization/auth0")).andExpect(status().is3xxRedirection());
        // A second tab hitting /bff/me mid-login must not break the callback.
        browser.perform(get(MeController.PATH)).andExpect(status().isUnauthorized());
        assertThat(sessionRows()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT attribute_name FROM bff_sessions.spring_session_attributes", String.class))
                .anyMatch(name -> name.contains("AUTHORIZATION_REQUEST"));
    }

    /**
     * The producer of an unreadable attribute is a class that no longer exists on the classpath after a
     * deploy. A test cannot serialize a class it lacks, so: serialize a class that does exist, then
     * rename it inside the stream to an equal-length name that does not.
     */
    private static byte[] bytesOfAClassThatNoLongerExists() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ObjectOutputStream objects = new ObjectOutputStream(out)) {
            objects.writeObject(new Vanished());
        }
        String stream = out.toString(StandardCharsets.ISO_8859_1);
        String renamed = stream.replace(
                Vanished.class.getName(), Vanished.class.getName().replace("Vanished", "Vanishe0"));
        assertThat(renamed).isNotEqualTo(stream);
        return renamed.getBytes(StandardCharsets.ISO_8859_1);
    }

    static final class Vanished implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    private static Object deserialize(byte[] bytes) throws Exception {
        try (var in = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes))) {
            return in.readObject();
        }
    }
}
