package com.example.productosai.service;

import com.example.productosai.entity.Producto;
import com.example.productosai.repository.ProductoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductoService {

    private final ProductoRepository productoRepository;
    private final VectorStore vectorStore;
    private final ChatClient.Builder chatClientBuilder;

    // Flag para rastrear si la IA está disponible
    private volatile boolean aiAvailable = true;
    private volatile long lastAiCheckTime = 0;
    private static final long AI_CHECK_INTERVAL = 60000; // 1 minuto

    /**
     * Indexar todos los productos
     */
    @Transactional(readOnly = true)
    public void indexarTodosLosProductos() {
        log.info("🔄 Iniciando indexación de productos...");

        List<Producto> productos = productoRepository.findAll();
        if (productos.isEmpty()) {
            log.warn("⚠️ No hay productos para indexar");
            return;
        }

        final int batchSize = 20;
        final int maxRetries = 3;
        final long initialDelayMs = 2000L;

        List<List<Producto>> batches = new ArrayList<>();
        for (int i = 0; i < productos.size(); i += batchSize) {
            batches.add(productos.subList(i, Math.min(i + batchSize, productos.size())));
        }

        int batchNumber = 0;
        for (List<Producto> batch : batches) {
            batchNumber++;
            List<Document> documents = batch.stream()
                    .map(this::convertirProductoADocument)
                    .collect(Collectors.toList());

            int attempt = 0;
            long delay = initialDelayMs;
            boolean success = false;

            while (attempt <= maxRetries && !success) {
                try {
                    attempt++;
                    vectorStore.add(documents);
                    success = true;
                    aiAvailable = true; // IA funciona
                    log.info("✅ Batch {}/{} indexado ({} docs)", batchNumber, batches.size(), documents.size());
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                    log.warn("⚠️ Error indexando: {}", msg);

                    if (msg.toLowerCase().contains("insufficient_quota") || msg.toLowerCase().contains("quota")) {
                        log.error("❌ Cuota de OpenAI agotada");
                        aiAvailable = false;
                        return;
                    }

                    if (attempt <= maxRetries) {
                        try {
                            Thread.sleep(delay + (long)(Math.random() * 500));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        delay *= 2;
                    } else {
                        aiAvailable = false;
                    }
                }
            }
        }
    }

    /**
     * Indexar un producto individual
     */
    @Transactional
    public void indexarProducto(Producto producto) {
        try {
            Document document = convertirProductoADocument(producto);
            vectorStore.add(List.of(document));
            aiAvailable = true;
            log.debug("✅ Producto indexado: {}", producto.getNombre());
        } catch (Exception e) {
            log.error("❌ Error indexando: {}", e.getMessage());
            aiAvailable = false;
            throw new RuntimeException("Error al indexar producto", e);
        }
    }

    /**
     * 🤖 BÚSQUEDA INTELIGENTE CON SISTEMA DE FALLBACK
     *
     * Estrategia en cascada:
     * 1. Intenta con IA completa (reformulación + vector + re-ranking)
     * 2. Si falla reformulación: usa heurística + vector + re-ranking
     * 3. Si falla vector: usa búsqueda textual avanzada
     * 4. Si todo falla: búsqueda SQL básica
     */
    public List<Producto> buscarPorDescripcion(String consulta, int topK, double similarityThreshold) {
        log.info("🔍 Búsqueda: '{}'", consulta);

        if (consulta == null || consulta.trim().isEmpty()) {
            return List.of();
        }

        int safeTopK = Math.max(1, Math.min(topK, 50));
        double safeThreshold = Math.max(0.0, Math.min(similarityThreshold, 1.0));

        // ESTRATEGIA 1: IA Completa (si está disponible)
        if (shouldTryAI()) {
            try {
                log.info("🤖 Intentando búsqueda con IA completa...");
                List<Producto> resultados = busquedaConIACompleta(consulta, safeTopK, safeThreshold);
                if (!resultados.isEmpty()) {
                    log.info("✅ IA completa: {} resultados", resultados.size());
                    return resultados;
                }
            } catch (Exception e) {
                log.warn("⚠️ IA completa falló: {}", e.getMessage());
                aiAvailable = false;
            }
        }

        // ESTRATEGIA 2: Híbrido (heurística + vector)
        try {
            log.info("🔧 Intentando búsqueda híbrida (heurística + vector)...");
            List<Producto> resultados = busquedaHibrida(consulta, safeTopK, safeThreshold);
            if (!resultados.isEmpty()) {
                log.info("✅ Búsqueda híbrida: {} resultados", resultados.size());
                return resultados;
            }
        } catch (Exception e) {
            log.warn("⚠️ Búsqueda híbrida falló: {}", e.getMessage());
        }

        // ESTRATEGIA 3: Búsqueda textual avanzada (sin IA)
        log.info("📝 Usando búsqueda textual avanzada (sin IA)...");
        List<Producto> resultados = busquedaTextualAvanzada(consulta, safeTopK);
        if (!resultados.isEmpty()) {
            log.info("✅ Búsqueda textual: {} resultados", resultados.size());
            return resultados;
        }

        // ESTRATEGIA 4: Búsqueda SQL básica (último recurso)
        log.info("🔍 Usando búsqueda SQL básica...");
        return busquedaSQLBasica(consulta, safeTopK);
    }

    /**
     * Verificar si debemos intentar usar IA
     */
    private boolean shouldTryAI() {
        long now = System.currentTimeMillis();
        if (now - lastAiCheckTime > AI_CHECK_INTERVAL) {
            // Resetear flag después del intervalo
            lastAiCheckTime = now;
            aiAvailable = true;
        }
        return aiAvailable;
    }

    /**
     * ESTRATEGIA 1: Búsqueda con IA completa
     */
    private List<Producto> busquedaConIACompleta(String consulta, int topK, double threshold) {
        // Reformular con IA
        String consultaMejorada = reformularConsultaConIA(consulta);
        log.info("💡 IA reformuló: '{}' → '{}'", consulta, consultaMejorada);

        // Búsqueda vectorial
        List<Producto> candidatos = buscarVectorial(consultaMejorada, topK * 3, threshold);

        if (candidatos.isEmpty()) {
            candidatos = buscarVectorial(consulta, topK * 3, threshold);
        }

        if (candidatos.isEmpty()) {
            return List.of();
        }

        // Re-ranking con IA
        return rerankearConIA(consulta, candidatos, topK);
    }

    /**
     * ESTRATEGIA 2: Búsqueda híbrida (heurística + vector)
     */
    private List<Producto> busquedaHibrida(String consulta, int topK, double threshold) {
        // Normalizar con heurística (sin LLM)
        QueryNormalized qn = normalizarHeuristica(consulta);
        log.info("🔧 Normalizado: keyword={}, fraction={}, size={}",
                qn.keyword, qn.fraction, qn.size);

        // Intentar búsqueda vectorial con consulta normalizada
        try {
            List<Producto> candidatos = buscarVectorial(qn.textNormalizado, topK * 2, threshold);

            if (!candidatos.isEmpty()) {
                // Re-ranking heurístico (sin LLM)
                return rerankearHeuristico(candidatos, qn, topK);
            }
        } catch (Exception e) {
            log.warn("⚠️ Vector search falló en híbrido: {}", e.getMessage());
        }

        return List.of();
    }

    /**
     * ESTRATEGIA 3: Búsqueda textual avanzada (sin IA ni vectores)
     */
    private List<Producto> busquedaTextualAvanzada(String consulta, int topK) {
        QueryNormalized qn = normalizarHeuristica(consulta);
        Set<Producto> resultados = new LinkedHashSet<>();
        PageRequest page = PageRequest.of(0, topK * 3);

        // 1. Buscar por keyword principal
        if (qn.keyword != null) {
            resultados.addAll(
                    productoRepository.findByNombreContainingIgnoreCaseOrDescripcionContainingIgnoreCase(
                            qn.keyword, qn.keyword, page
                    ).getContent()
            );
        }

        // 2. Buscar por fracción
        if (qn.fraction != null) {
            resultados.addAll(
                    productoRepository.findByNombreContainingIgnoreCaseOrDescripcionContainingIgnoreCase(
                            qn.fraction, qn.fraction, page
                    ).getContent()
            );
        }

        // 3. Buscar por tamaño
        if (qn.size != null) {
            resultados.addAll(
                    productoRepository.findByNombreContainingIgnoreCaseOrDescripcionContainingIgnoreCase(
                            qn.size, qn.size, page
                    ).getContent()
            );
        }

        // 4. Buscar por consulta original
        resultados.addAll(
                productoRepository.findByNombreContainingIgnoreCaseOrDescripcionContainingIgnoreCase(
                        consulta, consulta, page
                ).getContent()
        );

        List<Producto> lista = new ArrayList<>(resultados);
        return rerankearHeuristico(lista, qn, topK);
    }

    /**
     * ESTRATEGIA 4: Búsqueda SQL básica (último recurso)
     */
    private List<Producto> busquedaSQLBasica(String consulta, int topK) {
        PageRequest page = PageRequest.of(0, topK);
        return productoRepository.findByNombreContainingIgnoreCaseOrDescripcionContainingIgnoreCase(
                consulta, consulta, page
        ).getContent();
    }

    /**
     * Normalización heurística (sin LLM)
     */
    private QueryNormalized normalizarHeuristica(String consulta) {
        String texto = consulta.toLowerCase().trim();

        // Detectar fracción
        Pattern fraccionPattern = Pattern.compile("\\b(\\d+)/(\\d+)\\b");
        Matcher fraccionMatcher = fraccionPattern.matcher(texto);
        String fraction = fraccionMatcher.find() ? fraccionMatcher.group(0) : null;

        // Convertir palabras a números
        texto = texto.replaceAll("\\bcinco\\b", "5")
                .replaceAll("\\bocho\\b", "8")
                .replaceAll("\\boctavos?\\b", "8")
                .replaceAll("\\bdieciseis\\b", "16")
                .replaceAll("\\bdieciséis\\b", "16")
                .replaceAll("\\btres\\b", "3")
                .replaceAll("\\bcuatro\\b", "4")
                .replaceAll("\\bdiez\\b", "10");

        // Buscar fracción en palabras: "cinco octavos" → "5/8"
        if (fraction == null) {
            Pattern palabrasFraccion = Pattern.compile("(\\d+)\\s+octavos?");
            Matcher m = palabrasFraccion.matcher(texto);
            if (m.find()) {
                fraction = m.group(1) + "/8";
            }
        }

        // Normalizar unidades
        texto = texto.replaceAll("\\bplgs?\\b", "pulgadas")
                .replaceAll("\\bmm\\b", "milimetros");

        // Detectar tamaño (número antes de unidad o después de 'x')
        Pattern sizePattern = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\s*(?:x|pulgadas|mm|milimetros)");
        Matcher sizeMatcher = sizePattern.matcher(texto);
        String size = sizeMatcher.find() ? sizeMatcher.group(1) : null;

        // Detectar keyword principal (primera palabra significativa)
        Set<String> stopWords = Set.of("de", "del", "la", "el", "los", "con", "sin", "por", "para", "x");
        String[] palabras = texto.split("\\s+");
        String keyword = null;
        for (String p : palabras) {
            if (p.length() > 2 && !stopWords.contains(p) && !p.matches("\\d+")) {
                keyword = p;
                break;
            }
        }

        return new QueryNormalized(texto, keyword, fraction, size);
    }

    /**
     * Re-ranking heurístico (sin LLM)
     */
    private List<Producto> rerankearHeuristico(List<Producto> productos, QueryNormalized qn, int limit) {
        return productos.stream()
                .map(p -> new ProductoScore(p, calcularScoreHeuristico(p, qn)))
                .sorted(Comparator.comparingDouble(ProductoScore::getScore).reversed())
                .limit(limit)
                .map(ProductoScore::getProducto)
                .collect(Collectors.toList());
    }

    /**
     * Calcular score sin IA
     */
    private double calcularScoreHeuristico(Producto p, QueryNormalized qn) {
        double score = 0.0;
        String texto = (p.getNombre() + " " + (p.getDescripcion() != null ? p.getDescripcion() : "")).toLowerCase();

        // Keyword principal
        if (qn.keyword != null && texto.contains(qn.keyword)) {
            score += 10.0;
            if (p.getNombre() != null && p.getNombre().toLowerCase().contains(qn.keyword)) {
                score += 5.0;
            }
        }

        // Fracción exacta
        if (qn.fraction != null && texto.contains(qn.fraction)) {
            score += 15.0;
        }

        // Tamaño
        if (qn.size != null && texto.contains(qn.size)) {
            score += 8.0;
        }

        // Stock disponible
        if (p.getStock() != null && p.getStock() > 0) {
            score += 2.0;
        }

        return score;
    }

    /**
     * Reformular consulta con IA
     */
    private String reformularConsultaConIA(String consulta) {
        try {
            String prompt = """
                Reformula esta consulta para búsqueda de productos de ferretería.
                Normaliza medidas: "cinco octavos" → "5/8", "dieciseis" → "16"
                Expande abreviaturas: "plg" → "pulgadas"
                Corrige errores comunes.
                
                Consulta: "{query}"
                Respuesta (solo la consulta mejorada):
                """;

            ChatClient chatClient = chatClientBuilder.build();
            String resultado = chatClient.prompt()
                    .user(prompt.replace("{query}", consulta))
                    .call()
                    .content();

            aiAvailable = true;
            return resultado != null ? resultado.trim() : consulta;

        } catch (Exception e) {
            log.warn("⚠️ Error reformulando con IA: {}", e.getMessage());
            aiAvailable = false;
            return consulta;
        }
    }

    /**
     * Búsqueda vectorial
     */
    private List<Producto> buscarVectorial(String query, int topK, double threshold) {
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(threshold)
                    .build();

            List<Document> docs = vectorStore.similaritySearch(request);
            aiAvailable = true;

            return docs.stream()
                    .map(doc -> {
                        try {
                            Long id = Long.parseLong(doc.getId());
                            return productoRepository.findById(id).orElse(null);
                        } catch (NumberFormatException e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("⚠️ Error en búsqueda vectorial: {}", e.getMessage());
            aiAvailable = false;
            return List.of();
        }
    }

    /**
     * Re-ranking con IA
     */
    private List<Producto> rerankearConIA(String consulta, List<Producto> productos, int limit) {
        if (productos.isEmpty() || productos.size() <= limit) {
            return productos;
        }

        try {
            StringBuilder productosInfo = new StringBuilder();
            for (int i = 0; i < productos.size(); i++) {
                Producto p = productos.get(i);
                productosInfo.append(String.format(
                        "[%d] %s - %s\n",
                        i, p.getNombre(), p.getDescripcion() != null ? p.getDescripcion() : ""
                ));
            }

            String prompt = """
                Consulta: "{query}"
                Productos:
                {productos}
                
                Selecciona los {limit} más relevantes.
                Responde solo con números separados por comas: 0,3,7
                """;

            ChatClient chatClient = chatClientBuilder.build();
            String respuesta = chatClient.prompt()
                    .user(prompt
                            .replace("{query}", consulta)
                            .replace("{productos}", productosInfo.toString())
                            .replace("{limit}", String.valueOf(limit)))
                    .call()
                    .content();

            if (respuesta != null && !respuesta.trim().isEmpty()) {
                Set<Integer> indices = Arrays.stream(respuesta.trim().split("[,\\s]+"))
                        .map(String::trim)
                        .filter(s -> s.matches("\\d+"))
                        .map(Integer::parseInt)
                        .filter(i -> i >= 0 && i < productos.size())
                        .collect(Collectors.toSet());

                if (!indices.isEmpty()) {
                    aiAvailable = true;
                    return indices.stream()
                            .map(productos::get)
                            .collect(Collectors.toList());
                }
            }

            return productos.stream().limit(limit).collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("⚠️ Error en re-ranking IA: {}", e.getMessage());
            aiAvailable = false;
            return productos.stream().limit(limit).collect(Collectors.toList());
        }
    }

    private Document convertirProductoADocument(Producto producto) {
        String nombre = producto.getNombre() != null ? producto.getNombre() : "";
        String descripcion = producto.getDescripcion() != null ? producto.getDescripcion() : "";
        String categoria = producto.getCategoria() != null ? producto.getCategoria() : "";

        String contenido = String.format(
                "Nombre: %s. Descripción: %s. Categoría: %s. %s %s",
                nombre, descripcion, categoria, nombre.toLowerCase(), descripcion.toLowerCase()
        );

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("id", producto.getId().toString());
        metadata.put("nombre", nombre);
        metadata.put("categoria", categoria);

        return new Document(producto.getId().toString(), contenido, metadata);
    }

    public List<Producto> obtenerTodos() {
        return productoRepository.findAll();
    }

    /**
     * Verificar si la IA está disponible
     */
    public boolean isAIAvailable() {
        return aiAvailable;
    }

    // Clases auxiliares
    private static class QueryNormalized {
        String textNormalizado;
        String keyword;
        String fraction;
        String size;

        QueryNormalized(String text, String keyword, String fraction, String size) {
            this.textNormalizado = text;
            this.keyword = keyword;
            this.fraction = fraction;
            this.size = size;
        }
    }

    private static class ProductoScore {
        private final Producto producto;
        private final double score;

        ProductoScore(Producto producto, double score) {
            this.producto = producto;
            this.score = score;
        }

        Producto getProducto() { return producto; }
        double getScore() { return score; }
    }
}