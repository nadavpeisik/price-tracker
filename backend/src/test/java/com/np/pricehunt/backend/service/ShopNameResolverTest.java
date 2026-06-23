package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.np.pricehunt.backend.domain.MappingOrigin;
import com.np.pricehunt.backend.domain.ShopNameMapping;
import com.np.pricehunt.backend.domain.ShopNameSource;
import com.np.pricehunt.backend.repository.ShopNameMappingRepository;
import com.np.pricehunt.backend.service.ShopNameResolver.Resolved;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ShopNameResolverTest {

    @Mock
    private ShopNameMappingRepository mappingRepository;

    @InjectMocks
    private ShopNameResolver resolver;

    private ShopNameMapping mapping(String domain, String name, MappingOrigin origin) {
        return ShopNameMapping.builder()
                .domain(domain)
                .displayName(name)
                .origin(origin)
                .build();
    }

    // --- resolve: mapping tier ---

    @Test
    void resolve_curatedMapping_isMappingAndCurated() {
        when(mappingRepository.findByDomain("amazon.com"))
                .thenReturn(Optional.of(mapping("amazon.com", "Amazon", MappingOrigin.CURATED)));

        Resolved r = resolver.resolve("https://www.amazon.com/dp/1", "ignored proposal");

        assertThat(r.name()).isEqualTo("Amazon");
        assertThat(r.source()).isEqualTo(ShopNameSource.MAPPING);
        assertThat(r.curated()).isTrue();
    }

    @Test
    void resolve_learnedMapping_isMappingNotCurated() {
        when(mappingRepository.findByDomain("thomannmusic.com"))
                .thenReturn(Optional.of(mapping("thomannmusic.com", "Musikhaus Thomann", MappingOrigin.LEARNED)));

        Resolved r = resolver.resolve("https://www.thomannmusic.com/x.htm", null);

        assertThat(r.name()).isEqualTo("Musikhaus Thomann");
        assertThat(r.source()).isEqualTo(ShopNameSource.MAPPING);
        assertThat(r.curated()).isFalse();
    }

    // --- resolve: detected tier ---

    @Test
    void resolve_noMapping_usesDetected() {
        when(mappingRepository.findByDomain("ivory.co.il")).thenReturn(Optional.empty());

        Resolved r = resolver.resolve("https://www.ivory.co.il/catalog.php?id=1", "אייבורי מחשבים וסלולר");

        assertThat(r.name()).isEqualTo("אייבורי מחשבים וסלולר");
        assertThat(r.source()).isEqualTo(ShopNameSource.DETECTED);
        assertThat(r.curated()).isFalse();
    }

    @Test
    void resolve_detectedNameIsNormalized() {
        when(mappingRepository.findByDomain(any())).thenReturn(Optional.empty());

        Resolved r = resolver.resolve("https://example.com/x", "  My  Shop \n ");

        assertThat(r.name()).isEqualTo("My Shop");
        assertThat(r.source()).isEqualTo(ShopNameSource.DETECTED);
    }

    @Test
    void resolve_detectedNameIsCappedAt200() {
        when(mappingRepository.findByDomain(any())).thenReturn(Optional.empty());

        Resolved r = resolver.resolve("https://example.com/x", "a".repeat(250));

        assertThat(r.name()).hasSize(200);
        assertThat(r.source()).isEqualTo(ShopNameSource.DETECTED);
    }

    @Test
    void resolve_detectedNameCapDoesNotSplitSurrogatePair() {
        when(mappingRepository.findByDomain(any())).thenReturn(Optional.empty());

        // 199 ASCII chars + an emoji (a surrogate pair) straddles the 200-char cap; the lone high
        // half must not be kept.
        String emoji = new String(Character.toChars(0x1F600)); // 😀
        Resolved r = resolver.resolve("https://example.com/x", "a".repeat(199) + emoji);

        assertThat(r.name()).isEqualTo("a".repeat(199));
        assertThat(Character.isHighSurrogate(r.name().charAt(r.name().length() - 1)))
                .isFalse();
    }

    // --- resolve: host floor ---

    @Test
    void resolve_noMappingNoDetected_fallsToPrettyHost() {
        when(mappingRepository.findByDomain("super-pharm.co.il")).thenReturn(Optional.empty());

        Resolved r = resolver.resolve("https://shop.super-pharm.co.il/p/1", null);

        assertThat(r.name()).isEqualTo("Super Pharm");
        assertThat(r.source()).isEqualTo(ShopNameSource.HOST_FALLBACK);
    }

    @Test
    void resolve_blankDetected_fallsToHost() {
        when(mappingRepository.findByDomain("example.com")).thenReturn(Optional.empty());

        Resolved r = resolver.resolve("https://www.example.com/x", "   \t  ");

        assertThat(r.source()).isEqualTo(ShopNameSource.HOST_FALLBACK);
        assertThat(r.name()).isEqualTo("Example");
    }

    // --- learn ---

    @Test
    void learn_persistsNormalizedName() {
        resolver.learn("https://www.thomannmusic.com/x.htm", "  Musikhaus  Thomann ");

        verify(mappingRepository).upsertLearned("thomannmusic.com", "Musikhaus Thomann");
    }

    @Test
    void learn_blankName_doesNotUpsert() {
        resolver.learn("https://www.example.com/x", "   ");

        verify(mappingRepository, never()).upsertLearned(any(), any());
    }

    @Test
    void learn_unresolvableDomain_doesNotUpsert() {
        resolver.learn("not a url", "Whatever");

        verify(mappingRepository, never()).upsertLearned(eq("not a url"), any());
        verify(mappingRepository, never()).upsertLearned(any(), any());
    }
}
