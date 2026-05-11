/**
 * Unit tests for the `environment` feature.
 *
 * Exercises:
 * - Per-call `environment` on `client.trace(...)` is persisted on the trace.
 * - Spans (`Trace.span`, `Span.span`) inherit the parent trace's environment unconditionally.
 * - `@track({ environment })` propagates to the root trace it creates.
 * - Nested `@track({ environment })` is ignored and warns when it conflicts with
 *   the enclosing trace's environment.
 * - Back-compat: traces without `environment` still serialize fine.
 */
import { Opik } from "opik";
import { EnvironmentAlreadyExistsError } from "@/errors/environment/errors";
import { OpikApiError } from "@/rest_api";
import {
  _resetTrackOpikClientCache,
  getTrackOpikClient,
  track,
} from "@/decorators/track";
import { logger } from "@/utils/logger";
import { describe, expect, beforeEach, afterEach, MockInstance } from "vitest";
import { mockAPIFunction } from "./mockUtils";

describe("environment feature", () => {
  describe("client.trace propagates environment", () => {
    let createTracesSpy: MockInstance;

    beforeEach(() => {
      vi.useFakeTimers();
    });

    afterEach(() => {
      vi.useRealTimers();
      createTracesSpy?.mockRestore();
    });

    it("uses per-call environment when given", async () => {
      const client = new Opik();
      createTracesSpy = vi
        .spyOn(client.api.traces, "createTraces")
        .mockImplementation(mockAPIFunction);
      client.trace({ name: "t", environment: "production" });
      await client.flush();
      expect(createTracesSpy).toHaveBeenCalledTimes(1);
      const traces = createTracesSpy.mock.calls[0][0].traces;
      expect(traces[0]).toMatchObject({ environment: "production" });
    });

    it("trace without environment does not emit an environment field", async () => {
      const client = new Opik();
      createTracesSpy = vi
        .spyOn(client.api.traces, "createTraces")
        .mockImplementation(mockAPIFunction);
      client.trace({ name: "t" });
      await client.flush();
      const traces = createTracesSpy.mock.calls[0][0].traces;
      expect(traces[0].environment).toBeUndefined();
    });
  });

  describe("Trace.span / Span.span inherit environment", () => {
    it("child span inherits parent trace environment", async () => {
      const client = new Opik();
      const createSpansSpy = vi
        .spyOn(client.api.spans, "createSpans")
        .mockImplementation(mockAPIFunction);
      vi.spyOn(client.api.traces, "createTraces").mockImplementation(
        mockAPIFunction
      );

      const trace = client.trace({ name: "t", environment: "production" });
      trace.span({ name: "s1" });
      await client.flush();

      const spans = createSpansSpy.mock.calls[0][0].spans;
      expect(spans[0]).toMatchObject({ environment: "production" });
      createSpansSpy.mockRestore();
    });

    it("grandchild span inherits via Span.span", async () => {
      const client = new Opik();
      const createSpansSpy = vi
        .spyOn(client.api.spans, "createSpans")
        .mockImplementation(mockAPIFunction);
      vi.spyOn(client.api.traces, "createTraces").mockImplementation(
        mockAPIFunction
      );

      const trace = client.trace({ name: "t", environment: "production" });
      const child = trace.span({ name: "s1" });
      child.span({ name: "s2", type: "general" });
      await client.flush();

      const spans = createSpansSpy.mock.calls[0][0].spans;
      expect(spans).toHaveLength(2);
      expect(spans[0].environment).toBe("production");
      expect(spans[1].environment).toBe("production");
      createSpansSpy.mockRestore();
    });

    it("Trace.span warns when caller passes a mismatched environment", () => {
      const warnSpy = vi.spyOn(logger, "warn").mockImplementation(() => undefined);
      const client = new Opik();
      vi.spyOn(client.api.spans, "createSpans").mockImplementation(mockAPIFunction);
      vi.spyOn(client.api.traces, "createTraces").mockImplementation(mockAPIFunction);

      const trace = client.trace({ name: "t", environment: "production" });
      (trace.span as (d: Record<string, unknown>) => unknown)({ name: "s", environment: "staging" });

      expect(warnSpy).toHaveBeenCalledOnce();
      expect(warnSpy.mock.calls[0][0]).toContain("staging");
      expect(warnSpy.mock.calls[0][0]).toContain("production");
    });

    it("Span.span warns when caller passes a mismatched environment", () => {
      const warnSpy = vi.spyOn(logger, "warn").mockImplementation(() => undefined);
      const client = new Opik();
      vi.spyOn(client.api.spans, "createSpans").mockImplementation(mockAPIFunction);
      vi.spyOn(client.api.traces, "createTraces").mockImplementation(mockAPIFunction);

      const trace = client.trace({ name: "t", environment: "production" });
      const child = trace.span({ name: "s1" });
      (child.span as (d: Record<string, unknown>) => unknown)({ name: "s2", environment: "staging" });

      expect(warnSpy).toHaveBeenCalledOnce();
      expect(warnSpy.mock.calls[0][0]).toContain("staging");
      expect(warnSpy.mock.calls[0][0]).toContain("production");
    });
  });

  describe("createEnvironment", () => {
    it("throws EnvironmentAlreadyExistsError on 409", async () => {
      const client = new Opik();
      vi.spyOn(client.api.environments, "createEnvironment").mockRejectedValue(
        new OpikApiError({ statusCode: 409, body: "conflict" })
      );

      await expect(client.createEnvironment("production")).rejects.toThrow(
        EnvironmentAlreadyExistsError
      );
      await expect(client.createEnvironment("production")).rejects.toThrow(
        "production"
      );
    });

    it("re-throws non-409 errors unchanged", async () => {
      const client = new Opik();
      const serverError = new OpikApiError({ statusCode: 500, body: "oops" });
      vi.spyOn(client.api.environments, "createEnvironment").mockRejectedValue(
        serverError
      );

      await expect(client.createEnvironment("production")).rejects.toBe(
        serverError
      );
    });
  });

  describe("@track decorator", () => {
    let trackOpikClient: ReturnType<typeof getTrackOpikClient>;
    let createTracesSpy: MockInstance;
    let createSpansSpy: MockInstance;

    beforeEach(() => {
      _resetTrackOpikClientCache();
      trackOpikClient = getTrackOpikClient();
      createTracesSpy = vi
        .spyOn(trackOpikClient.api.traces, "createTraces")
        .mockImplementation(mockAPIFunction);
      createSpansSpy = vi
        .spyOn(trackOpikClient.api.spans, "createSpans")
        .mockImplementation(mockAPIFunction);
      vi.spyOn(trackOpikClient.api.traces, "updateTrace").mockImplementation(
        mockAPIFunction
      );
      vi.spyOn(trackOpikClient.api.spans, "updateSpan").mockImplementation(
        mockAPIFunction
      );
    });

    afterEach(() => {
      vi.restoreAllMocks();
      _resetTrackOpikClientCache();
    });

    it("propagates environment to root trace", async () => {
      const fn = track({ name: "outer", environment: "production" }, () => "ok");
      fn();
      await trackOpikClient.flush();

      const traces = createTracesSpy.mock.calls[0][0].traces;
      expect(traces[0]).toMatchObject({
        name: "outer",
        environment: "production",
      });
    });

    it("nested @track with mismatched environment warns and inherits the trace's environment", async () => {
      const warnSpy = vi
        .spyOn(logger, "warn")
        .mockImplementation(() => undefined);

      const inner = track(
        { name: "inner", environment: "this-should-be-ignored" },
        () => "inner-result"
      );
      const outer = track(
        { name: "outer", environment: "production" },
        () => inner()
      );
      outer();
      await trackOpikClient.flush();

      const traces = createTracesSpy.mock.calls[0][0].traces;
      expect(traces[0].environment).toBe("production");

      const spans = createSpansSpy.mock.calls
        .map((call) => call[0].spans ?? [])
        .flat();
      expect(spans).toHaveLength(2);
      for (const span of spans) {
        expect(span.environment).toBe("production");
      }

      expect(warnSpy).toHaveBeenCalledWith(
        expect.stringContaining('Nested @track requested environment "this-should-be-ignored"')
      );
    });
  });
});
