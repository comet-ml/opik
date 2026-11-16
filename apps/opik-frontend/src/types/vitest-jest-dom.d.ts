import type { TestingLibraryMatchers } from "@testing-library/jest-dom/matchers";

// Vitest 5 reads custom matcher types from `Matchers`; jest-dom 6 still
// augments `@vitest/expect`, which Vitest no longer depends on.
declare module "vitest" {
  interface Matchers<
    R extends void | Promise<void> = void | Promise<void>,
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    T = unknown,
  > extends TestingLibraryMatchers<unknown, R> {}
}
