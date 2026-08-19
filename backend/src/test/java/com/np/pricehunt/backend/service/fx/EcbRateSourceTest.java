package com.np.pricehunt.backend.service.fx;

import static com.np.pricehunt.backend.service.fx.FxRateSourceFixtures.props;
import static com.np.pricehunt.backend.service.fx.FxRateSourceFixtures.respondingWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

class EcbRateSourceTest {

    /** A trimmed copy of the live eurofxref-daily.xml, namespaces and single-quoted attributes intact. */
    private static final String ECB_XML =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01" \
            xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
              <gesmes:subject>Reference rates</gesmes:subject>
              <gesmes:Sender><gesmes:name>European Central Bank</gesmes:name></gesmes:Sender>
              <Cube>
                <Cube time='2026-08-17'>
                  <Cube currency='USD' rate='1.1593'/>
                  <Cube currency='JPY' rate='184.59'/>
                  <Cube currency='ILS' rate='3.4214'/>
                </Cube>
              </Cube>
            </gesmes:Envelope>
            """;

    @Test
    void constructs_withTimedRequestFactory() {
        assertThat(new EcbRateSource(RestClient.builder(), props())).isNotNull();
    }

    @Test
    void sourceName_isStable() {
        assertThat(new EcbRateSource(RestClient.builder(), props()).sourceName())
                .isEqualTo("ecb");
    }

    @Test
    void fetchLatest_readsPublicationDateAndEveryCurrencyCube() {
        RateSnapshot snapshot = provider(MediaType.TEXT_XML, ECB_XML).fetchLatest();

        assertThat(snapshot.asOf()).isEqualTo(LocalDate.parse("2026-08-17"));
        assertThat(snapshot.rates())
                .hasSize(3)
                .containsEntry("USD", new BigDecimal("1.1593"))
                .containsEntry("JPY", new BigDecimal("184.59"))
                .containsEntry("ILS", new BigDecimal("3.4214"));
    }

    // The publication date lives on the middle <Cube>, which carries no currency; reading it off the
    // wrong nesting level is the way this parser would silently lose the date.
    @Test
    void fetchLatest_datedCubeIsNotMistakenForARate() {
        assertThat(provider(MediaType.TEXT_XML, ECB_XML).fetchLatest().rates()).doesNotContainKey(null);
    }

    // The shape of eurofxref-hist-90d.xml: many dated cubes, newest first, each carrying the full
    // currency set. Silently accepted, it yields one day's rates under that day's own date — consistent,
    // months old, and indistinguishable downstream from a good snapshot.
    @Test
    void fetchLatest_multipleDatedCubes_throwsRatherThanPickingADay() {
        String history =
                """
                <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01">
                  <Cube>
                    <Cube time='2026-08-17'><Cube currency='ILS' rate='3.4214'/></Cube>
                    <Cube time='2026-08-14'><Cube currency='ILS' rate='3.4100'/></Cube>
                    <Cube time='2026-05-20'><Cube currency='ILS' rate='3.9000'/></Cube>
                  </Cube>
                </gesmes:Envelope>
                """;

        assertThatThrownBy(() -> provider(MediaType.TEXT_XML, history).fetchLatest())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("3 publication dates")
                // Not the wrapper the catch block applies — this document parses fine, it is the wrong one.
                .hasMessageNotContaining("unreadable");
    }

    // A <Cube currency=...> outside the dated cube is not part of the day. The real feed never emits
    // one, but nothing downstream could tell: a stray rate is positive and plausible, so it would be
    // merged into the snapshot and priced against. Position is what distinguishes the two cube kinds.
    @Test
    void fetchLatest_rateOutsideTheDatedCube_isIgnored() {
        String stray =
                """
                <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01">
                  <Cube>
                    <Cube time='2026-08-17'><Cube currency='USD' rate='1.1593'/></Cube>
                    <Cube currency='USD' rate='999'/>
                    <Cube currency='ZZZ' rate='42'/>
                  </Cube>
                </gesmes:Envelope>
                """;

        RateSnapshot snapshot = provider(MediaType.TEXT_XML, stray).fetchLatest();

        // The strays sit AFTER the dated cube on purpose: a parser that collected every currency-bearing
        // Cube would let them win on last-write, so this ordering is what makes the test able to fail.
        assertThat(snapshot.asOf()).isEqualTo(LocalDate.parse("2026-08-17"));
        assertThat(snapshot.rates()).hasSize(1).containsEntry("USD", new BigDecimal("1.1593"));
    }

    @Test
    void fetchLatest_dateless_throws() {
        String noDate =
                """
                <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01">
                  <Cube><Cube><Cube currency='USD' rate='1.1593'/></Cube></Cube>
                </gesmes:Envelope>
                """;

        assertThatThrownBy(() -> provider(MediaType.TEXT_XML, noDate).fetchLatest())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("publication date");
    }

    // An outage answered by a captive portal or an error page is HTML, not XML — it must fail loudly
    // here so FailoverRateProvider sees a failure rather than a snapshot with nothing in it.
    @Test
    void fetchLatest_nonXmlBody_throws() {
        assertThatThrownBy(() -> provider(MediaType.TEXT_HTML, "<html><body>503</body></html>")
                        .fetchLatest())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fetchLatest_blankBody_throws() {
        assertThatThrownBy(() -> provider(MediaType.TEXT_XML, "   ").fetchLatest())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty response body");
    }

    // XXE: the parser must not dereference an external entity, whatever the document asks for.
    @Test
    void fetchLatest_externalEntity_isNotResolved() {
        String xxe =
                """
                <?xml version="1.0"?>
                <!DOCTYPE Envelope [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <Envelope><Cube><Cube time='2026-08-17'>
                  <Cube currency='USD' rate='1.1593'/><Cube currency='&xxe;' rate='1.0'/>
                </Cube></Cube></Envelope>
                """;

        assertThatThrownBy(() -> provider(MediaType.TEXT_XML, xxe).fetchLatest())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unreadable");
    }

    private static EcbRateSource provider(MediaType contentType, String body) {
        return new EcbRateSource(respondingWith(contentType, body), props());
    }
}
