package com.np.pricehunt.backend.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.np.pricehunt.backend.dto.CreateInvitationRequest;
import com.np.pricehunt.backend.dto.InvitationResponse;
import com.np.pricehunt.backend.dto.InvitationStatus;
import com.np.pricehunt.backend.exception.NotFoundException;
import com.np.pricehunt.backend.exception.ValidationException;
import com.np.pricehunt.backend.service.InvitationService;
import com.np.pricehunt.backend.service.RegistrationService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The HTTP contract of {@code /api/admin/invitations} (#249); the security posture is the posture test's. */
@WebMvcTest(InvitationAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid/")
class InvitationAdminControllerTest {

    private static final Instant CREATED = Instant.parse("2026-09-15T10:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private InvitationService invitationService;

    @MockitoBean
    private RegistrationService registration;

    private static InvitationResponse pending(long id, String email) {
        return new InvitationResponse(
                id, email, InvitationStatus.PENDING, CREATED, CREATED.plusSeconds(7 * 86_400), null, null);
    }

    @Test
    void invite_is201WithTheInvitation() throws Exception {
        when(invitationService.invite(new CreateInvitationRequest("guest@example.com")))
                .thenReturn(pending(5, "guest@example.com"));

        mvc.perform(post("/api/admin/invitations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"guest@example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.email").value("guest@example.com"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.expiresAt").value("2026-09-22T10:00:00Z"))
                .andExpect(jsonPath("$.redeemedAt").value((Object) null));
    }

    @Test
    void invite_withAMissingEmail_is400ProblemDetail() throws Exception {
        when(invitationService.invite(new CreateInvitationRequest(null)))
                .thenThrow(new ValidationException("email is required"));

        mvc.perform(post("/api/admin/invitations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("email is required"));
    }

    @Test
    void list_isTheServiceOrder() throws Exception {
        when(registration.list()).thenReturn(List.of(pending(2, "b@example.com"), pending(1, "a@example.com")));

        mvc.perform(get("/api/admin/invitations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(2))
                .andExpect(jsonPath("$[1].id").value(1));
    }

    @Test
    void revoke_is204_and404ForAnUnknownId() throws Exception {
        mvc.perform(delete("/api/admin/invitations/7")).andExpect(status().isNoContent());
        verify(registration).revoke(7L);

        doThrow(new NotFoundException("Invitation not found"))
                .when(registration)
                .revoke(eq(8L));
        mvc.perform(delete("/api/admin/invitations/8"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Invitation not found"));
    }
}
