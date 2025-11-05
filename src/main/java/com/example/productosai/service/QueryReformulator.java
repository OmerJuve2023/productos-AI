package com.example.productosai.service;

import com.example.productosai.repository.ProductoRepository;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class QueryReformulator {

    private final ProductoRepository productoRepository;

    // marcas detectadas a partir de la BD
    private Set<String> brandSet = new HashSet<>();

    // tokens frecuentes que representan tipos de productos (lowercase)
    private Set<String> productTypeSet = new HashSet<>();

    // Diccionario pequeño de tipos de producto comunes como último recurso
    private final Set<String> knownProductTypes = new HashSet<>(java.util.Arrays.asList(
            "cerrojo","alicate","tornillo","perno","tuerca","arandela","angulo","bisagra","cerradura","cilindro","cinta","martillo","clavo","destornillador","llave","grifo","valvula"
    ));

    @PostConstruct
    public void init() {
        try {
            // Extraer tokens frecuentes de nombres para construir lista de marcas
            List<String> names = productoRepository.findAll().stream()
                    .map(p -> p.getNombre() == null ? "" : p.getNombre())
                    .collect(Collectors.toList());

            Map<String, Integer> counts = new HashMap<>();
            Pattern tokenPat = Pattern.compile("\\b([A-ZÁÉÍÓÚÑ]{3,})\\b");
            for (String name : names) {
                Matcher m = tokenPat.matcher(name != null ? name : "");
                while (m.find()) {
                    String tk = m.group(1).toUpperCase(Locale.ROOT).trim();
                    counts.put(tk, counts.getOrDefault(tk, 0) + 1);
                }
            }
            // tomar tokens que aparecen al menos en 3 productos como marcas candidatas
            counts.entrySet().stream()
                    .filter(e -> e.getValue() >= 3)
                    .sorted((a,b) -> Integer.compare(b.getValue(), a.getValue()))
                    .map(Map.Entry::getKey)
                    .forEach(brandSet::add);

            // Además construir productTypeSet a partir de tokens en minúsculas (nombres/descr)
            Map<String, Integer> typeCounts = new HashMap<>();
            Pattern tokenAny = Pattern.compile("\\b([a-zA-ZÁÉÍÓÚÑáéíóúñ0-9/\\-]{3,})\\b");
            for (com.example.productosai.entity.Producto p : productoRepository.findAll()) {
                String text = (p.getNombre() == null ? "" : p.getNombre()) + " " + (p.getDescripcion() == null ? "" : p.getDescripcion());
                Matcher m2 = tokenAny.matcher(text);
                while (m2.find()) {
                    String tk = m2.group(1).toLowerCase(Locale.ROOT).trim();
                    typeCounts.put(tk, typeCounts.getOrDefault(tk, 0) + 1);
                }
            }
            productTypeSet = typeCounts.entrySet().stream()
                    .filter(e -> e.getValue() >= 3)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());

            log.info("ℹ️ QueryReformulator detected {} candidate brands and {} product-type tokens", brandSet.size(), productTypeSet.size());
        } catch (Exception e) {
            log.warn("⚠️ No se pudo inicializar brandSet/productTypeSet: {}", e.getMessage());
        }
    }

    @Getter
    public static class QueryAttributes {
        private final String brand;
        private final String fraction;
        private final String size;
        private final String unit;
        private final String primaryTerm;
        private final String normalized;

        public QueryAttributes(String brand, String fraction, String size, String unit, String normalized, String primaryTerm) {
            this.brand = brand;
            this.fraction = fraction;
            this.size = size;
            this.unit = unit;
            this.normalized = normalized;
            this.primaryTerm = primaryTerm;
        }
    }

    public QueryAttributes reformulate(String input) {
        if (input == null) return new QueryAttributes(null, null, null, null, "", null);
        // Detectar brand primero usando la forma original (mayúsculas), para evitar confusiones con abreviaturas
        String orig = input == null ? "" : input;
        String origUpper = orig.toUpperCase(Locale.ROOT);
        String brand = null;
        for (String b : brandSet) {
            if (origUpper.contains(b)) { brand = b; break; }
        }

        String normalized = normalizeText(input);

        // detectar units (si la marca encontrada no es la misma abreviatura)
        String unit = null;
        Matcher u = Pattern.compile("\\b(pulgadas|plg|mm|milimetros|metros|mt|cm|centimetros|gal|galon)\\b").matcher(normalized);
        if (u.find()) {
            String cand = u.group();
            // si la marca detectada coincide con la abreviatura (ej. 'GAL'), no tratar como unidad
            if (!(brand != null && brand.equalsIgnoreCase(cand))) {
                unit = cand.replaceAll("plg", "pulgadas").replaceAll("mm","milimetros").replaceAll("mt","metros");
            }
        }

        // detectar size: número que precede a unit o que sigue a 'x' o 'por'
        String size = null;
        if (unit != null) {
            Matcher sizeAfter = Pattern.compile("(\\b\\d+(?:\\.\\d+)?\\b)\\s+" + Pattern.quote(unit)).matcher(normalized);
            if (sizeAfter.find()) size = sizeAfter.group(1);
        }
        if (size == null) {
            Matcher sizeX = Pattern.compile("\\b\\d+(?:\\/\\d+)?\\b(?=\\s*x\\s*\\d+)|(?<=x\\s*)\\b\\d+\\b").matcher(normalized);
            if (sizeX.find()) size = sizeX.group();
        }

        // detectar fracciones escritas: patrón '<num_word> <den_word>' con tolerancia a errores en el denominador
        String fraction = null;
        Matcher fracM = Pattern.compile("\\b\\d+\\/\\d+\\b").matcher(normalized);
        if (fracM.find()) fraction = fracM.group();
        else {
            // buscar patrones como 'cinco octavos' incluso con errores de escritura
            String[] toks = normalized.split("\\s+");
            java.util.Map<String,String> numbers = new java.util.HashMap<>();
            String[] words = {"cero","uno","dos","tres","cuatro","cinco","seis","siete","ocho","nueve","diez","once","doce","trece","catorce","quince","dieciseis","dieciséis"};
            for (int i=0;i<words.length;i++) numbers.put(words[i], String.valueOf(i));
            java.util.Map<String,Integer> denom = new java.util.HashMap<>();
            denom.put("medio",2); denom.put("mitad",2); denom.put("tercio",3); denom.put("cuarto",4); denom.put("octavo",8); denom.put("octavos",8); denom.put("quinto",5); denom.put("sexto",6); denom.put("septimo",7); denom.put("séptimo",7); denom.put("noveno",9); denom.put("decimo",10);
            for (int i=0;i<toks.length-1;i++) {
                String a = toks[i];
                String b = toks[i+1];
                String numPart = null;
                if (numbers.containsKey(a)) numPart = numbers.get(a);
                else if (a.matches("\\\\d+")) numPart = a;
                if (numPart != null) {
                    String matchedDenom = closeDenom(b, denom.keySet());
                    if (matchedDenom != null) {
                        fraction = numPart + "/" + denom.get(matchedDenom);
                        break;
                    }
                }
            }
            // fallback: detectar 'cinco octabos' (errores tipográficos) mediante tolerancia
        }

        // Si brand no fue detectado antes, intentar detectarlo en normalized
        if (brand == null) {
            for (String b : brandSet) {
                if (normalized.toUpperCase(Locale.ROOT).contains(b)) { brand = b; break; }
            }
        }
        // primaryTerm no disponible en heurístico (se puede deducir con inferPrimaryTerm)
        String inferredPrimary = inferPrimaryTerm(input);
        // fallback: si inferPrimaryTerm no encontró nada, intentar productTypeSet exact match
        if (inferredPrimary == null) {
            String[] tokens = normalized.split("\\s+");
            for (String t : tokens) {
                String clean = t.replaceAll("[^a-z0-9áéíóúñ/\\-]", "").trim();
                if (clean.length() > 2 && productTypeSet.contains(clean)) { inferredPrimary = clean; break; }
            }
        }

        // Si sigue sin inferir, intentar emparejamiento difuso (Levenshtein <= 2) contra productTypeSet
        if (inferredPrimary == null && productTypeSet != null && !productTypeSet.isEmpty()) {
            String[] tokens = normalized.split("\\s+");
            String best = null; int bestDist = Integer.MAX_VALUE;
            for (String t : tokens) {
                String clean = t.replaceAll("[^a-z0-9áéíóúñ/\\-]", "").trim();
                if (clean.length() <= 2) continue;
                for (String cand : productTypeSet) {
                    int d = levenshteinDistance(clean, cand);
                    if (d < bestDist) { bestDist = d; best = cand; }
                }
            }
            if (best != null && bestDist <= 2) inferredPrimary = best;
        }

        // Último recurso: buscar en knownProductTypes (exacto o difuso)
        if (inferredPrimary == null) {
            String[] tokens = normalized.split("\\s+");
            for (String t : tokens) {
                String clean = t.replaceAll("[^a-z0-9áéíóúñ/\\-]", "").trim();
                if (clean.length() <= 2) continue;
                if (knownProductTypes.contains(clean)) { inferredPrimary = clean; break; }
            }
            if (inferredPrimary == null) {
                String best = null; int bestDist = Integer.MAX_VALUE;
                for (String t : tokens) {
                    String clean = t.replaceAll("[^a-z0-9áéíóúñ/\\-]", "").trim();
                    if (clean.length() <= 2) continue;
                    for (String cand : knownProductTypes) {
                        int d = levenshteinDistance(clean, cand);
                        if (d < bestDist) { bestDist = d; best = cand; }
                    }
                }
                if (best != null && bestDist <= 2) inferredPrimary = best;
            }
        }
         return new QueryAttributes(brand, fraction, size, unit, normalized, inferredPrimary);
    }

    /**
     * Inferir un término principal (primary term) a partir de la consulta buscando tokens
     * que aparezcan frecuentemente en la BD (nombre o descripcion). Devuelve null si
     * no se encuentra un candidato con al menos 1 ocurrencia.
     */
    public String inferPrimaryTerm(String input) {
        if (input == null || input.isBlank()) return null;
        String normalized = normalizeText(input);
        String[] toks = normalized.split("\\s+");
        String best = null;
        long bestCount = 0;
        java.util.Set<String> stop = java.util.Set.of("de","x","por","y","con","para","pulgadas","milimetros","metros","centimetros","mm","plg","galon");
        for (String t : toks) {
            String clean = t.replaceAll("[^a-z0-9áéíóúñ/\\-]", "").trim();
            if (clean.length() <= 2) continue;
            if (stop.contains(clean)) continue;
            try {
                org.springframework.data.domain.Page<com.example.productosai.entity.Producto> page = productoRepository.findByNombreContainingIgnoreCaseOrDescripcionContainingIgnoreCase(clean, clean, org.springframework.data.domain.PageRequest.of(0,1));
                long total = page.getTotalElements();
                if (total > bestCount) { bestCount = total; best = clean; }
            } catch (Exception e) {
                // ignore per-token errors
            }
        }
        // require at least 1 occurrence to be considered useful
        return bestCount > 0 ? best : null;
    }

    // Reusar lógica de normalización del servicio (pero implementar aquí para autonomía)
    private String normalizeText(String input) {
        if (input == null) return "";
        String s = input.trim().toLowerCase(Locale.ROOT);

        // abreviaturas
        s = s.replaceAll("\\bplg\\b", "pulgadas");
        s = s.replaceAll("\\bplg\\.\\b", "pulgadas");
        s = s.replaceAll("\\bmm\\b", "milimetros");
        s = s.replaceAll("\\bcm\\b", "centimetros");
        s = s.replaceAll("\\bmt\\b", "metros");
        // NO transformar 'gal' globalmente a 'galon' porque puede ser marca (GAL) en nombres de producto.
        // s = s.replaceAll("\\bgal\\b", "galon");

        s = s.replaceAll("[\\\";,:\\[\\]\\{\\}\\(\\)]", " ");
        s = s.replaceAll("\\s+", " ");

        // números escritos básicos (español) a dígitos
        Map<String,String> numbers = new HashMap<>();
        String[] words = {"cero","uno","dos","tres","cuatro","cinco","seis","siete","ocho","nueve","diez","once","doce","trece","catorce","quince"};
        for (int i=0;i<words.length;i++) numbers.put(words[i], String.valueOf(i));
        for (Map.Entry<String,String> e : numbers.entrySet()) {
            s = s.replaceAll("\\b" + Pattern.quote(e.getKey()) + "\\b", e.getValue());
        }

        // fracciones escritas simples -> 5/8
        Map<String,Integer> denom = new HashMap<>();
        denom.put("medio",2); denom.put("mitad",2);
        denom.put("tercio",3); denom.put("cuarto",4); denom.put("octavo",8); denom.put("octavos",8);
        denom.put("quinto",5); denom.put("sexto",6); denom.put("septimo",7); denom.put("séptimo",7);
        denom.put("noveno",9); denom.put("decimo",10);

        for (Map.Entry<String,Integer> e : denom.entrySet()) {
            String dword = e.getKey(); int d = e.getValue();
            Pattern pat = Pattern.compile("(\\b\\d+\\b|\\b(" + String.join("|", numbers.keySet()) + ")\\b)\\s+" + Pattern.quote(dword));
            Matcher m = pat.matcher(s);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String numPart = m.group(1);
                m.appendReplacement(sb, numPart + "/" + d);
            }
            m.appendTail(sb);
            s = sb.toString();
        }

        s = s.replaceAll("\\s+"," ").trim();
        return s;
    }

    // Devuelve el denominador candidato más cercano (por distancia Levenshtein <=1) o null
    private String closeDenom(String token, java.util.Set<String> denomKeys) {
        if (token == null || token.isBlank()) return null;
        token = token.toLowerCase(Locale.ROOT).replaceAll("[^a-záéíóúñ]", "");
        String best = null; int bestDist = Integer.MAX_VALUE;
        for (String d : denomKeys) {
            int dist = levenshteinDistance(token, d);
            if (dist < bestDist) { bestDist = dist; best = d; }
        }
        if (bestDist <= 1) return best;
        return null;
    }

    private int levenshteinDistance(String s0, String s1) {
        if (s0 == null) s0 = ""; if (s1 == null) s1 = "";
        int len0 = s0.length()+1; int len1 = s1.length()+1;
        int[] cost = new int[len0]; int[] newcost = new int[len0];
        for (int i=0;i<len0;i++) cost[i]=i;
        for (int j=1;j<len1;j++) {
            newcost[0]=j;
            for (int i=1;i<len0;i++) {
                int match = (s0.charAt(i-1)==s1.charAt(j-1))?0:1;
                int opt = Math.min(Math.min(newcost[i-1]+1, cost[i]+1), cost[i-1]+match);
                newcost[i]=opt;
            }
            int[] swap = cost; cost = newcost; newcost = swap;
        }
        return cost[len0-1];
    }
}
