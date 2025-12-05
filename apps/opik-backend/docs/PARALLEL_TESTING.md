# Parallel Test Execution

## Overview

This document describes the parallel test execution strategy implemented for the Opik backend tests.

## Configuration

### Maven Surefire Plugin

The `pom.xml` configures the Maven Surefire plugin for parallel test execution:

- **`parallel=classes`**: Test classes run in parallel, but tests within a class run sequentially
- **`threadCount=2`**: Uses 2 threads per CPU core
- **`perCoreThreadCount=true`**: Thread count is multiplied by available CPU cores
- **`forkCount=1`** and **`reuseForks=true`**: Reuse a single JVM fork for efficiency
- **`argLine=-Xmx2g`**: Each test class has 2GB of memory

### JUnit Platform Properties

The `src/test/resources/junit-platform.properties` file configures JUnit 5 parallel execution:

- **`junit.jupiter.execution.parallel.enabled=true`**: Enable parallel execution
- **`junit.jupiter.execution.parallel.mode.default=same_thread`**: Tests within a class run sequentially
- **`junit.jupiter.execution.parallel.mode.classes.default=concurrent`**: Top-level classes run in parallel
- **`junit.jupiter.execution.parallel.config.strategy=dynamic`**: Dynamic thread pool based on CPU cores
- **`junit.jupiter.execution.timeout.default=10m`**: Global timeout to prevent hanging tests

## Test Class Design

### @TestInstance(PER_CLASS)

Most test classes use `@TestInstance(TestInstance.Lifecycle.PER_CLASS)`, which means:

- A single test class instance is created and shared across all test methods
- `@BeforeAll` and `@AfterAll` methods can be non-static
- Shared state within a class is preserved between tests

**Parallel execution compatibility**: Tests within each class run sequentially, so `PER_CLASS` behavior is preserved.

### Shared Resources

The main shared resources are:

1. **Testcontainers** (MySQL, ClickHouse, Redis, MinIO, Zookeeper)
   - Containers use **`withReuse(true)`** to reuse containers across test classes
   - Testcontainers automatically handles concurrent access with unique container IDs
   - Shutdown hooks ensure proper cleanup

2. **Dropwizard Application**
   - Each test class creates its own Dropwizard app extension
   - Uses unique ports per test class (Dropwizard auto-assigns available ports)

3. **WireMock Servers**
   - Each test class creates its own WireMock server instance
   - Auto-assigned ports prevent conflicts

## Resource Isolation Strategy

### Container Reuse

Testcontainers with `withReuse(true)` share the same underlying Docker containers across test classes. This provides:

- **Performance**: Containers are started once and reused
- **Isolation**: Each test class connects with its own connection pool
- **Safety**: Testcontainers uses unique internal identifiers to prevent conflicts

### Database Isolation

Each test class:
- Creates unique projects/workspaces using `UUID.randomUUID()`
- Cleans up data in `@AfterEach` or `@AfterAll` methods
- Uses transactions where possible for isolation

### Network Isolation

- ClickHouse and Zookeeper share a static `Network.newNetwork()` instance
- Testcontainers handles network isolation automatically
- No manual network name management needed

## Best Practices

### Writing Parallel-Safe Tests

1. **Use unique identifiers**: Always use `UUID.randomUUID()` for projects, traces, experiments, etc.
2. **Clean up resources**: Always clean up in `@AfterEach` or `@AfterAll`
3. **Avoid global state**: Don't rely on static mutable state shared across classes
4. **Use test-scoped containers**: Each test class should create its own Dropwizard app

### Debugging Parallel Test Issues

If tests fail intermittently in parallel mode:

1. **Run sequentially**: Use `mvn test -DforkCount=1 -DthreadCount=1` to disable parallelism
2. **Check for shared state**: Look for static mutable variables or shared resources
3. **Verify cleanup**: Ensure `@AfterEach` and `@AfterAll` methods clean up properly
4. **Check resource conflicts**: Look for hardcoded ports, file paths, or database names

### Running Tests

```bash
# Run all tests in parallel (default)
mvn test

# Run single test class
mvn test -Dtest=AutomationRuleEvaluatorsResourceTest

# Disable parallelism
mvn test -Djunit.jupiter.execution.parallel.enabled=false

# Use more threads (4 per core)
mvn test -DthreadCount=4

# Increase memory per fork
mvn test -DargLine="-Xmx4g"
```

## Performance Impact

**Before parallel execution** (sequential):
- ~1-2 minutes per test class
- Total time for 100+ test classes: ~2-3 hours

**After parallel execution** (2 threads per core on 4-core machine = 8 threads):
- Same per-class time, but 8 classes run concurrently
- Total time: ~20-30 minutes (8x speedup)

**Memory requirements**:
- Sequential: ~4-6GB total
- Parallel (8 threads): ~16-20GB total (2GB per thread)

## Limitations

1. **Docker-in-Docker**: Running in Docker-in-Docker may limit parallelism due to resource constraints
2. **Memory**: Each parallel thread needs ~2GB, so ensure sufficient memory
3. **Container limits**: Docker has limits on concurrent containers (default ~100)
4. **Testcontainers reuse**: Containers with `withReuse(true)` persist between runs (use `docker ps -a | grep testcontainers` to clean up)

## Troubleshooting

### Container cleanup
```bash
# Remove all testcontainers
docker rm -f $(docker ps -a | grep testcontainers | awk '{print $1}')

# Remove testcontainers networks
docker network prune -f
```

### Memory issues
```bash
# Reduce thread count
mvn test -DthreadCount=1 -DperCoreThreadCount=false

# Increase heap size
export MAVEN_OPTS="-Xmx8g"
mvn test
```

### Port conflicts
```bash
# Testcontainers auto-assigns ports, but if conflicts occur:
# Kill processes using conflicting ports
lsof -ti:8080 | xargs kill -9
```
