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
