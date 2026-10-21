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
// Spot checks against literals - fine, this is not an object comparison
assertThat(result.getName()).isEqualTo("John Doe");
assertThat(result.getId()).isNotBlank();

// Exception assertions
assertThatThrownBy(() -> service.create(invalid))
    .isInstanceOf(BadRequestException.class)
    .hasMessageContaining("Name is required");
```

### Comparing Two Objects — Plain `isEqualTo` by Default

Comparing two objects field by field is the default failure mode to avoid. It isn't just
verbose: when a field is later added to the record, the test keeps passing while silently not
covering the new field. Nothing fails and nothing flags it, so coverage erodes with every model
change.

Compare the whole object instead. `equals`/`hashCode` are the source of truth for equality in
Java, so plain `isEqualTo` is the default — our API models are overwhelmingly records, whose
generated `equals` covers every component and picks up new ones automatically:

```java
// ❌ BAD - add a field to the record later and this still passes, now covering less
assertThat(actual.modelName()).isEqualTo(request.model());
assertThat(actual.temperature()).isEqualTo(request.temperature());
assertThat(actual.topP()).isEqualTo(request.topP());
assertThat(actual.maxOutputTokens()).isEqualTo(request.maxCompletionTokens());

// ✅ GOOD - new components are compared automatically, via the type's own equals
assertThat(actual).isEqualTo(expected);
```

Prefer records for new models. For Java POJOs/Beans that need equality, implement it with Lombok
— `@Value`, `@Data`, `@EqualsAndHashCode`, in that order of preference — rather than reaching for
reflection-based comparison in the test.

### When to Use `usingRecursiveComparison`

`usingRecursiveComparison` bypasses `equals` and walks fields reflectively via AssertJ internals.
That is the right tool only for **exceptional cases**:

- **Excluding fields** from the comparison (`ignoringFields`).
- **Applying an AssertJ feature** during comparison, such as a custom comparator.
- **The type has no usable `equals`/`hashCode`** — realistically only third-party types we don't
  control. For our own code, fix the model instead.

```java
// ✅ GOOD - exceptional case: server-generated fields must be excluded
assertThat(actual)
    .usingRecursiveComparison()
    .ignoringFields("id", "createdAt", "createdBy", "lastUpdatedAt", "lastUpdatedBy")
    .isEqualTo(expected);
```

Recursive comparison is widespread in this codebase, largely as a side effect of JSON views on
response objects rather than a deliberate default. Existing bare
`usingRecursiveComparison().isEqualTo(...)` calls with no exclusion or comparator are not the
pattern to copy — a plain `isEqualTo` is what those want.

### Partial Comparison — `ignoringFields`, Not Dropped Assertions

When only some fields should match, use recursive comparison and name the exclusions. The
ignore list is then explicit and reviewable, instead of the exclusions being implied by whichever
assertions someone chose not to write.

```java
// ❌ BAD - which fields are deliberately unchecked? Unknowable from the test
assertThat(actual.name()).isEqualTo(expected.name());
assertThat(actual.projectId()).isEqualTo(expected.projectId());

// ✅ GOOD - exclusions are visible and reviewed
assertThat(actual)
    .usingRecursiveComparison()
    .ignoringFields("id", "createdAt", "createdBy", "lastUpdatedAt", "lastUpdatedBy")
    .isEqualTo(expected);
```

Server-generated audit fields (`id`, `createdAt`, `createdBy`, `lastUpdatedAt`, `lastUpdatedBy`)
are the usual exclusions. Hoist a shared list into a constant when a test class repeats it — see
`EXPERIMENT_IGNORED_FIELDS` in `api/resources/utils/resources/ExperimentTestAssertions.java`.

Ignoring a field and then asserting it separately is a deliberate, valid idiom — not a
redundancy — when the field needs different semantics than plain equality:

```java
// ✅ GOOD - lastUpdatedAt is ignored above so each caller can pick == vs isAfter
assertThat(actual)
    .usingRecursiveComparison()
    .ignoringFields(EXPERIMENT_IGNORED_FIELDS)
    .isEqualTo(expected);

assertThat(actual.lastUpdatedAt()).isAfter(expected.lastUpdatedAt());
```

### Comparators, Not Exclusions, for Inexact Types

Inexact types are the second exceptional case: `BigDecimal.equals` is scale-sensitive and doubles
need an epsilon, so plain `isEqualTo` on the whole object is too strict. Reach for recursive
comparison here — but give it a comparator rather than ignoring the field, so the field stays
covered:

```java
// ❌ BAD - the field is now untested
.ignoringFields("totalEstimatedCost")

// ✅ GOOD - still asserted, compared by value
assertThat(actual)
    .usingRecursiveComparison()
    .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
    .withComparatorForFields(StatsUtils::closeToEpsilonComparator, "duration")
    .isEqualTo(expected);
```

### Collections — `containsExactly`, Not Index-by-Index

```java
// ❌ BAD - misses a 4th unexpected element entirely
assertThat(page.content().get(0).name()).isEqualTo("Alice");
assertThat(page.content().get(1).name()).isEqualTo("Bob");
assertThat(page.content().get(2).name()).isEqualTo("Charlie");

// ✅ GOOD - order matters (sorting/pagination tests)
assertThat(page.content())
    .extracting(Entity::getName)
    .containsExactly("Alice", "Bob", "Charlie");

// ✅ GOOD - order is not part of the contract
assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);

// ✅ GOOD - whole objects, order-insensitive nested collections
assertThat(actual)
    .usingRecursiveComparison()
    .ignoringCollectionOrderInFields("feedbackScores", "comments")
    .isEqualTo(expected);
```

`containsExactly` asserts size and content together — `hasSize` plus per-index checks does not,
and lets an extra element through.

`hasSize` **on its own** is weaker still: it asserts a count and nothing about identity, so any
bug that preserves the count passes. This bites hardest on de-duplication, merge, and upsert
tests, where the count is exactly the thing a bug is most likely to keep right:

```java
// ❌ BAD - passes if the wrong revision survived, or if the duplicate was kept
// and the distinct row dropped. Both keep the size at 2.
assertThat(stored).hasSize(2);
assertThat(version.itemsTotal()).isEqualTo(stored.size());

// ✅ GOOD - names the rows that must survive, so a wrong-winner bug fails
assertDatasetItemsInAnyOrder(stored, winningDuplicate, distinctItem);
assertThat(version.itemsTotal()).isEqualTo(stored.size());
```

Asserting a derived counter against `stored.size()` is good — it ties the counter to reality
rather than to a literal — but it is only as strong as the assertion on `stored` itself. Pin the
contents first, then tie the counter to them.

Reach for the assertion helper before the constant. Where a helper class or method already covers
the entity, call it — reusing only its ignore-field constant still leaves the comparator chain
re-derived at each call site, which is what drifts. The helpers live under
`api/resources/utils/`: `TraceAssertions`, `SpanAssertions`, `DatasetItemAssertions`,
`AlertAssertions`, `PromptTestAssertions` and `ExperimentTestAssertions`.

```java
// ❌ BAD - comparator chain re-derived; the next field to ignore has to be found here too
assertThat(actualItems)
    .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS_DATA_ITEM)
    .containsExactlyElementsOf(expectedItems);

// ✅ GOOD - the helper owns both the ignore list and the comparison
assertDatasetItemsInOrder(actualItems, expectedItems);
```

Each helper owns the ignore-field constant for the comparisons it covers, so those comparisons
have exactly one declaration. Never re-declare that list locally and never import it from another
test class: a second copy drifts silently, and the resulting failure reads like a product bug
rather than a stale ignore list.

A call site whose contract genuinely differs may still ignore a different set — but derive it from
the shared constant rather than rebuilding the list, so the base set stays in one place:

```java
// ✅ GOOD - server generates the id, so the expected item cannot pin it
.ignoringFields(ignoredFieldsPlus("id"))

// ❌ BAD - a hand-written list that silently drifts from the shared one
private static final String[] MY_IGNORED_FIELDS = {"id", "createdAt", /* ...9 more... */};
```

Two cases need care when consolidating. A field that one comparison ignores and another asserts is
a real difference, not drift — folding it into the shared list quietly drops coverage, so keep the
narrower set and assert that field explicitly where it matters. And `ignoredFieldsPlus` only fits
where the helper's comparator semantics already apply; a call site needing a different comparison
shape keeps its own chain.

If no helper exists for the entity yet and more than one test class needs the assertion, add one
under `api/resources/utils/` rather than hoisting a constant into a test class.

These `containsExactly*` variants compare elements with the element type's own `equals`, which is
what you want for exact-valued models. When the elements carry `BigDecimal` or `double`, the same
exception that justifies a comparator on a single object applies per element — otherwise a
difference in numeric scale fails the assertion even though the values are equal:

```java
// ✅ GOOD - inexact numeric elements (see TraceAssertions for the real usage)
var config = new RecursiveComparisonConfiguration();
config.ignoreFields(IGNORED_FIELDS_SCORES);
config.registerComparatorForType(BigDecimal::compareTo, BigDecimal.class);

assertThat(actual.feedbackScores())
    .usingRecursiveFieldByFieldElementComparator(config)
    .containsExactlyInAnyOrderElementsOf(expected.feedbackScores());
```

This is the same escalation rule as for single objects, not a separate one: plain
`containsExactly*` by default, element comparator only when an element field can't be compared by
`equals`. `FeedbackScore.value` is a `BigDecimal`, which is why
`api/resources/utils/traces/TraceAssertions.java` needs it — most models don't.

### When Per-Field Assertions Are Still Right

- **Spot checks against literals** — `assertThat(result.getName()).isEqualTo("John Doe")` is not
  an object comparison at all.
- **Non-equality semantics** — `isAfter`, `isNotNull`, `isNotBlank`, `hasMessageContaining`.
- **Deliberately narrow assertions on an ignored field**, as shown above.

The rule targets *object-to-object* field-by-field comparison, not every use of a single-field
assertion. In short: compare whole objects with `isEqualTo`, escalate to
`usingRecursiveComparison` only for the exceptional cases above, and keep per-field assertions for
the cases in this list.

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
