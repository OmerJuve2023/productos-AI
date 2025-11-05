package com.example.productosai.controller;

import com.example.productosai.entity.Producto;
import com.example.productosai.service.ProductoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/productos")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class ProductoController {

    private final ProductoService productoService;

    /**
     * 🤖 BÚSQUEDA INTELIGENTE CON IA
     *
     * La IA interpreta tu consulta y encuentra los productos más relevantes.
     *
     * Puedes escribir de forma natural:
     * - "cerrojo gal cinco octavos por dieciseis plg"
     * - "necesito un tornillo de 3/4 por 10"
     * - "tubo pvc de 2 pulgadas"
     * - "martillo stanley"
     *
     * La IA entiende:
     * - Números escritos ("cinco" → 5)
     * - Fracciones ("cinco octavos" → "5/8")
     * - Abreviaturas ("plg" → "pulgadas")
     * - Errores de escritura ("tornio" → "tornillo")
     * - Marcas, medidas, materiales, etc.
     *
     * GET /api/productos/buscar?q=tu consulta&limit=5
     */
    @GetMapping("/buscar")
    public ResponseEntity<?> buscarConIA(
            @RequestParam(name = "q", required = false) String consulta,
            @RequestParam(name = "limit", defaultValue = "5") int limit,
            @RequestParam(name = "threshold", defaultValue = "0.6") double threshold) {

        if (consulta == null || consulta.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "El parámetro 'q' es requerido",
                    "ejemplo", "/api/productos/buscar?q=cerrojo gal 5/8 x 16 pulgadas",
                    "info", "Puedes escribir de forma natural, la IA interpretará tu consulta"
            ));
        }

        log.info("🤖 Nueva búsqueda IA: '{}'", consulta);

        try {
            long startTime = System.currentTimeMillis();

            // La IA hace TODO el trabajo aquí
            List<Producto> resultados = productoService.buscarPorDescripcion(
                    consulta,
                    limit,
                    threshold
            );

            long elapsedTime = System.currentTimeMillis() - startTime;

            Map<String, Object> response = new HashMap<>();
            response.put("consulta", consulta);
            response.put("total", resultados.size());
            response.put("tiempo_ms", elapsedTime);
            response.put("ia_disponible", productoService.isAIAvailable());
            response.put("estrategia_usada", productoService.isAIAvailable() ? "IA Completa" : "Fallback");
            response.put("resultados", resultados);

            if (resultados.isEmpty()) {
                response.put("mensaje",
                        "No se encontraron productos relevantes. " +
                                "Intenta con términos más generales o reduce el threshold."
                );
                response.put("sugerencias", List.of(
                        "Verifica la ortografía",
                        "Usa términos más generales",
                        "Reduce el threshold (ej: threshold=0.4)"
                ));
            } else {
                response.put("mensaje",
                        String.format("✅ Se encontraron %d productos relevantes", resultados.size())
                );
            }

            log.info("✅ Búsqueda completada: {} resultados en {}ms",
                    resultados.size(), elapsedTime);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("❌ Error en búsqueda IA: {}", e.getMessage(), e);

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error al realizar la búsqueda");
            errorResponse.put("detalle", e.getMessage());
            errorResponse.put("tipo_error", e.getClass().getSimpleName());

            // Dar más contexto según el error
            if (e.getMessage() != null) {
                if (e.getMessage().contains("insufficient_quota")) {
                    errorResponse.put("solucion", "La cuota de OpenAI se ha agotado. Verifica tu cuenta.");
                } else if (e.getMessage().contains("api_key")) {
                    errorResponse.put("solucion", "Verifica que OPENAI_API_KEY esté configurada correctamente.");
                } else if (e.getMessage().contains("rate_limit")) {
                    errorResponse.put("solucion", "Has excedido el límite de peticiones. Espera un momento.");
                }
            }

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * 📋 OBTENER TODOS LOS PRODUCTOS
     */
    @GetMapping
    public ResponseEntity<?> obtenerTodos() {
        try {
            List<Producto> productos = productoService.obtenerTodos();
            return ResponseEntity.ok(Map.of(
                    "total", productos.size(),
                    "productos", productos
            ));
        } catch (Exception e) {
            log.error("❌ Error obteniendo productos: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Error al obtener productos",
                    "detalle", e.getMessage()
            ));
        }
    }

    /**
     * 🔄 REINDEXAR PRODUCTOS EN VECTOR STORE
     *
     * Genera nuevos embeddings para todos los productos.
     * Ejecuta esto cuando:
     * - Agregas nuevos productos
     * - Actualizas descripciones
     * - Cambias el contenido de los productos
     */
    @PostMapping("/reindexar")
    public ResponseEntity<?> reindexar() {
        try {
            log.info("🔄 Iniciando reindexación con IA...");
            productoService.indexarTodosLosProductos();

            return ResponseEntity.ok(Map.of(
                    "mensaje", "✅ Reindexación completada exitosamente",
                    "info", "Todos los productos han sido procesados con embeddings de IA"
            ));
        } catch (Exception e) {
            log.error("❌ Error en reindexación: {}", e.getMessage(), e);

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error al reindexar productos");
            errorResponse.put("detalle", e.getMessage());

            if (e.getMessage() != null && e.getMessage().contains("quota")) {
                errorResponse.put("solucion",
                        "La cuota de OpenAI se ha agotado. " +
                                "Los productos no pudieron ser indexados completamente."
                );
            }

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * ℹ️ INFORMACIÓN DE LA API Y ESTADO DE IA
     */
    @GetMapping("/info")
    public ResponseEntity<?> info() {
        Map<String, Object> info = new HashMap<>();
        info.put("nombre", "API de Búsqueda Inteligente de Productos");
        info.put("version", "2.0 - Powered by AI con Fallback");
        info.put("descripcion",
                "API con búsqueda inteligente usando IA y sistema de fallback automático"
        );

        // Estado de la IA
        Map<String, Object> aiStatus = new HashMap<>();
        aiStatus.put("disponible", productoService.isAIAvailable());
        aiStatus.put("estado", productoService.isAIAvailable() ? "✅ Operativa" : "⚠️ Usando fallback");
        aiStatus.put("estrategias", List.of(
                "1. IA Completa (reformulación + vector + re-ranking)",
                "2. Híbrido (heurística + vector + re-ranking)",
                "3. Textual avanzada (sin IA ni vectores)",
                "4. SQL básico (último recurso)"
        ));
        info.put("ia_status", aiStatus);

        Map<String, String> endpoints = new HashMap<>();
        endpoints.put("GET /api/productos/buscar",
                "Búsqueda inteligente con IA (params: q, limit, threshold)");
        endpoints.put("GET /api/productos",
                "Obtener todos los productos");
        endpoints.put("POST /api/productos/reindexar",
                "Reindexar productos con embeddings de IA");
        endpoints.put("GET /api/productos/info",
                "Información de la API");

        info.put("endpoints", endpoints);

        Map<String, String> ejemplos = new HashMap<>();
        ejemplos.put("Búsqueda natural",
                "/api/productos/buscar?q=cerrojo gal cinco octavos por dieciseis plg");
        ejemplos.put("Con fracciones",
                "/api/productos/buscar?q=tornillo 3/4 x 10");
        ejemplos.put("Con marca",
                "/api/productos/buscar?q=martillo stanley");

        info.put("ejemplos", ejemplos);

        info.put("tecnologias", List.of(
                "Spring AI - Integración con OpenAI",
                "Vector Store - Búsqueda semántica con embeddings",
                "ChatGPT - Reformulación de consultas y re-ranking",
                "Text Embeddings - Similitud vectorial"
        ));

        return ResponseEntity.ok(info);
    }

    /**
     * 🏥 HEALTH CHECK DE IA
     *
     * Verifica el estado del sistema de IA
     */
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        Map<String, Object> health = new HashMap<>();

        boolean aiAvailable = productoService.isAIAvailable();

        health.put("status", aiAvailable ? "UP" : "DEGRADED");
        health.put("ia_disponible", aiAvailable);
        health.put("modo", aiAvailable ? "IA Completa" : "Modo Fallback");
        health.put("timestamp", System.currentTimeMillis());

        Map<String, String> capacidades = new HashMap<>();
        capacidades.put("reformulacion_llm", aiAvailable ? "✅" : "⚠️ Fallback heurístico");
        capacidades.put("busqueda_vectorial", aiAvailable ? "✅" : "⚠️ Fallback textual");
        capacidades.put("reranking_llm", aiAvailable ? "✅" : "⚠️ Fallback scoring");
        health.put("capacidades", capacidades);

        Map<String, Object> recomendaciones = new HashMap<>();
        if (!aiAvailable) {
            recomendaciones.put("mensaje", "La IA no está disponible. Usando fallbacks.");
            recomendaciones.put("acciones", List.of(
                    "Verificar OPENAI_API_KEY",
                    "Revisar cuota de OpenAI",
                    "Verificar conexión a internet",
                    "Esperar 1 minuto para reintentos automáticos"
            ));
        } else {
            recomendaciones.put("mensaje", "Sistema operando con IA completa");
        }
        health.put("info", recomendaciones);

        return ResponseEntity.ok(health);
    }
}