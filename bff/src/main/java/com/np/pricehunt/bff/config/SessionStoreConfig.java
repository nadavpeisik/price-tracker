package com.np.pricehunt.bff.config;

import com.np.pricehunt.bff.session.TolerantSessionAttributeDeserializer;
import java.time.Duration;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * The Spring Session hooks the BFF needs beyond properties: attribute (de)serialization that expires
 * a row it cannot read instead of failing the request, and the cookie serializer itself, so the
 * {@code __Host-} cookie line and the remember-me request attribute are unconditional code rather
 * than an autoconfiguration branch.
 */
@Configuration
public class SessionStoreConfig {

    /**
     * Request attribute, not a session one: its presence on the request at response commit is what makes
     * Spring Session write a persistent cookie. Declared here because this class is what registers the
     * name with the serializer; {@code session/LoginSessionPolicy} is what sets it.
     */
    public static final String REMEMBER_ME_COOKIE_REQUEST_ATTRIBUTE = "bff.rememberMeCookie";

    /**
     * The bean name is the {@code @Qualifier} Spring Session's {@code JdbcHttpSessionConfiguration}
     * picks up. Replaces the default in both directions: the stock {@code Object -> byte[]} converter
     * unchanged, and a {@code byte[] -> Object} converter that turns a deserialization failure into
     * {@code null} + WARN.
     */
    @Bean("springSessionConversionService")
    ConversionService springSessionConversionService() {
        GenericConversionService conversionService = new GenericConversionService();
        conversionService.addConverter(Object.class, byte[].class, new SerializingConverter());
        conversionService.addConverter(byte[].class, Object.class, new TolerantSessionAttributeDeserializer());
        return conversionService;
    }

    /**
     * The same mapping of {@code server.servlet.session.cookie.*} Boot's {@code SessionAutoConfiguration}
     * does, owned here because Boot's is conditional on an embedded server: a MockMvc context reads as a
     * WAR deployment and would silently write {@code SESSION} with no attributes, so the tests could not
     * pin the cookie line. Plus the remember-me hook: when the request carries the attribute at commit,
     * Spring Session writes {@code Max-Age=Integer.MAX_VALUE} (its own design: it relies on session
     * expiry, not cookie expiry); otherwise a session cookie. The server-side bounds are the contract
     * either way.
     */
    @Bean
    DefaultCookieSerializer cookieSerializer(ServerProperties server) {
        Cookie cookie = server.getServlet().getSession().getCookie();
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        PropertyMapper map = PropertyMapper.get();
        map.from(cookie::getName).to(serializer::setCookieName);
        map.from(cookie::getDomain).to(serializer::setDomainName);
        map.from(cookie::getPath).to(serializer::setCookiePath);
        map.from(cookie::getHttpOnly).to(serializer::setUseHttpOnlyCookie);
        map.from(cookie::getSecure).to(serializer::setUseSecureCookie);
        map.from(cookie::getMaxAge).asInt(Duration::getSeconds).to(serializer::setCookieMaxAge);
        map.from(cookie::getSameSite).to(sameSite -> serializer.setSameSite(sameSite.attributeValue()));
        map.from(cookie::getPartitioned).to(serializer::setPartitioned);
        serializer.setRememberMeRequestAttribute(REMEMBER_ME_COOKIE_REQUEST_ATTRIBUTE);
        return serializer;
    }
}
