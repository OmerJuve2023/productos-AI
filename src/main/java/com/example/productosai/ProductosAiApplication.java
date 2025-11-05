package com.example.productosai;

import com.example.productosai.service.ProductoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;

@SpringBootApplication
@Slf4j
public class ProductosAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductosAiApplication.class, args);
    }

    /**
     * Ejecutar al iniciar la aplicación
     */

    @Autowired
    private Environment environment;

    @Value("${app.index-on-startup:true}")
    private boolean indexOnStartup;

    @Bean
    CommandLineRunner inicializar(ProductoService productoService) {
        return args -> {
            log.info("═══════════════════════════════════════════════════════");
            log.info("🚀 INICIANDO SISTEMA DE BÚSQUEDA INTELIGENTE");
            log.info("═══════════════════════════════════════════════════════");

            // Mostrar información del sistema
            mostrarConfiguracion();

            try {
                // Verificar si hay productos en la base de datos
                long totalProductos = productoService.obtenerTodos().size();

                if (totalProductos == 0) {
                    log.warn("⚠️ No hay productos en la base de datos");
                    log.info("💡 Ejecuta el script init.sql o usa POST /api/productos para agregar productos");
                } else {
                    log.info("📦 Productos en base de datos: {}", totalProductos);

                    if (indexOnStartup) {
                        log.info("🔄 Indexando productos en vector store (ejecución asíncrona)...");
                        // Ejecutar indexación en background para no bloquear el arranque ni fallar por errores externos
                        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                            Thread t = new Thread(r, "productos-indexer");
                            t.setDaemon(true);
                            return t;
                        });

                        ex.submit(() -> {
                            try {
                                productoService.indexarTodosLosProductos();
                            } catch (Exception e) {
                                log.error("⚠️ Error en indexación asíncrona: {}", e.getMessage(), e);
                            }
                        });
                        ex.shutdown();
                    } else {
                        log.info("ℹ️ Indexación automática en startup está deshabilitada (app.index-on-startup=false)");
                    }
                }

                log.info("═══════════════════════════════════════════════════════");
                log.info("✅ APLICACIÓN LISTA");
                log.info("═══════════════════════════════════════════════════════");
                mostrarEndpoints();

            } catch (Exception e) {
                log.error("═══════════════════════════════════════════════════════");
                log.error("❌ ERROR AL INICIALIZAR");
                log.error("═══════════════════════════════════════════════════════");
                log.error("Error: {}", e.getMessage());

                if (e.getMessage() != null && e.getMessage().contains("vector")) {
                    log.error("");
                    log.error("🔧 SOLUCIÓN:");
                    log.error("   La extensión pgvector no está instalada en PostgreSQL");
                    log.error("   Conéctate a la base de datos y ejecuta:");
                    log.error("   CREATE EXTENSION vector;");
                    log.error("");
                }

                log.error("═══════════════════════════════════════════════════════");
            }
        };
    }

    private void mostrarConfiguracion() {
        String port = environment.getProperty("server.port", "8080");
        String dbUrl = environment.getProperty("spring.datasource.url", "N/A");
        String aiModel = environment.getProperty("spring.ai.openai.embedding.options.model", "N/A");

        log.info("📋 CONFIGURACIÓN:");
        log.info("   • Puerto: {}", port);
        log.info("   • Base de datos: {}", dbUrl);
        log.info("   • Modelo de embeddings: {}", aiModel);
        log.info("   • Vector dimensions: {}", environment.getProperty("spring.ai.vectorstore.pgvector.dimensions", "1536"));

        // Verificar API Key (sin mostrarla completa)
        String apiKey = environment.getProperty("spring.ai.openai.api-key", "");
        if (apiKey.startsWith("sk-")) {
            log.info("   • OpenAI API Key: Configurada ✓");
        } else {
            log.warn("   • OpenAI API Key: ⚠️ NO CONFIGURADA");
            log.warn("      Edita application.properties y agrega tu API key");
        }
    }

    private void mostrarEndpoints() {
        String port = environment.getProperty("server.port", "8080");
        String baseUrl = "http://localhost:" + port;

        log.info("");
        log.info("📡 ENDPOINTS DISPONIBLES:");
        log.info("");
        log.info("🔍 Búsqueda Semántica:");
        log.info("   GET  {}/api/productos/buscar?q=tu%20consulta&limit=5", baseUrl);
        log.info("");
        log.info("📋 Gestión de Productos:");
        log.info("   GET  {}/api/productos", baseUrl);
        log.info("   GET  {}/api/productos/categoria/{{categoria}}", baseUrl);
        log.info("   POST {}/api/productos", baseUrl);
        log.info("");
        log.info("🔧 Utilidades:");
        log.info("   POST {}/api/productos/reindexar", baseUrl);
        log.info("   GET  {}/api/productos/stats", baseUrl);
        log.info("   GET  {}/api/productos/health", baseUrl);
        log.info("   GET  {}/api/productos/ejemplos", baseUrl);
        log.info("");
        log.info("💡 EJEMPLOS DE BÚSQUEDA:");
        log.info("   curl \"{}/api/productos/buscar?q=necesito%20algo%20para%20hacer%20ejercicio\"", baseUrl);
        log.info("   curl \"{}/api/productos/buscar?q=escuchar%20música%20sin%20ruido\"", baseUrl);
        log.info("");
    }
}
