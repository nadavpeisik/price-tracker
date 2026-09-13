package com.np.pricehunt.bff.token;

import com.np.pricehunt.bff.session.SessionAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;

/**
 * Keeps the Auth0 tokens in the session row as a {@link StoredTokens} attribute. The two halves are
 * deliberately asymmetric:
 *
 * <ul>
 *   <li><b>load</b> reads the committed Postgres state (a fresh {@code findById}), never the copy this
 *       request loaded earlier. A missing row means the session was deleted underneath the request
 *       (a concurrent logout or revocation) and the right answer is "no client", never tokens the
 *       request happened to have in memory. On a refresh it is what lets a request that just waited on
 *       another's refresh see the rotated token instead of replaying the old one.
 *   <li><b>save</b> writes through the request's own session, which {@code flush-mode=immediate} sends
 *       to Postgres before {@code setAttribute} returns: durable when the caller's call returns. Spring
 *       Session saves only changed attributes, so a request that never touches this one cannot
 *       overwrite a rotation at end of request.
 *   <li><b>remove</b> drops the attribute from an existing session only; {@code getSession(false)}
 *       because a removal must never resurrect a session.
 * </ul>
 *
 * One indexed {@code SELECT} per proxied call; the #245 stance that one indexed query is not worth a
 * cache applies here too.
 */
@Component
public class SessionStoreAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

    private final SessionRepository<? extends Session> sessions;
    private final ClientRegistrationRepository registrations;

    public SessionStoreAuthorizedClientRepository(
            SessionRepository<? extends Session> sessions, ClientRegistrationRepository registrations) {
        this.sessions = sessions;
        this.registrations = registrations;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
            String clientRegistrationId, Authentication principal, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Session row = sessions.findById(session.getId());
        if (row == null) {
            return null;
        }
        StoredTokens tokens = row.getAttribute(SessionAttributes.TOKENS);
        if (tokens == null || !tokens.principalName().equals(principal.getName())) {
            return null;
        }
        ClientRegistration registration = registrations.findByRegistrationId(clientRegistrationId);
        return (T) tokens.toAuthorizedClient(registration);
    }

    @Override
    public void saveAuthorizedClient(
            OAuth2AuthorizedClient authorizedClient,
            Authentication principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        request.getSession().setAttribute(SessionAttributes.TOKENS, StoredTokens.from(authorizedClient));
    }

    @Override
    public void removeAuthorizedClient(
            String clientRegistrationId,
            Authentication principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(SessionAttributes.TOKENS);
        }
    }
}
