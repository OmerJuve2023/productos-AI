package com.example.productosai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;

import javax.sql.DataSource;

@Configuration
@Slf4j
public class VectorStoreConfig {
    
    @Value("${spring.ai.vectorstore.pgvector.dimensions:1536}")
    private int dimensions;
    
    @Value("${spring.ai.vectorstore.pgvector.distance-type:COSINE_DISTANCE}")
    private String distanceType;
    
    @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}")
    private String tableName;
    
    /**
     * Configuración del VectorStore con PGVector
     */
    @Bean
    @ConditionalOnBean(EmbeddingModel.class)
    public VectorStore vectorStore(
            JdbcTemplate jdbcTemplate,
            EmbeddingModel embeddingModel) {
        
        log.info("🔧 Configurando PGVectorStore con:");
        log.info("   - Dimensiones: {}", dimensions);
        log.info("   - Tipo de distancia: {}", distanceType);
        log.info("   - Tabla: {}", tableName);
        
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(dimensions)
                .distanceType(PgVectorStore.PgDistanceType.valueOf(distanceType))
                .initializeSchema(true) // Crear tabla automáticamente
                .build();
    }
    
    /**
     * Verificar que pgvector está instalado
     */
    @Bean
    public boolean verificarPgVector(DataSource dataSource) {
        try {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

            String query = "SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'";
            Integer count = jdbcTemplate.queryForObject(query, Integer.class);

            if (count != null && count > 0) {
                log.info("✅ Extensión pgvector está instalada correctamente");
                return true;
            } else {
                log.warn("⚠️ Extensión pgvector NO está instalada. Intentando crear la extensión desde la aplicación...");
                try {
                    // Intentar crear la extensión (requiere privilegios de superuser)
                    jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector;");

                    // Volver a comprobar
                    Integer after = jdbcTemplate.queryForObject(query, Integer.class);
                    if (after != null && after > 0) {
                        log.info("✅ Extensión pgvector creada correctamente desde la aplicación");
                        return true;
                    } else {
                        log.error("❌ No se pudo crear la extensión pgvector automáticamente");
                        log.error("   Si no tienes permisos, ejecuta manualmente en PostgreSQL con un usuario superuser: CREATE EXTENSION vector;");
                        return false;
                    }
                } catch (Exception ce) {
                    log.error("❌ Error creando la extensión pgvector desde la aplicación: {}", ce.getMessage());
                    log.error("   Nota: para crear extensiones se requieren permisos de superuser. Ejecuta manualmente en PostgreSQL: CREATE EXTENSION vector; or use a DB image that includes pgvector (e.g., ankane/pgvector)");
                    return false;
                }
            }

        } catch (Exception e) {
            log.error("❌ Error verificando pgvector: {}", e.getMessage());
            return false;
        }
    }
}