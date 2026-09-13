package com.np.pricehunt.bff.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializationFailedException;

/**
 * Spring Session's default {@code byte[] -> Object} converter, except that an attribute whose bytes
 * no longer deserialize (a class renamed or removed between deploys, a changed
 * {@code serialVersionUID}) becomes {@code null} with a WARN instead of a 500 from whichever filter
 * first reads it. {@link SessionExpiryFilter} then sees an authenticated row with no context, or a
 * context with no bound, and expires it: the user logs in again, which is the epic's "graceful
 * expire-on-failure" decision (#247). The failure is logged once per read; attribute reads are lazy
 * and the row is gone after this request.
 */
public class TolerantSessionAttributeDeserializer implements Converter<byte[], Object> {

    private static final Logger log = LoggerFactory.getLogger(TolerantSessionAttributeDeserializer.class);

    private final DeserializingConverter delegate = new DeserializingConverter();

    @Override
    public Object convert(byte[] source) {
        try {
            return delegate.convert(source);
        } catch (SerializationFailedException e) {
            log.warn(
                    "Session attribute could not be deserialized; the session will be expired: {}",
                    e.getMostSpecificCause().toString());
            return null;
        }
    }
}
