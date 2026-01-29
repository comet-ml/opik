# Frontend Testing Patterns

## When to Write Tests

### ALWAYS Test:
- Utility functions with multiple scenarios
- Data processing logic with edge cases
- Parsing/transformation functions
- Filter/search logic
- Business logic with multiple branches

### DON'T Test:
- Simple UI components without logic
- Third-party library integrations
- Trivial getters/setters
- Pure presentational components

## Test Structure (AAA Pattern)

```typescript
import { describe, it, expect } from "vitest";

describe("functionName", () => {
  describe("feature group", () => {
    it("should do something when condition", () => {
      // Arrange
      const input = createTestData();

      // Act
      const result = functionUnderTest(input);

      // Assert
      expect(result).toEqual(expectedOutput);
    });
  });
});
```

## Edge Cases to Test

```typescript
describe("edge cases", () => {
  it("should handle empty input", () => {
    expect(processData([])).toEqual([]);
  });

  it("should handle null/undefined input", () => {
    expect(processData(null)).toEqual(null);
    expect(processData(undefined)).toEqual(undefined);
  });

  it("should handle large datasets", () => {
    const largeArray = Array(1000).fill().map((_, i) => ({ id: i }));
    expect(processData(largeArray)).toHaveLength(1000);
  });

  it("should handle malformed data", () => {
    expect(() => processData({ invalid: "data" })).toThrowError();
  });
});
```

## Mock Data Pattern

```typescript
const createMockUser = (overrides = {}) => ({
  id: "user-1",
  name: "John Doe",
  email: "john@example.com",
  role: "admin",
  createdAt: "2024-01-01T00:00:00Z",
  ...overrides,
});

it("should process user data", () => {
  const user = createMockUser({ role: "guest" });
  expect(processUser(user).hasAdminAccess).toBe(false);
});
```

## Running Tests

```bash
npm test                    # Run all tests
npm run test:ui             # Watch mode
npm test -- utils.test.ts   # Specific file
npm test -- --coverage      # With coverage
```
