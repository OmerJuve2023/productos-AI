package com.example.productosai.service;

import com.example.productosai.entity.Producto;
import com.example.productosai.repository.ProductoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class ProductoServiceTest {

    private ProductoRepository productoRepository;
    private VectorStore vectorStore;
    private ProductoService productoService;

    @BeforeEach
    void setUp() {
        productoRepository = mock(ProductoRepository.class);
        vectorStore = mock(VectorStore.class);
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class);
        productoService = new ProductoService(productoRepository, vectorStore, chatClientBuilder);
    }

    @Test
    void buscarPorDescripcion_emptyAndNullQuery_returnsEmpty() {
        List<Producto> res1 = productoService.buscarPorDescripcion(null, 5, 0.6);
        List<Producto> res2 = productoService.buscarPorDescripcion("    ", 5, 0.6);

        assertThat(res1).isEmpty();
        assertThat(res2).isEmpty();
    }

    @Test
    void indexarTodosLosProductos_noProducts_doesNotCallVectorStore() {
        when(productoRepository.findAll()).thenReturn(List.of());

        productoService.indexarTodosLosProductos();

        verify(vectorStore, never()).add(anyList());
    }

    @Test
    void indexarProducto_callsVectorStoreAndSetsAIAvailable() {
        Producto p = new Producto(1L, "Martillo", "Descripción", new BigDecimal("10.00"), "Herramientas", 5, null, null);

        // No exception from vector store
        doNothing().when(vectorStore).add(anyList());

        productoService.indexarProducto(p);

        verify(vectorStore, times(1)).add(anyList());
        assertThat(productoService.isAIAvailable()).isTrue();
    }

    @Test
    void buscarPorDescripcion_textualFallback_returnsRepositoryResultsWhenVectorFails() {
        String consulta = "martillo stanley";

        // Vector store similarity search throws to force fallback
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenThrow(new RuntimeException("vector error"));

        // Mock repository textual searches to return a page with one producto
        Producto p = new Producto(1L, "Martillo Stanley", "Martillo de carpintero", new BigDecimal("25.00"), "Herramientas", 10, null, null);
        when(productoRepository.findByNombreContainingIgnoreCaseOrDescripcionContainingIgnoreCase(eq(consulta), eq(consulta), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(p)));

        // Also for normalized searches (keyword/fraction/size) we can return empty pages
        when(productoRepository.findByNombreContainingIgnoreCaseOrDescripcionContainingIgnoreCase(anyString(), anyString(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(p)));

        List<Producto> resultados = productoService.buscarPorDescripcion(consulta, 5, 0.6);

        assertThat(resultados).isNotEmpty();
        assertThat(resultados.get(0).getNombre()).containsIgnoringCase("Martillo");
    }
}
