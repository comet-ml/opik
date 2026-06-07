# Backend Testing Patterns

## Test Data with PODAM

```java
import com.comet.opik.podam.PodamFactoryUtils;

private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

@Test
void createUser() {
    var request = podamFactory.manufacturePojo(UserCreateRequest.class)
        .toBuilder()
        .name("John Doe")  // Override only what matters for test
        .build();

    // ...
}
```

**Utility methods:**
- `PodamFactoryUtils.manufacturePojoList(factory, Class)` - Generate List
- `PodamFactoryUtils.manufacturePojoSet(factory, Class)` - Generate Set

## Test Naming

```java
// ✅ Happy path - same as method name
void createUser() { }

// ✅ Specific scenarios
void createUserWhenValidRequestReturnsUser() { }
void createUserWhenUserExistsReturnsConflict() { }

// ✅ Error paths
void createUserWhenInvalidEmailThrowsBadRequestException() { }

// ❌ Bad
void testCreateUser() { }
void should_create_user() { }
```

## Sorting Test Anti-Pattern

```java
// ❌ BAD - Self-fulfilling prophecy (always passes!)
var actualValues = api.findSorted("name", "ASC");
var expectedValues = new ArrayList<>(actualValues);
expectedValues.sort(Comparator.naturalOrder());
assertThat(actualValues).isEqualTo(expectedValues);

// ✅ GOOD - Test against known data
var page = api.findSorted("name", "ASC");
assertThat(page.content())
    .extracting(Entity::getName)
    .containsExactly("Alice", "Bob", "Charlie");

// ✅ GOOD - Use AssertJ sorting assertions
assertThat(page.content())
    .extracting(Entity::getName)
    .isSorted();

// ✅ GOOD - Compare against independently sorted original
var expectedOrder = originalEntities.stream()
    .sorted(comparator)
    .map(Entity::getId)
    .toList();
assertThat(actualOrder).isEqualTo(expectedOrder);
```

## Sorting / Pagination / Field-Exclusion SQL Changes — Coverage Bar

When you change query SQL that backs **sorting, pagination, or field exclusion** (e.g. the
two-phase `page_ids`/`page_wide` CTEs, deferred wide columns, `EXCEPT`/`exclude_fields`,
`sort_needs_wide`, dynamic `sort_fields`), the test MUST:

- **Assert the whole page content, not just IDs.** Reuse the existing full-page assertion
  helpers (the per-test-class `getAndAssertPage` → `TraceAssertions.assertTraces` /
  `SpanAssertions.assertSpan`) so every field is verified. ID-only assertions are too weak — they can't catch a row that
  returns the right id with wrong/empty data.
- **Cover custom/dynamic `sort_fields`**, not only static columns — sort by a wide text column
  (`input`/`output`/`metadata`) AND by a regular column, in both directions.
- **Cover the sort × field-exclusion combination.** Sorting by a field while excluding that
  same field (and while excluding a *different* wide field) is the case that regresses when the
  deferred-wide-column pre-filter doesn't carry the sort key. Build expected via
  `EXCLUDE_FUNCTIONS.get(field)` and pass the `exclude` set to `getAndAssertPage`.
- **Exercise both spans and traces** — they share the same query shape; a fix on one usually
  needs the mirror test on the other.

```java
// ✅ GOOD - sort × exclude, full-page assertion (deferred-wide path)
var expected = traces.stream().sorted(comparator)
        .map(t -> TraceAssertions.EXCLUDE_FUNCTIONS.get(excludeField).apply(t))
        .toList();
getAndAssertPage(workspaceName, projectName, null, List.of(), traces, expected, List.of(),
        apiKey, List.of(sortingField), Set.of(excludeField));
```

## Parameterized Tests

```java
// ❌ BAD - Duplicate methods
void testSortByNameAsc() { }
void testSortByNameDesc() { }
void testSortByTypeAsc() { }

// ✅ GOOD - Single parameterized test
@ParameterizedTest(name = "Sort by {0} {1}")
@MethodSource("sortingTestCases")
void sortEntities(String field, String direction, Comparator<Entity> comparator) {
    // Single test handles all scenarios
}

static Stream<Arguments> sortingTestCases() {
    return Stream.of(
        Arguments.of("name", "ASC", Comparator.comparing(Entity::getName)),
        Arguments.of("name", "DESC", Comparator.comparing(Entity::getName).reversed())
    );
}
```

## Awaitility - When to Use

```java
// ❌ BAD - MySQL operations are synchronous
Awaitility.await().untilAsserted(() -> {
    var page = client.findAll();
    assertThat(page).hasSize(5);
});

// ✅ GOOD - Direct assertion for sync operations
var page = client.findAll();
assertThat(page).hasSize(5);

// ✅ GOOD - Awaitility only for truly async (Kafka, background jobs)
kafkaProducer.send(message);
Awaitility.await()
    .atMost(5, TimeUnit.SECONDS)
    .untilAsserted(() -> {
        var processed = repository.find(message.getId());
        assertThat(processed).isNotNull();
    });
```

## Assertion Patterns

```java
// Object equality when you have expected object
assertThat(actualUser).isEqualTo(expectedUser);

// Field assertions for specific checks
assertThat(result.getName()).isEqualTo("John Doe");
assertThat(result.getId()).isNotBlank();

// Exception assertions
assertThatThrownBy(() -> service.create(invalid))
    .isInstanceOf(BadRequestException.class)
    .hasMessageContaining("Name is required");
```

## Don't Run Two ClickHouse-Migrating Test Classes in One `mvn` Reactor

Each resource test class that touches ClickHouse runs its own Liquibase migration against the
Testcontainers instance. Running two such classes in a single `mvn` invocation (e.g. spans + traces
together, or a wildcard that matches both) makes the second migration fail with
`REPLICA_ALREADY_EXISTS` (the replicated table from migration `000017` already exists) — a confusing
failure that looks like a product bug but is purely a test-harness collision.

When a change spans both spans and traces (the usual case for shared query SQL), run each class in a
**separate** `mvn` invocation:

```bash
# ✅ GOOD - separate invocations
mvn test -o -Dtest='FindSpansResourceTest$FindSpans#whenFilterSortExcludeAcrossPages*'
mvn test -o -Dtest='GetTracesByProjectResourceTest$FindTraces#getTracesByProject__whenFilterSortExcludeAcrossPages*'

# ❌ BAD - one reactor migrates ClickHouse twice -> REPLICA_ALREADY_EXISTS
mvn test -o -Dtest='FindSpansResourceTest,GetTracesByProjectResourceTest'
```

Surefire selectors for `@Nested` + parameterized tests: use `OuterClass$NestedClass#methodPattern`,
and prefer a `*wildcard*` over the exact (long) method name — exact long names silently match 0 tests.
Combine methods within a class with `+`, classes with `,` (but see the ClickHouse caveat above).
