# Copilot Code Review Instructions

> **Scope:** These guidelines apply to all Opik applications including backend, frontend, and SDKs. Use the appropriate sections based on the code being reviewed.

When Copilot automatically reviews pull requests, use the following guidelines to structure feedback and ensure consistency across the entire Opik project.

---

## Project Overview

Opik is a comprehensive observability and AI evaluation platform with multiple applications:

- **Backend**: Java-based REST API with MySQL and ClickHouse databases
- **Frontend**: React/TypeScript application with modern UI components
- **Python SDK**: Client library for Python applications
- **TypeScript SDK**: Client library for TypeScript/JavaScript applications
- **Documentation**: Comprehensive documentation site
- **Testing**: End-to-end and load testing suites

## 1. Git Workflow & Branch Management

### Branch Naming Convention
```
{username}/{ticket}-{summary}
```

**Examples:**
```
andrescrz/OPIK-2236-add-documentation-and-user-facing-distinction-to-pr-template
someuser/issue-1234-some-task
someotheruser/NA-some-other-task
```

### Commit Message Standards
Use component types to categorize changes (optional but recommended):
- `[DOCS]` - Documentation updates, README changes, comments, swagger/OpenAPI documentation
- `[FE]` - Frontend changes (React, TypeScript, UI components)
- `[BE]` - Backend changes (Java, API endpoints, services)
- `[SDK]` - SDK changes (Python, TypeScript SDKs)

**Examples:**
```bash
# ✅ Recommended format
git commit -m "[OPIK-1234] [FE] Add project custom metrics UI dashboard"
git commit -m "[OPIK-1234] [BE] Add create trace endpoint"

# ✅ Also acceptable
git commit -m "[OPIK-1234] Add project custom metrics UI dashboard"
```

### Pull Request Guidelines
**Title Format:** `[{ticket}] [{component}] {summary}`

**Required Sections:**
- **Details**: What the change does, why it was made, and any design decisions
- **Change checklist**: User facing and Documentation update checkboxes
- **Issues**: GitHub issue or Jira ticket references
- **Testing**: Scenarios covered by tests and steps to reproduce
- **Documentation**: List of docs updated or new configuration introduced

---

## 2. Backend (Java) Review Guidelines

### Technology Stack
- **Language**: Java 21
- **Framework**: Dropwizard 4.0.14
- **Database**: MySQL 9.3.0, ClickHouse 0.9.0
- **Build Tool**: Maven with Spotless 2.46.0
- **Testing**: JUnit 5, Testcontainers, WireMock

### Architecture Requirements
- **Layered Architecture**: Resources → Services → DAOs → Models
- **Separation of Concerns**: Each layer has a single responsibility
- **Dependency Injection**: Use Guice with `@Singleton` and `@RequiredArgsConstructor`
- **Reactive Design**: Applications must be reactive, non-blocking, and horizontally scalable

### API Design Standards
- **REST Endpoints**: Follow standard HTTP methods and URL patterns
- **Validation**: Use `@Valid` and Jakarta validation annotations
- **Documentation**: Include `@Operation` with proper `operationId`
- **Response Codes**: Use appropriate HTTP status codes (200, 201, 400, 404, 500)

**Example Controller Pattern:**
```java
@Path("/api/v1/resources")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ResourcesResource {
    
    private final @NonNull ResourceService resourceService;

    @POST
    @Operation(summary = "Create resource", operationId = "createResource")
    public Response createResource(@Valid ResourceCreateRequest request) {
        var resource = resourceService.createResource(request);
        return Response.status(Response.Status.CREATED)
            .entity(resource)
            .build();
    }
}
```

### Database Access Patterns
- **Always use transactions** for MySQL reads/writes
- **Use TransactionTemplate** with READ_ONLY or WRITE types
- **JDBI3 interfaces** for DAO implementations
- **IdGenerator** for UUID v7 generation

**Example Service Pattern:**
```java
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ResourceService {
    
    private final @NonNull ResourceDao resourceDao;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate transactionTemplate;

    public ResourceResponse createResource(ResourceCreateRequest request) {
        return transactionTemplate.inTransaction(WRITE, handle -> {
            var repository = handle.attach(ResourceDao.class);
            
            var resource = Resource.builder()
                .id(idGenerator.generate())
                .name(request.getName())
                .createdAt(Instant.now())
                .build();
            
            return repository.create(resource);
        });
    }
}
```

### Error Handling
- **Specific Exceptions**: Use Jakarta exceptions (BadRequestException, NotFoundException, etc.)
- **Graceful Handling**: Always handle exceptions gracefully
- **Logging**: Use SLF4J with `@Slf4j` annotation
- **Context**: Include relevant context in log messages (surround values with single quotes)

**Example Error Handling:**
```java
@Slf4j
public class ResourceService {
    
    public ResourceResponse getResource(String id) {
        try {
            return resourceDao.findById(id)
                .orElseThrow(() -> new NotFoundException("Resource not found: '%s'".formatted(id)));
        } catch (SQLException exception) {
            log.error("Database error while retrieving resource: '{}'", id, exception);
            throw new InternalServerErrorException("Failed to retrieve resource", exception);
        }
    }
}
```

### Database Migrations
- **MySQL**: Place in `src/main/resources/liquibase/db-app-state/migrations/`
- **ClickHouse**: Place in `src/main/resources/liquibase/db-app-analytics/migrations/`
- **Format**: Include `--liquibase formatted sql` and proper changeset metadata
- **Indexes**: Add only relevant indexes with explanatory comments

### Testing Requirements
- **Unit Tests**: Test business logic with mocks
- **Integration Tests**: Test component interactions
- **Test Data**: Use PODAM for generating test data
- **Naming**: Follow camelCase conventions for test methods

### Code Quality Standards
- **File Formatting**: All files must end with a blank line
- **Naming**: Use meaningful variable and method names
- **Collections**: Prefer `Map.of()`, `List.of()`, `Set.of()` for immutable collections
- **List Access**: Use `getFirst()` or `getLast()` instead of `get(0)` or `get(size() - 1)`
- **Constants**: Replace magic numbers with named constants
- **Documentation**: Use Javadoc for public methods and classes

---

## 3. Frontend (React/TypeScript) Review Guidelines

### Technology Stack
- **Language**: TypeScript 5.4.5
- **Framework**: React 18.3.1
- **Build Tool**: Vite 5.2.11
- **Styling**: Tailwind CSS 3.4.3
- **State Management**: Zustand 4.5.2
- **Testing**: Vitest 3.0.5, Playwright 1.45.3

### Component Development Patterns
- **Performance Optimization**: Always use `useMemo` for data transformations and `useCallback` for event handlers
- **Component Structure**: Follow established patterns with proper TypeScript interfaces
- **UI Components**: Use shadcn/ui components with consistent variants
- **Styling**: Use Tailwind CSS with custom design system classes

**Example Component Pattern:**
```typescript
import React, { useMemo, useCallback } from "react";
import { cn } from "@/lib/utils";

type ComponentProps = {
  // Props interface
};

const Component: React.FunctionComponent<ComponentProps> = ({
  prop1,
  prop2,
  ...props
}) => {
  // 1. State hooks
  // 2. useMemo for expensive computations
  // 3. useCallback for event handlers
  // 4. Other hooks
  
  const processedData = useMemo(() => transformData(rawData), [rawData]);
  const handleClick = useCallback(() => {}, [deps]);
  
  return (
    <div className="component-container">
      {/* JSX */}
    </div>
  );
};
```

### Data Fetching Patterns
- **React Query**: Use TanStack Query for data fetching and caching
- **Query Keys**: Use descriptive keys with proper parameters
- **Error Handling**: Implement proper error states and loading indicators
- **Optimistic Updates**: Use mutations for data updates

### State Management
- **Zustand**: Use for global state management
- **Local Storage**: Use `use-local-storage-state` for persistence
- **Selectors**: Create focused selectors for state access

### Form Handling
- **React Hook Form**: Use with Zod validation
- **Validation**: Implement comprehensive form validation
- **Error Display**: Show validation errors clearly

### Testing Patterns
- **Unit Tests**: Test individual components and hooks
- **Integration Tests**: Test component interactions
- **E2E Tests**: Test complete user workflows with Playwright
- **Test Data**: Use realistic test data and proper mocking

### UI Component Patterns
- **Button Variants**: Use established variant system (default, secondary, outline, destructive, ghost, minimal)
- **Data Tables**: Use DataTable wrapper with proper column definitions
- **Loading States**: Use Skeleton components for loading states
- **Error States**: Use proper error styling with destructive colors

### Styling Guidelines
- **Design System**: Use custom CSS properties and typography classes
- **Color System**: Use semantic color classes (primary, secondary, muted, destructive)
- **Layout Classes**: Use consistent spacing and sizing patterns
- **Responsive Design**: Use Tailwind responsive prefixes appropriately

---

## 4. Python SDK Review Guidelines

### Technology Stack
- **Language**: Python 3.8+
- **Package Manager**: setuptools with pyproject.toml
- **HTTP Client**: httpx
- **Validation**: Pydantic 2.x
- **Testing**: pytest

### API Design Principles
- **Main API Class**: `opik.Opik` is the main entry point
- **Higher Level APIs**: Provide wrappers for complex REST calls
- **Backward Compatibility**: Maintain compatibility for public interfaces
- **Consistency**: Follow existing API patterns

### Architecture Patterns
- **Layered Architecture**: API Objects → Message Processing → REST API → Backend
- **Non-blocking Operations**: Create spans, traces, and feedback scores as background operations
- **Context Management**: Use `opik.opik_context` and `@opik.track` decorator
- **Integration Patterns**: Extend base decorator classes for new integrations

### Code Organization
- **Import Organization**: Import modules, not names (except from `typing`)
- **Access Control**: Use proper access modifiers (protected methods with underscores)
- **Module Structure**: Organize by functionality, avoid generic utility modules
- **Naming**: Use meaningful names that reflect purpose

### Dependency Management
- **Existing Dependencies**: Prioritize keeping existing dependencies
- **Version Bounds**: Use flexible version bounds with appropriate constraints
- **Conditional Imports**: Use for optional dependencies (integrations)
- **Python Versions**: Ensure compatibility with specified Python versions

### Error Handling
- **Specific Exceptions**: Use specific exception types for different error categories
- **Structured Errors**: Use consistent structured error information
- **Recovery Logic**: Implement proper retry logic for transient failures
- **Provider Errors**: Handle provider-specific errors in integrations

### Testing Requirements
- **Test Naming**: Use convention `test_WHAT__CASE_DESCRIPTION__EXPECTED_RESULT`
- **Test Organization**: Unit tests, library integration tests, end-to-end tests
- **Test Data**: Use `fake_backend` fixture for emulating real backend
- **Coverage**: Test public API only, never violate privacy

### Logging Guidelines
- **Structured Logging**: Use proper logger hierarchies
- **Log Levels**: DEBUG for detailed info, INFO/WARNING for user messages, ERROR for problems
- **Context**: Include relevant context without exposing sensitive information
- **Timing**: Include timing information for API calls and processing

---

## 5. TypeScript SDK Review Guidelines

### Technology Stack
- **Language**: TypeScript 5.7.2
- **Runtime**: Node.js 18+
- **Build Tool**: tsup 8.3.6
- **HTTP Client**: node-fetch 3.3.2
- **Validation**: Zod 3.25.55

### Code Quality Standards
- **Type Safety**: Use comprehensive TypeScript types
- **ES Modules**: Use modern ES module syntax
- **Error Handling**: Implement proper error handling with typed errors
- **Documentation**: Include comprehensive JSDoc comments

### Testing Patterns
- **Unit Tests**: Test individual functions and classes
- **Integration Tests**: Test API interactions
- **Mocking**: Use proper mocking for external dependencies
- **Type Testing**: Test TypeScript types and interfaces

---

## 6. General Code Quality Guidelines

### Clean Code Principles
- **Constants**: Replace magic numbers with named constants
- **Meaningful Names**: Variables, functions, and classes should reveal their purpose
- **Single Responsibility**: Each function should do exactly one thing
- **DRY**: Don't repeat yourself - extract common logic
- **Comments**: Explain why, not what - make code self-documenting

### Performance Considerations
- **Efficient Algorithms**: Use appropriate data structures and algorithms
- **Memory Management**: Avoid memory leaks and excessive allocations
- **Database Optimization**: Use proper indexes and query optimization
- **Caching**: Implement appropriate caching strategies

### Security Guidelines
- **Input Validation**: Validate all external inputs
- **Authentication**: Implement proper authentication and authorization
- **Data Protection**: Never log sensitive information (PII, credentials)
- **Dependency Security**: Keep dependencies updated and scan for vulnerabilities

### Documentation Standards
- **API Documentation**: Use OpenAPI/Swagger for backend APIs
- **Code Comments**: Use Javadoc, JSDoc, or docstrings as appropriate
- **README Files**: Keep documentation up to date
- **Examples**: Provide usage examples for complex functionality

---

## 7. Testing Guidelines

### Test Organization
- **Unit Tests**: Fast, isolated, no external dependencies
- **Integration Tests**: Test component interactions
- **E2E Tests**: Test complete user workflows
- **Performance Tests**: Load and stress testing where applicable

### Test Quality Standards
- **Coverage**: Aim for comprehensive test coverage
- **Readability**: Tests should be easy to understand and maintain
- **Reliability**: Tests should be deterministic and not flaky
- **Performance**: Tests should run quickly and efficiently

### Test Data Management
- **Realistic Data**: Use realistic but not sensitive test data
- **Fixtures**: Use test fixtures for common setup
- **Isolation**: Each test should be independent
- **Cleanup**: Properly clean up test data and resources

### Backend Testing (Java)
- **PODAM**: Use for generating test data with `PodamFactoryUtils.newPodamFactory()`
- **Naming**: Follow camelCase conventions (`shouldCreateUser_whenValidRequest`)
- **Assertions**: Use AssertJ for fluent assertions
- **Mocking**: Use Mockito for mocking dependencies

### Frontend Testing (TypeScript)
- **React Testing Library**: Use for component testing
- **MSW**: Use for API mocking
- **Playwright**: Use for E2E testing
- **Vitest**: Use for unit testing

### Python SDK Testing
- **pytest**: Use for all testing
- **fake_backend**: Use fixture for backend emulation
- **Test Naming**: Use descriptive test names with underscores
- **Coverage**: Test public API only

---

## 8. Dependency Management

### Version Strategy
- **Pin Major Versions**: For production stability
- **Allow Minor Updates**: For security patches and bug fixes
- **Security Updates**: Automate security patch updates
- **Breaking Changes**: Test thoroughly before major version upgrades

### Dependency Guidelines
- **Existing Dependencies**: Prefer existing dependencies over adding new ones
- **Security**: Keep dependencies updated and scan for vulnerabilities
- **Licensing**: Ensure all dependencies have acceptable licenses
- **Size**: Consider the impact of adding new dependencies

### Technology-Specific Dependencies

#### Backend (Java)
- **Core**: Dropwizard 4.0.14, JDBI3, MySQL 9.3.0, ClickHouse 0.9.0
- **Build**: Maven, Spotless 2.46.0
- **Testing**: JUnit 5, Testcontainers, WireMock
- **Observability**: OpenTelemetry 2.18.0

#### Frontend (TypeScript)
- **Core**: React 18.3.1, TypeScript 5.4.5, Vite 5.2.11
- **UI**: Tailwind CSS 3.4.3, shadcn/ui, Radix UI
- **State**: Zustand 4.5.2, TanStack Query 5.45.0
- **Testing**: Vitest 3.0.5, Playwright 1.45.3

#### Python SDK
- **Core**: Python 3.8+, httpx, Pydantic 2.x
- **Testing**: pytest
- **CLI**: Click
- **Logging**: Rich, Sentry SDK

#### TypeScript SDK
- **Core**: TypeScript 5.7.2, Node.js 18+, tsup 8.3.6
- **HTTP**: node-fetch 3.3.2
- **Validation**: Zod 3.25.55
- **Logging**: tslog 4.9.3

---

## 9. Review Checklist

### Before Review
- [ ] Understand the context and purpose of the changes
- [ ] Check if the changes follow established patterns
- [ ] Verify that tests are included and appropriate
- [ ] Ensure documentation is updated if needed

### During Review
- [ ] Check code quality and adherence to standards
- [ ] Verify error handling and edge cases
- [ ] Review performance implications
- [ ] Check security considerations
- [ ] Ensure proper logging and observability
- [ ] Verify test coverage and quality

### After Review
- [ ] Provide constructive feedback
- [ ] Suggest improvements when appropriate
- [ ] Approve only when standards are met
- [ ] Follow up on any issues identified

---

## 10. Common Issues to Watch For

### Backend Issues
- Missing transaction boundaries
- Improper exception handling
- Missing validation annotations
- Inconsistent logging patterns
- Missing or incorrect API documentation
- Not using `@Slf4j` annotation
- Logging sensitive information
- Not surrounding logged values with single quotes

### Frontend Issues
- Missing performance optimizations (useMemo, useCallback)
- Improper error handling
- Missing loading states
- Inconsistent component patterns
- Missing accessibility features
- Not using proper TypeScript types
- Inline functions in JSX props

### SDK Issues
- Breaking API changes without proper deprecation
- Missing error handling
- Inconsistent naming conventions
- Missing documentation
- Improper dependency management
- Not following import organization rules

### General Issues
- Code duplication
- Magic numbers or hardcoded values
- Missing tests
- Poor error messages
- Security vulnerabilities
- Performance issues
- Files not ending with blank lines
- Inconsistent naming conventions

---

## 11. Technology-Specific Review Focus Areas

### Backend (Java) Focus
- **Architecture**: Layered architecture compliance
- **Transactions**: Proper TransactionTemplate usage
- **Validation**: Jakarta validation annotations
- **Logging**: SLF4J with proper context
- **Testing**: PODAM usage and test naming
- **Database**: Migration script quality
- **Error Handling**: Specific exception types

### Frontend (TypeScript) Focus
- **Performance**: useMemo and useCallback usage
- **TypeScript**: Proper type definitions
- **Components**: shadcn/ui patterns
- **Styling**: Tailwind CSS conventions
- **State Management**: Zustand patterns
- **Testing**: Component and E2E test coverage
- **Accessibility**: ARIA labels and semantic HTML

### Python SDK Focus
- **API Design**: Main Opik class usage
- **Architecture**: Layered patterns
- **Testing**: Test naming conventions
- **Logging**: Structured logging
- **Dependencies**: Minimal dependency addition
- **Documentation**: Comprehensive docstrings

### TypeScript SDK Focus
- **Type Safety**: Comprehensive TypeScript usage
- **ES Modules**: Modern module syntax
- **Error Handling**: Typed error handling
- **Documentation**: JSDoc comments
- **Testing**: Unit and integration tests

---

Use these guidelines to provide comprehensive, consistent, and helpful code review feedback across all Opik applications. Each section provides specific, actionable guidance for the technology stack being reviewed.
