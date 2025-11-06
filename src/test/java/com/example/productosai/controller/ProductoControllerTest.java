package com.example.productosai.controller;

import com.example.productosai.entity.Producto;
import com.example.productosai.service.ProductoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class ProductoControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ProductoService productoService;

    @BeforeEach
    void setUp() {
        // Configurar MockMvc en modo standalone para evitar usar @MockBean
        mockMvc = MockMvcBuilders.standaloneSetup(new ProductoController(productoService)).build();
    }

    @Test
    void buscarConIA_missingQuery_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/productos/buscar"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("'q'")));
    }

    @Test
    void buscarConIA_withResults_returnsOkWithResults() throws Exception {
        Producto p = new Producto(1L, "Tornillo", "Tornillo 3/4", new BigDecimal("1.00"), "Ferreteria", 100, null, null);
        when(productoService.buscarPorDescripcion(eq("tornillo"), eq(5), anyDouble()))
                .thenReturn(List.of(p));
        when(productoService.isAIAvailable()).thenReturn(true);

        mockMvc.perform(get("/api/productos/buscar").param("q", "tornillo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(1)))
                .andExpect(jsonPath("$.resultados[0].nombre", containsString("Tornillo")));
    }

    @Test
    void obtenerTodos_returnsList() throws Exception {
        Producto p = new Producto(1L, "Tornillo", "Tornillo 3/4", new BigDecimal("1.00"), "Ferreteria", 100, null, null);
        when(productoService.obtenerTodos()).thenReturn(List.of(p));

        mockMvc.perform(get("/api/productos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(1)))
                .andExpect(jsonPath("$.productos", hasSize(1)));
    }

    @Test
    void reindexar_callsServiceAndReturnsOk() throws Exception {
        // indexarTodosLosProductos no devuelve nada, solo verificar que el endpoint responde OK
        mockMvc.perform(post("/api/productos/reindexar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje", containsString("Reindexación")));
    }
}
