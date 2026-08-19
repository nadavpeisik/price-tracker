package com.np.pricehunt.backend.service.fx;

import com.np.pricehunt.backend.config.CurrencyProperties;
import com.np.pricehunt.backend.config.RestClientFactories;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads the ECB's daily euro foreign-exchange reference rates straight from the publisher.
 *
 * <p>This is the failover behind {@link FrankfurterRateSource}, and it is deliberately one hop
 * <em>upstream</em> of it rather than a second aggregator: Frankfurter republishes this very file, so
 * the two agree to the last digit while sharing no operator, no account, and no quota. The fallback it
 * replaced was a free commercial tier that quietly moved behind an API key and began answering {@code
 * 200 OK} with an error envelope — leaving the daily refresh with no working second source at all. A
 * central bank's own open data file is the one link in this chain that cannot be repriced.
 *
 * <p>The feed is EUR-base by definition, which is what {@link ExchangeRateService#BASE_CURRENCY}
 * already assumes, so no rebasing arithmetic is involved.
 *
 * <p><b>Runtime precondition.</b> {@code www.ecb.europa.eu} presents a Sectigo Public Server
 * Authentication chain, and those roots entered the JDK truststore only after 21.0.6 — an older or
 * hand-trimmed truststore fails the handshake with {@code PKIX path building failed} and this source
 * is simply absent. That is a real risk for the one component whose whole job is to work when the
 * other one doesn't, so it is stated here rather than discovered on the day it matters. The failure is
 * at least never silent: {@link FailoverRateProvider} logs the provider name with the PKIX message and
 * carries it up as a suppressed exception. Run on a current JDK.
 */
@Slf4j
@Component
public class EcbRateSource implements FxRateSource {

    private static final String SOURCE_NAME = "ecb";
    /** The feed nests {@code <Cube>} three deep: wrapper, one per day, one per currency. */
    private static final String CUBE = "Cube";
    /** Depths of the two {@code <Cube>} kinds carrying data; the wrapper at depth 1 carries none. */
    private static final int DATED_CUBE_DEPTH = 2;

    private static final int RATE_CUBE_DEPTH = 3;

    private final RestClient restClient;
    private final String url;

    public EcbRateSource(RestClient.Builder restClientBuilder, CurrencyProperties properties) {
        // clone() before mutating: RestClient.Builder is a shared Spring bean — see FrankfurterRateSource.
        this.restClient = restClientBuilder
                .clone()
                .requestFactory(RestClientFactories.timed(
                        properties.fx().connectTimeout(), properties.fx().readTimeout(), HttpClient.Version.HTTP_2))
                .build();
        this.url = properties.fx().ecbUrl();
    }

    @Override
    public String sourceName() {
        return SOURCE_NAME;
    }

    @Override
    public RateSnapshot fetchLatest() {
        String xml = restClient.get().uri(url).retrieve().body(String.class);
        if (xml == null || xml.isBlank()) {
            throw new IllegalStateException("ecb returned an empty response body");
        }
        RateSnapshot snapshot = parse(xml);
        log.info("Fetched {} FX rates from {} (asOf={})", snapshot.rates().size(), SOURCE_NAME, snapshot.asOf());
        return snapshot;
    }

    /**
     * Reads the one dated cube and the currency cubes beneath it.
     *
     * <p>Matched on local names so the feed's two namespaces never enter into it, and streamed rather
     * than DOM-parsed so the document is never materialized as a tree. Entity resolution is switched
     * off: this is remote XML, and an ECB outage answered by a captive portal or a hijacked response
     * must not be able to talk the parser into fetching anything.
     *
     * <p>Position is part of the contract, not just the element name. A {@code <Cube>} means different
     * things at different depths, so a rate is read only from inside the dated cube. Otherwise a
     * document that merely contained rate elements somewhere would yield a snapshot assembled from
     * whatever was lying around, and nothing downstream would object: a stray rate is positive and
     * plausible, which is all {@code FailoverRateProvider} inspects.
     */
    private static RateSnapshot parse(String xml) {
        DailyCubes cubes = readCubes(xml);

        if (cubes.asOf == null) {
            throw new IllegalStateException("ecb payload carried no publication date");
        }
        // More than one dated cube means the URL is not the daily feed — most likely someone pointed it
        // at eurofxref-hist-90d.xml. Refuse it, because carrying on is the one outcome nobody would
        // catch: every day in that file carries the full currency set, so the read would end holding one
        // day's rates under that same day's date. Internally consistent, three months old, and accepted
        // by every check downstream. Reading "the newest" instead would work only while the file stays
        // ordered newest-first, an undocumented property of a document we do not control.
        if (cubes.datedCubes > 1) {
            throw new IllegalStateException("ecb payload carried " + cubes.datedCubes
                    + " publication dates — expected the daily feed, which carries exactly one");
        }
        return new RateSnapshot(cubes.asOf, cubes.rates);
    }

    private static DailyCubes readCubes(String xml) {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

        DailyCubes cubes = new DailyCubes();
        XMLStreamReader reader = null;
        // XMLStreamReader is not AutoCloseable (it predates the interface, and its close() throws a
        // checked XMLStreamException), so only the StringReader can be managed here; the reader still
        // needs the finally below.
        try (StringReader in = new StringReader(xml)) {
            reader = factory.createXMLStreamReader(in);
            int depth = 0;
            while (reader.hasNext()) {
                int event = reader.next();
                if (!isCube(reader, event)) {
                    continue;
                }
                if (event == XMLStreamConstants.END_ELEMENT) {
                    depth--;
                } else {
                    cubes.read(reader, ++depth);
                }
            }
        } catch (XMLStreamException | RuntimeException e) {
            throw new IllegalStateException("ecb returned an unreadable FX payload: " + e.getMessage(), e);
        } finally {
            closeQuietly(reader);
        }
        return cubes;
    }

    // getLocalName() throws unless the cursor sits on an element, so the event check has to come first.
    private static boolean isCube(XMLStreamReader reader, int event) {
        return (event == XMLStreamConstants.START_ELEMENT || event == XMLStreamConstants.END_ELEMENT)
                && CUBE.equals(reader.getLocalName());
    }

    /**
     * What one pass over the document found — mutable because a StAX pass is accumulative by nature.
     *
     * <p>Dated cubes are counted rather than rejected on sight: judging the shape belongs in
     * {@link #parse}, outside the catch that turns any {@link RuntimeException} into "unreadable
     * payload", because a multi-day document is perfectly readable and simply is not the one we want.
     */
    private static final class DailyCubes {

        private LocalDate asOf;
        private int datedCubes;
        private final Map<String, BigDecimal> rates = new HashMap<>();

        void read(XMLStreamReader reader, int depth) {
            if (depth == DATED_CUBE_DEPTH) {
                String time = reader.getAttributeValue(null, "time");
                if (time != null) {
                    datedCubes++;
                    if (asOf == null) {
                        asOf = LocalDate.parse(time);
                    }
                }
            } else if (depth == RATE_CUBE_DEPTH) {
                String currency = reader.getAttributeValue(null, "currency");
                String rate = reader.getAttributeValue(null, "rate");
                if (currency != null && rate != null) {
                    rates.put(currency, new BigDecimal(rate));
                }
            }
        }
    }

    // The reader wraps an in-memory StringReader, so a close failure has nothing to leak and must not
    // mask the parse failure that is on its way up.
    private static void closeQuietly(XMLStreamReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (XMLStreamException e) {
            log.debug("Ignoring close failure on the ECB XML reader", e);
        }
    }
}
