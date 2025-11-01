# Create and Run Tests

**Command**: `cursor create-and-run-tests`

## Overview

Create comprehensive tests for new features and run only relevant tests (new, updated, or modified) to ensure code quality and functionality. This command provides Cursor-native test creation and execution that integrates seamlessly with daily development, optimizing for large project workflows.

This workflow will:

- Detect the project type and testing framework (Java/Backend, TypeScript/Frontend, Python SDK, TypeScript SDK)
- Run existing tests to identify current status
- Create new tests for untested functionality following component-specific patterns
- Execute only relevant tests (new, updated, or modified) to optimize development workflow
- Follow all Opik testing conventions and rules

---

## Inputs

- **None required**: Automatically detects project type and current testing needs

---

## Steps

### 1. Preflight & Environment Check

- **Detect project type**: Analyze workspace to determine which Opik component we're working with:
  - **Backend**: Check for `pom.xml`, Java source files, Maven structure
  - **Frontend**: Check for `package.json`, React/TypeScript files, Vite config
  - **Python SDK**: Check for `pyproject.toml`, Python source files, pytest config
  - **TypeScript SDK**: Check for `package.json`, TypeScript files, tsup config
- **Verify testing framework**: Ensure appropriate testing tools are available and configured
- **Check git status**: Ensure we're on a feature branch (not main) following Opik conventions

---

### 2. Project-Specific Test Setup

#### **Backend (Java)**
- **Framework**: JUnit 5, AssertJ, PODAM, Testcontainers
- **Test organization**: Mirror `src/main/java` structure in `src/test/java`
- **Naming conventions**: `shouldCreateUser_whenValidRequest`, `shouldThrowBadRequestException_whenInvalidInput`
- **Test location**: `apps/opik-backend/src/test/java/`

#### **Frontend (TypeScript/React)**
- **Framework**: Vitest, Playwright, React Testing Library
- **Test organization**: Co-located with components or in dedicated test directories
- **Naming conventions**: `should render user profile`, `should handle form submission`
- **Test location**: `apps/opik-frontend/src/` or `apps/opik-frontend/e2e/`

#### **Python SDK**
- **Framework**: pytest, fake_backend fixture, testlib utilities
- **Test organization**: Mirror `src/opik` structure in `tests/`
- **Naming conventions**: `test_create_user__happyflow`, `test_create_user__when_invalid_email__throws_bad_request_exception`
- **Test location**: `sdks/python/tests/`

#### **TypeScript SDK**
- **Framework**: Vitest, node-fetch mocking
- **Test organization**: Mirror source structure in `tests/`
- **Naming conventions**: `should create user when valid request`, `should throw error when invalid input`
- **Test location**: `sdks/typescript/tests/`

---

### 3. Run Relevant Tests

- **Execute relevant tests** based on project type and changes:
  ```bash
  # Backend - Run specific test classes or packages
  mvn test -Dtest="UserServiceTest"
  mvn test -Dtest="UserServiceTest#shouldCreateUser_whenValidRequest"
  
  # Frontend - Run tests for changed components
  npm test -- UserProfile.test.tsx
  npm test -- --run src/components/UserProfile/
  
  # Python SDK - Run specific test files or functions
  pytest tests/unit/test_user_service.py
  pytest tests/unit/test_user_service.py::test_create_user__happyflow
  
  # TypeScript SDK - Run specific test files
  npm test -- UserService.test.ts
  ```
- **Capture output**: Record test results, failures, and coverage information
- **Identify gaps**: Note which functionality lacks test coverage

---

### 4. Identify Relevant Tests

- **Detect changed files**: Use git to identify modified source files and their corresponding tests
- **Find related tests**: Locate test files that cover the changed functionality
- **Determine test scope**: Identify which tests need to be run based on changes:
  - **New functionality**: Run only newly created tests
  - **Modified functionality**: Run tests for modified classes/methods
  - **Dependency changes**: Run tests that depend on changed components
- **Optimize test execution**: Focus on minimal test set for faster feedback

---

### 5. Analyze Test Coverage

- **Review source code**: Identify untested classes, methods, and edge cases
- **Check existing tests**: Ensure tests follow established patterns and conventions
- **Identify missing scenarios**: Look for untested error conditions, edge cases, and integration points
- **Prioritize test creation**: Focus on critical business logic and public APIs first

---

### 6. Create Missing Tests

#### **Backend Test Creation**
- **Follow established patterns**: Use existing test classes as templates
- **Test organization**: Create tests in appropriate package structure
- **Use PODAM**: Generate test data with `PodamFactoryUtils.newPodamFactory()`
- **Mock dependencies**: Use Mockito for external dependencies
- **Test both success and failure**: Cover happy path and error scenarios

```java
@Test
void shouldCreateUser_whenValidRequest() {
    // Given
    var request = podamFactory.manufacturePojo(UserCreateRequest.class)
        .toBuilder()
        .name("John Doe")
        .email("john@example.com")
        .build();
    
    var expectedId = "user-123";
    when(idGenerator.generate()).thenReturn(expectedId);
    when(userDao.create(any(User.class))).thenReturn(expectedUser);
    
    // When
    var actualUser = userService.createUser(request);
    
    // Then
    assertThat(actualUser).isEqualTo(expectedUser);
    verify(userDao).create(any(User.class));
}
```

#### **Frontend Test Creation**
- **Component testing**: Test React components with React Testing Library
- **Hook testing**: Test custom hooks in isolation
- **Integration testing**: Test component interactions and data flow
- **Mock external dependencies**: Use Vitest mocking for API calls

```typescript
test('should render user profile with data', async () => {
  // Given
  const mockUser = { id: '1', name: 'John Doe', email: 'john@example.com' };
  vi.mocked(useUser).mockReturnValue({ data: mockUser, isLoading: false });
  
  // When
  render(<UserProfile userId="1" />);
  
  // Then
  expect(screen.getByText('John Doe')).toBeInTheDocument();
  expect(screen.getByText('john@example.com')).toBeInTheDocument();
});
```

#### **Python SDK Test Creation**
- **Use fake_backend**: For integration tests that create traces/spans
- **Follow naming conventions**: Use descriptive test names with double underscores
- **Use testlib utilities**: Leverage `TraceModel`, `SpanModel`, `assert_equal`
- **Test both sync and async**: Cover both synchronous and asynchronous code paths

```python
def test_create_user__happyflow(fake_backend):
    # Given
    request = UserCreateRequest(name="John Doe", email="john@example.com")
    
    # When
    actual_user = user_service.create_user(request)
    
    # Then
    assert actual_user is not None
    assert actual_user.name == request.name
```

#### **TypeScript SDK Test Creation**
- **Test public API**: Focus on testing the public interface
- **Mock network calls**: Use Vitest to mock HTTP requests
- **Test error handling**: Cover error scenarios and edge cases
- **Use proper assertions**: Leverage Vitest's assertion library

```typescript
test('should create user when valid request', async () => {
  // Given
  const mockUser = { id: '1', name: 'John Doe' };
  vi.mocked(fetch).mockResolvedValueOnce({
    ok: true,
    json: async () => mockUser,
  } as Response);
  
  // When
  const result = await client.createUser({ name: 'John Doe' });
  
  // Then
  expect(result).toEqual(mockUser);
});
```

---

### 7. Execute Relevant Tests

- **Run relevant tests**: Execute only tests that are new, updated, or modified
- **Monitor execution**: Track test progress and identify failures
- **Capture results**: Record test outcomes, timing, and coverage metrics

---

### 8. Analyze Test Failures

- **Categorize failures**:
  - **Flaky tests**: Tests that fail intermittently
  - **Broken tests**: Tests that consistently fail due to code changes
  - **New failures**: Tests that fail due to recent modifications
- **Prioritize fixes**: Focus on critical failures first
- **Identify root causes**: Understand why tests are failing

---

### 9. Fix Test Issues Systematically

#### **Flaky Test Fixes**
- **Add proper waiting**: Use appropriate synchronization mechanisms
- **Fix race conditions**: Ensure proper test isolation
- **Stabilize test data**: Use consistent, predictable test data

#### **Broken Test Fixes**
- **Update test expectations**: Align tests with current implementation
- **Fix mock configurations**: Update mocks to match new interfaces
- **Correct assertions**: Fix test assertions to match expected behavior

#### **New Failure Fixes**
- **Review recent changes**: Understand what caused the failures
- **Update test logic**: Modify tests to match new requirements
- **Add missing coverage**: Create tests for new functionality

---

### 10. Re-run Tests After Fixes

- **Execute fixed tests**: Run tests that were just fixed
- **Verify fixes**: Ensure failures are resolved
- **Check for regressions**: Verify that fixes don't break other tests
- **Iterate if needed**: Continue fixing until relevant tests pass

---

### 11. Quality Assurance

#### **Test Coverage Review**
- **Check coverage metrics**: Ensure adequate test coverage
- **Identify gaps**: Look for untested code paths
- **Add edge case tests**: Test boundary conditions and error scenarios

#### **Test Quality Review**
- **Follow naming conventions**: Ensure tests follow established patterns
- **Check test isolation**: Verify tests don't depend on each other
- **Review test data**: Ensure test data is realistic and varied
- **Validate assertions**: Check that assertions are meaningful and specific

---

### 12. Commit Test Changes

- **Stage test files**: Add new and modified test files
- **Follow commit conventions**: Use appropriate commit message format
  ```bash
  # For new tests
  git commit -m "Revision X: Add comprehensive tests for <feature>"
  
  # For test fixes
  git commit -m "Revision X: Fix failing test cases"
  ```
- **Push changes**: Update the remote branch with test improvements

---

### 13. Completion Summary & Validation

- **Confirm all steps completed**:
  - ✅ Project type detected and testing framework verified
  - ✅ Existing test suite executed successfully
  - ✅ Test coverage analyzed and gaps identified
  - ✅ Missing tests created following established patterns
  - ✅ Relevant tests pass successfully
  - ✅ Test quality standards met
  - ✅ Changes committed and pushed
- **Show summary**: Display test results, coverage metrics, and improvements made
- **Next steps**: Provide guidance on maintaining test quality

---

## Error Handling

### **Framework Detection Errors**
- **Unknown project type**: Provide manual project type selection
- **Missing testing framework**: Guide user through framework setup
- **Configuration issues**: Help resolve testing configuration problems

### **Test Execution Errors**
- **Build failures**: Fix compilation issues before running tests
- **Dependency issues**: Resolve missing or conflicting dependencies
- **Environment problems**: Help set up proper testing environment

### **Test Creation Errors**
- **Pattern violations**: Ensure tests follow established conventions
- **Missing dependencies**: Add required testing dependencies
- **Configuration issues**: Fix test configuration problems

### **Test Fix Errors**
- **Complex failures**: Break down complex test issues into manageable parts
- **Mock configuration**: Help configure mocks correctly
- **Assertion problems**: Guide proper assertion writing

---

## Success Criteria

The command is successful when:

1. ✅ Project type is correctly detected
2. ✅ Testing framework is verified and working
3. ✅ Relevant tests execute successfully
4. ✅ Test coverage gaps are identified and addressed
5. ✅ New tests are created following established patterns
6. ✅ Relevant tests pass successfully
7. ✅ Test quality standards are met
8. ✅ Changes are committed and pushed
9. ✅ Clear guidance is provided for maintaining test quality

---

## Notes

- **Component-specific patterns**: Follows different testing conventions for each Opik component
- **Integration with development**: Tests become a natural part of daily development workflow
- **Quality standards**: Ensures tests meet Opik testing guidelines and best practices
- **Pattern consistency**: Maintains consistency with existing test codebase
- **Automated execution**: Provides seamless test execution and failure analysis
- **Comprehensive coverage**: Identifies and addresses testing gaps systematically
- **Follows conventions**: Adheres to Opik testing naming conventions and organization patterns
- **Git integration**: Commits test improvements following Opik commit message standards
- **CI/CD integration**: Full test suites run in CI before and after merge, local development focuses on relevant tests only

---

**End Command**
