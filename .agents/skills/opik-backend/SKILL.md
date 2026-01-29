---
name: opik-backend
description: Java backend patterns for Opik. Use when working in apps/opik-backend, designing APIs, database operations, or services.
---

# Opik Backend

## Architecture
- **Layered**: Resource → Service → DAO (never skip layers)
- **DI**: Guice modules, constructor injection with `@Inject`
- **Databases**: MySQL (metadata, transactional) + ClickHouse (analytics, append-only)

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

### Immutable Collections
```java
// ✅ GOOD
Set.of("A", "B", "C")
List.of(1, 2, 3)
Map.of("key", "value")

// ❌ BAD
Arrays.asList("A", "B", "C")
```

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
