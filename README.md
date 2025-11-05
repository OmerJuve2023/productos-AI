# productos-ai

Proyecto Spring Boot que integra PostgreSQL con la extensión pgvector y Spring AI (OpenAI embeddings) para indexar y consultar información de productos mediante embeddings vectoriales.

Este README explica cómo levantar el servicio y la base de datos (con Docker), cómo configurar la clave de OpenAI, las propiedades importantes del proyecto y ejemplos de uso.

Resumen rápido
- Lenguaje: Java 17
- Framework: Spring Boot 3.5.x
- Dependencias clave: Spring Web, Spring Data JPA, Spring AI (OpenAI + PGVector), Postgres JDBC, Lombok
- Base de datos: PostgreSQL con la extensión pgvector (se incluye docker-compose y un script init.sql)

Checklist (lo que haré/esta documentado)
- [x] Requisitos y dependencias
- [x] Configuración de la base de datos con docker-compose
- [x] Configurar la API key de OpenAI (evitar dejarla en el repo)
- [x] Cómo ejecutar la aplicación localmente
- [x] Rutas/endpoints principales
- [x] Estructura del proyecto y explicación de las clases principales
- [x] Troubleshooting y próximos pasos

Requisitos
- Java 17 (JDK)
- Maven (se incluye wrapper `mvnw`/`mvnw.cmd`)
- Docker + Docker Compose (para ejecutar Postgres con pgvector)
- Una clave de OpenAI para los embeddings (o el servicio equivalente que soporte la configuración en `spring.ai`)

Archivos importantes
- `docker-compose.yml` — Levanta PostgreSQL con la imagen `ankane/pgvector` y monta `init.sql/init.sql`.
- `init.sql/init.sql` — Contiene `CREATE EXTENSION IF NOT EXISTS vector;` y es ejecutado al inicializar el contenedor.
- `src/main/resources/application.properties` — Configuraciones por defecto (DB, Spring AI, pgvector, server).
- `pom.xml` — Dependencias y plugins Maven.

Configuración recomendada (entorno)
1. No dejes la API key en `application.properties` dentro del repositorio. Actualmente el repo contiene una clave en claro; bórrala y usa variable de entorno:
   - OPENAI_API_KEY=<tu_api_key>
2. Variables y propiedades relevantes (se encuentran en `application.properties`):
   - `spring.datasource.url` — URL JDBC a Postgres (por defecto jdbc:postgresql://localhost:5432/productos_db)
   - `spring.datasource.username` / `spring.datasource.password` — credenciales (por defecto admin / admin123)
   - `spring.ai.openai.api-key` — API Key (recomendado: via variable de entorno `OPENAI_API_KEY`)
   - `spring.ai.openai.embedding.options.model` — Modelo de embeddings (por defecto text-embedding-3-small)
   - `spring.ai.vectorstore.pgvector.dimensions` — Dimensiones del embedding (1536 para text-embedding-3-small)
   - `app.index-on-startup` — Si `true` la app puede intentar indexar en startup (por defecto `false` para evitar consumo de cuota)

Levantar el entorno (Docker + DB)
1. Levanta la base de datos con Docker Compose (desde la raíz del proyecto):

```cmd
mvnw.cmd -q -DskipTests package || echo "Omitir build si no deseas compilar antes"
docker-compose up --build
```

2. El `docker-compose.yml` expone Postgres en el puerto 5432 y monta `init.sql/init.sql` para crear la extensión `vector`.

Configurar la clave de OpenAI (recomendado)
- Exporta la variable de entorno `OPENAI_API_KEY` en tu sistema antes de ejecutar la aplicación. En Windows (cmd.exe):

```cmd
setx OPENAI_API_KEY "sk-..."
```

- Alternativamente, puedes establecer `spring.ai.openai.api-key=${OPENAI_API_KEY}` en `application.properties` (la configuración por defecto ya tiene esa opción comentada).

Ejecutar la aplicación
1. Desde la raíz del proyecto (Windows):

```cmd
mvnw.cmd spring-boot:run
```

2. O ejecutar el JAR empaquetado:

```cmd
mvnw.cmd -DskipTests package
java -jar target\productos-ai-0.0.1-SNAPSHOT.jar
```

Endpoints (REST)
La aplicación expone controladores en `com.example.productosai.controller`. Los endpoints principales incluyen (según el código en `ProductoController`):
- GET  /api/productos — listar productos
- GET  /api/productos/{id} — obtener un producto por id
- POST /api/productos — crear un producto (envía JSON con los campos del entity `Producto`)
- Otros endpoints relacionados con búsquedas o reformulación de consultas si están implementados en `ProductoService` / `QueryReformulator`.

Ejemplo de petición para crear un producto
```cmd
curl -X POST http://localhost:8080/api/productos -H "Content-Type: application/json" -d "{\"nombre\":\"Producto A\", \"descripcion\":\"Descripción...\", \"precio\": 12.5 }"
```

Estructura del proyecto y clases clave
- `com.example.productosai.ProductosAiApplication` — Clase principal Spring Boot.
- `controller/ProductoController` — Endpoints REST para CRUD de productos.
- `entity/Producto` — Entidad JPA que representa un producto.
- `repository/ProductoRepository` — Interfaz JPA para acceso a datos.
- `service/ProductoService` — Lógica de negocio y uso del vector-store (indexación / búsqueda por embeddings).
- `service/QueryReformulator` y `service/LlmQueryReformulator` — Utilidades para reformular o normalizar consultas usando LLMs (cache en LlmQueryReformulator).
- `config/VectorStoreConfig` — Configuración del vector store / pgvector.

Pruebas
- Hay pruebas unitarias bajo `src/test/java`. Para ejecutar las pruebas:

```cmd
mvnw.cmd test
```

Buenas prácticas y seguridad
- Nunca comprometas claves de API en el repositorio. El `application.properties` del repo contiene una clave en claro: reemplázala por la variable de entorno y remueve la clave del control de versiones.
- Usa perfiles de Spring (`application-dev.properties`, `application-prod.properties`) para separar credenciales y configuraciones.
- Controla el uso de `app.index-on-startup` si indexar consume cuota de OpenAI.

Problemas comunes / Troubleshooting
- El contenedor Postgres falla en el arranque:
  - Revisa que el volumen `postgres_data` no contenga una instalación previa incompatible. Puedes eliminar el volumen si quieres reiniciar la base de datos:

    ```cmd
    docker-compose down -v
    docker-compose up --build
    ```

- Errores de conexión a la DB:
  - Verifica `spring.datasource.*` y que el contenedor Postgres esté en `healthy`.
  - Ejecuta `pg_isready -h localhost -p 5432 -U admin` en un contenedor o máquina con cliente psql.

- Errores relacionados con OpenAI / embeddings:
  - Verifica la variable `OPENAI_API_KEY` y el modelo configurado.
  - Si recibes errores por límite de cuota, establece `app.index-on-startup=false` y realiza indexaciones manuales.

Próximos pasos recomendados
- Remover la API key del repositorio y documentar cómo inyectarla (variables de entorno / vault).
- Añadir endpoints de ejemplo para búsqueda semántica usando embeddings y ejemplos de payload.
- Añadir pruebas de integración que levanten una base Postgres en memoria o testcontainers para validar flujos de indexación y búsqueda.

Licencia
- Añade la licencia del proyecto aquí si corresponde (MIT, Apache-2.0, etc.).

Contacto
- Añade información de contacto o autores en la sección `developers` del `pom.xml` si deseas.


-- Fin del README --

