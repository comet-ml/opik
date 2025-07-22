# Copilot Code Review Instructions

> **Scope:** These guidelines apply only to pull requests affecting `apps/opik-backend`. Other applications have separate instructions.

When Copilot automatically reviews pull requests, use the following guidelines to structure feedback and ensure consistency:

---

## 1. Branch Naming

Branch names should follow this pattern:

```
{GIT_USERNAME}/{JIRA_TICKET}_{CHANGE_SUMMARY}
```

* **GIT\_USERNAME**: Your GitHub username.
* **JIRA\_TICKET**: Use a JIRA ticket ID (e.g., `OPIK-1763`) or `NA` if none.
* **CHANGE\_SUMMARY**: A concise, lowercase, hyphen-separated description of your change.

**Examples:**

```
alice/OPIK-1763_add-login-endpoint
bob/NA_update-readme
```

---

## 2. Pull Request Description

Every PR description must include **Details** and **Testing** sections. The **Resolves** and **Documentation** sections are optional.

### Details (required)

* Clearly explain what the change does and why it was made.
* List the main files or modules modified.
* Note any key design decisions or trade-offs.

### Testing (required)

* Describe how you verified the change (e.g., test commands, manual steps).
* Outline the test scenarios and edge cases covered.
* Mention any automated tests added or run.

### Resolves (optional)

* Provide the URL of the GitHub issue this PR closes:

  ```
  Resolves: https://github.com/your-org/your-repo/issues/123
  ```
* If no issue exists, state:

  ```
  Resolves: NA
  ```

### Documentation (optional)

* List documentation files updated or added (e.g., `docs/configuration.md`).
* Summarize any new documentation or configuration options introduced.

---

## 3. API Endpoint Requirements

Copilot should verify that REST endpoint methods adhere to our validation, documentation, and logging standards:

* **Jakarta Validation**:

  * Controllers must annotate request bodies and query parameters with `@Valid` and appropriate Jakarta validation annotations (e.g., `@NotNull`, `@Size`, `@Pattern`).

* **OpenAPI Documentation**:

  * Each endpoint method must have `@Operation` with:

    * `operationId` matching the Java method name exactly.
  * Example:

    ```java
    ```

@Operation(summary = "Create Widget", operationId = "createWidget")
public Response createWidget(...) {
// your implementation here
return Response.status(Response.Status.CREATED)
.entity(widget)
.build();
}

````

- **Request Body Schema**:
  - Endpoints that accept a JSON payload must include:
    ```java
    @RequestBody(
      content = @Content(
        schema = @Schema(
          implementation = PayloadClass.class
        )
      )
    )
    ```

- **Logging**:
  - Every endpoint should log entry and exit points with clear messages:
    ```java
    log.info("Creating {}", resourceName);
    // ...
    log.info("Created {} with id {}", resourceName, createdId);
    ```

- **Reactive & Non-Blocking**:
  - Application code must be fully reactive and non-blocking, using Project Reactor or equivalent.
  - Methods interacting with MySQL (e.g., repository calls) are allowed to block; controllers and service methods must use reactive signatures.
  - Endpoints should be horizontally scalable without blocking on shared resources.

**Typical controller pattern**:
```java
log.info("Creating experiment items, count '{}'", newRequest.size());
experimentItemService.create(newRequest)
        .contextWrite(ctx -> setRequestContext(ctx, requestContext)) // ensure propagation of request context
        .block();
log.info("Created experiment items, count '{}'", newRequest.size());
````

* **Context Propagation**:

  * Ensure that reactive pipelines propagate the `requestContext` via `.contextWrite(ctx -> setRequestContext(ctx, requestContext))` on all service calls.

---

## 4. Architecture Guidelines

* Ensure separation of concerns between architectural layers:

  * **Resources** → API layer
  * **Services** → Business logic layer
  * **DAO** → Persistence layer
* Use a modular architecture to enable independent development, testing, and deployment

## 5. Service & DAO Implementation Guidelines

* Use dependency and constructor annotations where possible:

  * `@Singleton` for singleton-scoped classes
  * `@RequiredArgsConstructor(onConstructor_ = @Inject)` for auto-generating constructors with injection

* For MySQL access, use JDBI3 with `TransactionTemplate`:

  ```java
  import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
  // in DAO class:
  private final TransactionTemplate transactionTemplate;
  ```

* Inject and use `IdGenerator` for UUID creation:

  ```java
  import com.comet.opik.domain.IdGenerator;
  private final @NonNull IdGenerator idGenerator;
  ```

Use these guidelines to shape Copilot’s code review feedback on Service and DAO implementations.

## 6. Code Quality Guidelines

When reviewing code, verify adherence to the following checklist:

* Code follows established style conventions and includes appropriate documentation (JavaDocs or inline comments).
* Apply design patterns where they improve clarity, maintainability, or performance.
* Eliminate code duplication by factoring common logic into utility classes or methods.
* Ensure code is modular and reusable across components and services.
* Use meaningful and descriptive names for variables, methods, and classes.
* Keep methods short and focused on a single responsibility.
* Use comments to explain complex logic or important design decisions.
* Maintain consistent naming conventions for classes, methods, variables, and constants.
* Avoid hardcoding values; prefer constants or external configuration.
* All changes must pass Spotless (or equivalent) formatting and lint checks.
* Prefer immutable collection factory methods (`Map.of()`, `List.of()`, `Set.of()`) for fixed-size collections.
* When accessing list ends, use helper methods (`getFirst()`, `getLast()`) instead of index-based calls (`get(0)`, `get(size() - 1)`).

## 7. Database Migration Guidelines

When reviewing migration scripts, verify the following:

### ClickHouse Migrations

* Scripts must reside in `src/main/resources/liquibase/db-app-analytics/migrations`.
* Each changeset must begin with `--liquibase formatted sql` and include:

  ```sql
  --changeset <author>:<version>_<name>
  ```
* Include `ON CLUSTER '{cluster}'` in DDL statements; refer to existing scripts for pattern.
* Add only relevant indexes, and explain the rationale in SQL comments.
* End each script with a blank line.
* Provide a rollback with:

  ```sql
  --rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.<table> ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS <column>;
  ```

  or, if no rollback is needed:

  ```sql
  --rollback empty
  ```

### MySQL Migrations

* Scripts must reside in `src/main/resources/liquibase/db-app-state/migrations`.
* Each changeset must begin with `--liquibase formatted sql` and include:

  ```sql
  --changeset <author>:<version>_<name>
  ```
* Add only relevant indexes, and explain the rationale in SQL comments.
* End each script with a blank line.
* Provide a rollback analogous to ClickHouse or `--rollback empty` when appropriate.

Use these guidelines to shape Copilot’s feedback on database migration scripts.

## 8. MySQL Transaction Usage

* Always use transactions for MySQL reads and writes to ensure consistency.
* Preferred pattern using `TransactionTemplate`:

  ```java
  import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
  import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

  try {
      return transactionTemplate.inTransaction(TransactionType.WRITE, handle -> {
          var repository = handle.attach(MyDao.class);
          return repository.myMethod(param1, param2);
      });
  } catch (Exception e) {
      log.error("Transaction failed", e);
      throw e;
  }
  ```
* Replace `TransactionType.WRITE` or `READ_ONLY` based on use case.
* Always log and handle exceptions gracefully within the transaction.
* Avoid performing unrelated logic inside the transaction block.
* For DAOs, use interfaces; if logic is minimal, define inline.
* Models should represent database or business entities.

Use these guidelines to shape Copilot’s feedback on transaction usage and DAO patterns.

## 8. Error Handling Guidelines

When reviewing exception handling and error logic, ensure the following:

* Implement graceful exception handling: catch expected failures and translate them into meaningful error responses.
* Avoid catching broad exceptions (`Exception` or `Throwable`); instead, catch specific exception types relevant to the operation.
* Validate all external inputs and handle validation failures with clear error messages.
* Ensure resources (connections, streams) are properly closed or released in `finally` blocks or try-with-resources.
* Log exceptions with context, but do not expose stack traces or internal details in API responses.
* Use custom exception classes where appropriate to convey domain-specific errors.
* Ensure HTTP status codes match error semantics (e.g., 400 for client errors, 500 for server errors).

## 9. General Project Guidelines

* Follow the layered architecture: **Resources → Services → DAOs → Models**.
* When creating a new component (Resource, Service, DAO, or Model), place it in the appropriate package and layer.
* Review three existing classes in the target layer to align with established patterns and conventions.
* All source files must end with a blank line.
* This project contains many tests; when verifying compilation, tests may be skipped (focus on production code compilation).

## 10. Logging Guidelines

* Use structured, consistent logging throughout the codebase.
* Loggers must use SLF4J and be defined via Lombok's `@Slf4j` annotation on classes:

  ```java
  @Slf4j
  public class MyService {
      // use log.info, log.error, etc.
  }
  ```
* Include context-rich messages and avoid string concatenation (use parameterized logging):

  ```java
  log.info("User {} created order {}", userId, orderId);
  ```
* Ensure logs occur at key points: method entry, significant state changes, and method exit or errors.
* Do not log sensitive information (passwords, tokens, personal data).

Use these guidelines to shape Copilot’s feedback on logging practices.

## 11. Dependency Management Guidelines

* Do not add Java dependencies lightly; always prefer existing libraries in the project.
* Language: Java 21
* Build Tool: Maven
* Approved Frameworks & Libraries:

  * Dropwizard 4.0.12 (REST API)
  * Dropwizard-Guicey (DI)
  * JDBI3 (Database access)
  * Lombok, MapStruct, Jackson
  * OpenTelemetry (Observability)
  * Redisson (Redis), Swagger (API Docs)
  * Liquibase (MySQL + ClickHouse)
  * Testcontainers, WireMock
  * AWS Java SDK, Mustache.java
  * Database Drivers:

    * MySQL Connector/J 9.2.0
    * ClickHouse Java Driver
  * Build Plugins:

    * Maven Surefire, Javadoc, and Source Plugins

Use these guidelines to shape Copilot’s feedback on dependency usage and library choices.

## 12. Testing Guidelines

* **Unit tests** are recommended for specific and important business logic, but not mandatory.
* **Blackbox tests** are required to cover at least the happy path scenarios and enforce contract constraints.
* Use mocks or stubs (e.g., Mockito) where appropriate to isolate components.
* Before creating new tests, check if existing test classes cover the functionality.
* Use PODAM for generating test data to simplify object instantiation.
* Write unit tests with JUnit 5 and use AssertJ for fluent assertions.
* Prefer `@ParameterizedTest` to cover multiple scenarios without duplication.
* When running tests locally or in CI, run specific test classes or methods rather than the entire suite for faster feedback.

Use these guidelines to shape Copilot’s feedback on test coverage and quality.

## 13. Static Analysis & Best Practices

* Ensure the code passes static analysis tools such as Checkstyle, PMD, and SpotBugs without violations.
* Favor immutability: declare fields `final` when possible and avoid mutable static fields.
* Use `Optional<T>` for nullable return values instead of returning `null`.
* Apply early returns to reduce nesting and improve readability.
* Validate method parameters at the beginning of methods using `Objects.requireNonNull` or Jakarta validation.
* Use Streams and lambda expressions judiciously; avoid overly complex stream pipelines.
* Ensure sensitive data is not exposed in logs, exception messages, or serialized forms.
* Avoid empty catch blocks; always handle or log the caught exception.
* Follow SOLID principles: single responsibility, open-closed, Liskov substitution, interface segregation, dependency inversion.
* Favor composition over inheritance where it leads to more flexible designs.
* Document deprecated methods or classes with `@Deprecated` and `@Deprecated(since="...")` comments.
* Keep cyclomatic complexity low; methods exceeding 10 branches should be refactored.
* Ensure no unused imports, variables, or private methods remain in the codebase.

Use these guidelines to shape Copilot’s feedback on static code analysis and best practices.
