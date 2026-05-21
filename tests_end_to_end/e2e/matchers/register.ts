import { expect } from '@playwright/test';
import type { Span } from '../pom/trace-panel.page';

declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace PlaywrightTest {
    interface Matchers<R> {
      toHaveSpanOfType(type: Span['type']): R;
      toHaveSpanCount(n: number): R;
      toHaveAtLeastSpanCount(n: number): R;
      toHaveValidInput(): R;
      toHaveValidOutput(): R;
      toHaveNoErrors(): R;
    }
  }
}

expect.extend({
  toHaveSpanOfType(received: Span[], type: Span['type']) {
    const pass = received.some((s) => s.type === type);
    return {
      pass,
      message: () =>
        pass
          ? `expected spans not to contain type "${type}", but found ${received.filter((s) => s.type === type).length} match(es)`
          : `expected spans to contain type "${type}", got types: ${received.map((s) => s.type).join(', ') || '(empty)'}`,
    };
  },
  toHaveSpanCount(received: Span[], n: number) {
    const pass = received.length === n;
    return {
      pass,
      message: () => `expected ${n} spans, got ${received.length}`,
    };
  },
  toHaveAtLeastSpanCount(received: Span[], n: number) {
    const pass = received.length >= n;
    return {
      pass,
      message: () => `expected at least ${n} spans, got ${received.length}`,
    };
  },
  toHaveValidInput(received: Span[]) {
    const invalid = received.filter((s) => s.input === null || s.input === undefined);
    const pass = invalid.length === 0;
    return {
      pass,
      message: () =>
        pass
          ? 'expected at least one span with invalid input'
          : `expected all spans to have valid input, ${invalid.length} had null/undefined: ${invalid.map((s) => s.name).join(', ')}`,
    };
  },
  toHaveValidOutput(received: Span[]) {
    const invalid = received.filter((s) => s.output === null || s.output === undefined);
    const pass = invalid.length === 0;
    return {
      pass,
      message: () =>
        pass
          ? 'expected at least one span with invalid output'
          : `expected all spans to have valid output, ${invalid.length} had null/undefined: ${invalid.map((s) => s.name).join(', ')}`,
    };
  },
  toHaveNoErrors(received: Span[]) {
    const errored = received.filter((s) => s.errorInfo !== null);
    const pass = errored.length === 0;
    return {
      pass,
      message: () =>
        pass
          ? 'expected at least one span with errors'
          : `expected no errored spans, ${errored.length} had errors: ${errored.map((s) => `${s.name}: ${s.errorInfo?.message}`).join('; ')}`,
    };
  },
});
