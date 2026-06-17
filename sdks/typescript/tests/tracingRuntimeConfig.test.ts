import { afterEach, describe, expect, it } from "vitest";
import {
  isTracingActive,
  resetTracingToConfigDefault,
  setTracingActive,
} from "opik";

describe("Tracing runtime config", () => {
  afterEach(() => {
    resetTracingToConfigDefault();
  });

  it("defaults to active when trackDisable is not set", () => {
    expect(isTracingActive()).toBe(true);
  });

  it("honors a runtime override", () => {
    setTracingActive(false);
    expect(isTracingActive()).toBe(false);

    setTracingActive(true);
    expect(isTracingActive()).toBe(true);
  });

  it("restores the config default after reset", () => {
    setTracingActive(false);
    expect(isTracingActive()).toBe(false);

    resetTracingToConfigDefault();
    expect(isTracingActive()).toBe(true);
  });
});
