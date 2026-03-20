---
name: opik-backend
description: Java backend patterns for Opik. Use when working in apps/opik-backend, designing APIs, database operations, or services.
---

# Opik Backend

## Architecture
- **Layered**: Resource → Service → DAO (never skip layers)
- **DI**: Guice modules, constructor injection with `@Inject`
- **Databases**: MySQL (metadata, transactional) + ClickHouse (analytics, append-only)

## Naming Conventions

### Plural Names (Resources, Tests, URLs, DB Tables)
- **Resource classes**: `TracesResource`, `SpansResource`, `DatasetsResource` (not `TraceResource`)
- **Resource test classes**: `TracesResourceTest`, `SpansResourceTest`, `DatasetsResourceTest` (not `TraceResourceTest`)
- **URL paths**: `/v1/private/traces`, `/v1/private/spans` (not `/v1/private/trace`)
- **DB table names**: `traces`, `spans`, `feedback_scores` (not `trace`, `span`, `feedback_score`)

### Singular Names (DAO, Service)
- **DAO classes**: `TraceDAO`, `SpanDAO`, `DatasetDAO` (not `TracesDAO`)
- **Service classes**: `TraceService`, `SpanService`, `DatasetService` (not `TracesService`)

```java
// ✅ GOOD
@Path("/v1/private/traces")
public class TracesResource { }

// ✅ GOOD - DAO and Service use singular
public class TraceDAO { }
public class TraceService { }

// ✅ GOOD - test classes match plural resource name
public class TracesResourceTest { }

// ❌ BAD - singular test class
public class TraceResourceTest { }

// ❌ BAD - singular resource/URL
@Path("/v1/private/trace")
public class TraceResource { }

// ❌ BAD - plural DAO/Service
public class TracesDAO { }
public class TracesService { }
```

## Lombok Conventions

### Records and DTOs
- Always annotate records/DTOs with `@Builder(toBuilder = true)`
- Add `@NonNull` on all non-optional fields
- Use builders (not constructors) when instantiating records

```java
// ✅ GOOD
@Builder(toBuilder = true)
record MyData(@NonNull UUID id, @NonNull String name, String description) {}

MyData data = MyData.builder()
        .id(id)
        .name(name)
        .build();

// ❌ BAD - plain constructor (positional mistakes, less readable)
new MyData(id, name, null);

// ❌ BAD - @Builder without toBuilder
@Builder
record MyData(UUID id, String name) {}
```

### Dependency Injection
- Use `@RequiredArgsConstructor(onConstructor_ = @Inject)` instead of manual constructors

```java
// ✅ GOOD
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class MyService {
    private final @NonNull DependencyA depA;
    private final @NonNull DependencyB depB;
}

// ❌ BAD - boilerplate constructor
public class MyService {
    private final DependencyA depA;
    @Inject
    public MyService(DependencyA depA) {
        this.depA = depA;
    }
}
```

### Interfaces
- Don't put validation annotations (`@NonNull`) on interface method parameters
- Keep interfaces free of implementation details

```java
// ✅ GOOD
interface MyService {
    void process(String workspaceId, UUID promptId);
}

// ❌ BAD - validation on interface
interface MyService {
    void process(@NonNull String workspaceId, @NonNull UUID promptId);
}
```

## Critical Gotchas

### StringTemplate Memory Leak
```java
// ✅ GOOD
var template = TemplateUtils.newST(QUERY);

// ❌ BAD - causes memory leak via STGroup singleton
var template = new ST(QUERY);
```

### List Access
```java
// ✅ GOOD
users.getFirst()
users.getLast()

// ❌ BAD
users.get(0)
users.get(users.size() - 1)
```

### SQL Text Blocks
```java
// ✅ GOOD - text blocks for multi-line SQL
@SqlQuery("""
        SELECT * FROM datasets
        WHERE workspace_id = :workspace_id
        <if(name)> AND name like concat('%', :name, '%') <endif>
        """)

// ❌ BAD - string concatenation
@SqlQuery("SELECT * FROM datasets " +
        "WHERE workspace_id = :workspace_id " +
        "<if(name)> AND name like concat('%', :name, '%') <endif> ")
```

### Immutable Collections
```java
// ✅ GOOD
Set.of("A", "B", "C")
List.of(1, 2, 3)
Map.of("key", "value")

// ❌ BAD
Arrays.asList("A", "B", "C")
```

## API Design
- **Query parameters that accept lists**: Use plural names from the start (e.g., `exclude_category_names` not `exclude_category_name`). Starting with a singular name and later adding a plural variant results in two redundant query params on the same endpoint. Plural names are backward-compatible since they work for both single and multiple values.

## Error Handling

### Use Jakarta Exceptions
```java
throw new BadRequestException("Invalid input");
throw new NotFoundException("User not found: '%s'".formatted(id));
throw new ConflictException("Already exists");
throw new InternalServerErrorException("System error", cause);
```

### Error Response Classes
- Simple: `io.dropwizard.jersey.errors.ErrorMessage`
- Complex: `com.comet.opik.api.error.ErrorMessage`
- **Never create new error message classes**

## Logging

### Format Convention
```java
// ✅ GOOD - values in single quotes
log.info("Created user: '{}'", userId);
log.error("Failed for workspace: '{}'", workspaceId, exception);

// ❌ BAD - no quotes
log.info("Created user: {}", userId);
```

### Never Log
- Emails, passwords, tokens, API keys
- PII, personal identifiers
- Database credentials

## Reference Files
- [clickhouse.md](clickhouse.md) - ClickHouse query patterns
- [mysql.md](mysql.md) - TransactionTemplate patterns
- [testing.md](testing.md) - PODAM, naming, assertion patterns
- [migrations.md](migrations.md) - Liquibase format for MySQL/ClickHouse
- [permissions.md](permissions.md) - `@RequiredPermissions` annotation guidance for endpoints
