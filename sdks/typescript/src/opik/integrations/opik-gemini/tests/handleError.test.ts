/* eslint-disable @typescript-eslint/no-explicit-any */
import { describe, expect, it, vi } from "vitest";
import { trackGemini } from "../src";

const failures = {
  "a rejected promise": (error: Error) => async () => {
    throw error;
  },
  "a synchronous throw": (error: Error) => () => {
    throw error;
  },
  "a failing stream": (error: Error) => async () =>
    (async function* () {
      yield { candidates: [{ content: { parts: [{ text: "partial" }] } }] };
      throw error;
    })(),
};

const callFailing = async (
  fail: (error: Error) => (...args: unknown[]) => unknown,
  error: Error,
  withParent: boolean
) => {
  const target = { span: vi.fn(), update: vi.fn(), end: vi.fn() };
  const parent = { span: vi.fn(), update: vi.fn(), end: vi.fn() };
  const client = {
    trace: vi.fn().mockReturnValue(target),
    flush: async () => {},
  };
  const tracked = trackGemini(
    { models: { generateContent: fail(error) } },
    withParent
      ? { parent: parent as any, client: client as any }
      : { client: client as any }
  );

  await expect(
    (async () => {
      const result: any = await tracked.models.generateContent({
        model: "gemini-test",
        contents: "test",
      });
      if (result && Symbol.asyncIterator in result) {
        for await (const chunk of result as AsyncIterable<unknown>) {
          void chunk;
        }
      }
    })()
  ).rejects.toBe(error);

  return { parent, client, target: withParent ? parent : target };
};

describe.each(Object.entries(failures))(
  "trackGemini with %s",
  (_name, fail) => {
    it("records the error on a user-provided parent without ending it", async () => {
      const error = new Error(`boom_${crypto.randomUUID()}`);

      const { parent, client } = await callFailing(fail, error, true);

      expect(client.trace).not.toHaveBeenCalled();
      expect(parent.span).toHaveBeenCalledTimes(1);
      expect(parent.span.mock.calls[0][0].errorInfo.message).toBe(
        error.message
      );
      expect(parent.end).not.toHaveBeenCalled();
    });

    it("ends the trace it created when there is no user-provided parent", async () => {
      const error = new Error(`boom_${crypto.randomUUID()}`);

      const { target } = await callFailing(fail, error, false);

      expect(target.span).toHaveBeenCalledTimes(1);
      expect(target.span.mock.calls[0][0].errorInfo.message).toBe(
        error.message
      );
      expect(target.end).toHaveBeenCalledTimes(1);
    });
  }
);
