package com.example.productosai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class LlmQueryReformulator {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QueryReformulator fallbackReformulator;

    @Value("${OPENAI_API_KEY:}")
    private String openAiKey;

    // Simple cache with TTL
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final long ttlMillis = 24 * 60 * 60 * 1000L; // 24h

    public QueryReformulator.QueryAttributes reformulate(String query) {
        if (query == null || query.isBlank()) return new QueryReformulator.QueryAttributes(null, null, null, null, "", null);
        // cache
        CacheEntry ce = cache.get(query);
        if (ce != null && Instant.now().toEpochMilli() - ce.timestamp < ttlMillis) {
            return ce.attrs;
        }

        if (openAiKey == null || openAiKey.isBlank()) {
            log.warn("OPENAI_API_KEY no configurada: usando reformulador heurístico");
            QueryReformulator.QueryAttributes attrs = fallbackReformulator.reformulate(query);
            cache.put(query, new CacheEntry(attrs));
            return attrs;
        }

        try {
            String prompt = buildPrompt(query);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openAiKey);

            String body = objectMapper.writeValueAsString(Map.of(
                    "model", "gpt-3.5-turbo",
                    "messages", new Object[] {
                            Map.of("role","system","content", buildPrompt(query)),
                            Map.of("role","user","content", "Analiza y devuelve SOLO EL JSON.")
                    },
                    "temperature", 0.0
            ));

            HttpEntity<String> request = new HttpEntity<>(body, headers);
            String resp = restTemplate.postForObject("https://api.openai.com/v1/chat/completions", request, String.class);
            if (resp == null) throw new RuntimeException("Empty response from OpenAI");

            JsonNode root = objectMapper.readTree(resp);
            JsonNode content = root.path("choices").get(0).path("message").path("content");
            String text = content.asText();
            // parse text as JSON
            JsonNode json = null;
            try {
                json = objectMapper.readTree(text);
            } catch (Exception ex) {
                // Sometimes the model returns text with backticks; try to extract JSON substring
                int first = text.indexOf('{');
                int last = text.lastIndexOf('}');
                if (first >= 0 && last > first) {
                    String sub = text.substring(first, last+1);
                    json = objectMapper.readTree(sub);
                } else {
                    throw ex;
                }
            }

            String brand = json.hasNonNull("brand") ? json.get("brand").asText(null) : null;
            String fraction = json.hasNonNull("fraction") ? json.get("fraction").asText(null) : null;
            String size = json.hasNonNull("size") ? json.get("size").asText(null) : null;
            String unit = json.hasNonNull("unit") ? json.get("unit").asText(null) : null;
            String normalized = json.hasNonNull("normalized") ? json.get("normalized").asText("") : "";
            // primary_term or primaryTerm
            String primaryTerm = null;
            if (json.hasNonNull("primary_term")) primaryTerm = json.get("primary_term").asText(null);
            else if (json.hasNonNull("primaryTerm")) primaryTerm = json.get("primaryTerm").asText(null);

            // Siempre intentar inferir primaryTerm a partir de la BD y usarlo si existe.
            try {
                String inferred = fallbackReformulator.inferPrimaryTerm(query);
                if (inferred != null && !inferred.isBlank()) {
                    primaryTerm = inferred; // override LLM
                }
            } catch (Exception ex) {
                // ignore
            }

            QueryReformulator.QueryAttributes attrs = new QueryReformulator.QueryAttributes(brand, fraction, size, unit, normalized, primaryTerm);
            cache.put(query, new CacheEntry(attrs));
            return attrs;
        } catch (Exception e) {
            log.warn("LLM reformulation failed (will fallback): {}", e.getMessage());
            QueryReformulator.QueryAttributes attrs = fallbackReformulator.reformulate(query);
            cache.put(query, new CacheEntry(attrs));
            return attrs;
        }
    }

    private String buildPrompt(String query) {
        return "Eres un parser que EXTRAE Y CORRIGE la consulta de producto. Devuelve SOLO un JSON válido (sin texto adicional) con estas claves: \n" +
                "  - primary_term: (string|null) -> el término principal del producto (ej: 'cerrojo')\n" +
                "  - brand: (string|null)\n" +
                "  - fraction: (string|null) -> fracción en forma '5/8' si aplica\n" +
                "  - size: (string|null) -> número asociado al tamaño (ej: '16')\n" +
                "  - unit: (string|null) -> unidad (ej: 'pulgadas','mm')\n" +
                "  - normalized: (string) -> versión limpiada y normalizada de la consulta\n\n" +
                "Ejemplo de output EXACTO (sin texto adicional):\n" +
                "{\n" +
                "  \"primary_term\": \"cerrojo\",\n" +
                "  \"brand\": \"gal\",\n" +
                "  \"fraction\": \"5/8\",\n" +
                "  \"size\": \"16\",\n" +
                "  \"unit\": \"pulgadas\",\n" +
                "  \"normalized\": \"cerrojo gal 5/8 x 16 pulgadas\"\n" +
                "}\n\nProcesa ahora la entrada y devuelve SOLO el JSON. Entrada: \"" + query + "\"";
    }

    private static class CacheEntry {
        final QueryReformulator.QueryAttributes attrs;
        final long timestamp;
        CacheEntry(QueryReformulator.QueryAttributes a) { this.attrs = a; this.timestamp = Instant.now().toEpochMilli(); }
    }
}
