package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.CreateProductRequest;
import com.np.pricehunt.backend.dto.ProductResponse;
import com.np.pricehunt.backend.dto.UpdateProductRequest;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ProductCatalogServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private TrackedItemRepository trackedItemRepository;

    private ProductCatalogService service;

    private Product product;
    private TrackedItem item;

    @BeforeEach
    void setUp() {
        service = new ProductCatalogService(productRepository, trackedItemRepository);
        product = Product.builder().id(1L).name("Laptop").build();
        item = TrackedItem.builder()
                .id(1L)
                .url("https://amazon.com/dp/1")
                .shopName("amazon.com")
                .product(product)
                .build();
    }

    // --- createProduct ---

    @Test
    void createProduct_savesWithoutCountingTheCatalogue() {
        // No global product cap: the catalogue is a shared canonical set, so its size is a capacity
        // question for whoever operates the system, not something to reject a create over (#172).
        when(productRepository.save(any()))
                .thenReturn(Product.builder().id(2L).name("Laptop").build());

        assertThat(service.createProduct(new CreateProductRequest("Laptop")).id())
                .isEqualTo(2L);

        verify(productRepository, never()).count();
    }

    // --- deleteProduct ---

    @Test
    void deleteProduct_notFound_throwsException() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteProduct(99L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(productRepository, never()).delete(any());
    }

    @Test
    void deleteProduct_found_callsDelete() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        service.deleteProduct(1L);

        verify(productRepository).delete(product);
    }

    // --- deleteTrackedItem ---

    @Test
    void deleteTrackedItem_notFound_throwsException() {
        when(trackedItemRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteTrackedItem(1L, 99L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(trackedItemRepository, never()).delete(any());
    }

    @Test
    void deleteTrackedItem_wrongProduct_throwsException() {
        Product other = Product.builder().id(99L).name("Other").build();
        TrackedItem foreignItem = TrackedItem.builder()
                .id(1L)
                .url("http://x.com")
                .shopName("x")
                .product(other)
                .build();
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(foreignItem));

        assertThatThrownBy(() -> service.deleteTrackedItem(1L, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(trackedItemRepository, never()).delete(any());
    }

    @Test
    void deleteTrackedItem_found_callsDelete() {
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(item));

        service.deleteTrackedItem(1L, 1L);

        verify(trackedItemRepository).delete(item);
    }

    // --- updateProduct ---

    @Test
    void updateProduct_notFound_throwsException() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateProduct(99L, new UpdateProductRequest("New", null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateProduct_updatesName() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        ProductResponse response = service.updateProduct(1L, new UpdateProductRequest("New Name", null));

        assertThat(product.getName()).isEqualTo("New Name");
        assertThat(response.name()).isEqualTo("New Name");
    }

    @Test
    void updateProduct_blankName_returns400() {
        assertThatThrownBy(() -> service.updateProduct(1L, new UpdateProductRequest("  ", null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(productRepository, never()).findById(any());
    }

    @Test
    void updateProduct_blankNameWithDescription_returns400_noPartialUpdate() {
        assertThatThrownBy(() -> service.updateProduct(1L, new UpdateProductRequest("  ", "A great laptop")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(productRepository, never()).findById(any());
        assertThat(product.getDescription()).isNull();
    }

    @Test
    void updateProduct_updatesDescription() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        service.updateProduct(1L, new UpdateProductRequest(null, "A great laptop"));

        assertThat(product.getDescription()).isEqualTo("A great laptop");
    }

    @Test
    void updateProduct_returnsLightweightResponse_doesNotFetchTrackedItems() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        service.updateProduct(1L, new UpdateProductRequest("New Name", null));

        verifyNoInteractions(trackedItemRepository);
    }

    @Test
    void updateProduct_clearsDescription_whenEmptyStringPassed() {
        product.setDescription("old description");
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        service.updateProduct(1L, new UpdateProductRequest(null, ""));

        assertThat(product.getDescription()).isNull();
    }

    @Test
    void updateProduct_doesNotCallSave_dirtyCheckingFlushes() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        service.updateProduct(1L, new UpdateProductRequest("New Name", null));

        verify(productRepository, never()).save(any());
    }

    @Test
    void updateProduct_allFieldsNull_returns400() {
        assertThatThrownBy(() -> service.updateProduct(1L, new UpdateProductRequest(null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(productRepository, never()).findById(any());
    }
}
