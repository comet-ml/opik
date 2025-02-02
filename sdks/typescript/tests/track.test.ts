import { trackOpikClient } from "@/decorators/track";
import { Opik, track } from "@opik";
import { MockInstance } from "vitest";
import { delay } from "./utils";

async function mockAPIPromise<T>() {
  return {} as T;
}

describe("Track decorator", () => {
  let client: Opik;
  let createSpansSpy: MockInstance<typeof client.api.spans.createSpans>;
  let updateSpansSpy: MockInstance<typeof client.api.spans.updateSpan>;
  let createTracesSpy: MockInstance<typeof client.api.traces.createTraces>;
  let updateTracesSpy: MockInstance<typeof client.api.traces.updateTrace>;

  beforeEach(() => {
    createSpansSpy = vi
      .spyOn(trackOpikClient.api.spans, "createSpans")
      .mockImplementation(mockAPIPromise);

    updateSpansSpy = vi
      .spyOn(trackOpikClient.api.spans, "updateSpan")
      .mockImplementation(mockAPIPromise);

    createTracesSpy = vi
      .spyOn(trackOpikClient.api.traces, "createTraces")
      .mockImplementation(mockAPIPromise);

    updateTracesSpy = vi
      .spyOn(trackOpikClient.api.traces, "updateTrace")
      .mockImplementation(mockAPIPromise);
  });

  afterEach(() => {
    createSpansSpy.mockRestore();
    createTracesSpy.mockRestore();
    updateSpansSpy.mockRestore();
    updateTracesSpy.mockRestore();
  });

  it("should maintain correct span hierarchy for mixed async/sync functions", async () => {
    const f111 = track()(function innerf111() {
      return "f111";
    });

    const f11 = track()(async function innerf11(a: number, b: number) {
      await delay(10);
      const result = f111();
      return a + b;
    });

    const f12 = track()(function innerf12() {
      return "f12";
    });

    const f13 = track()(function innerf13(obj: any) {
      return { hello: "world" };
    });

    const f1 = track()(async function innerf1(message: string) {
      const promise = f11(1, 2);
      f12();
      f13({ a: "b" });
      return promise;
    });

    await f1("test:f1");
    await trackOpikClient.flush();

    expect(createTracesSpy).toHaveBeenCalledTimes(1);
    expect(createSpansSpy).toHaveBeenCalledTimes(1);

    const { spans } = createSpansSpy.mock.calls[0][0];
    expect(spans[0]).toMatchObject({
      name: "innerf1",
      parentSpanId: undefined,
    });
    expect(spans[1]).toMatchObject({
      name: "innerf11",
      parentSpanId: spans[0]?.id,
    });
    expect(spans[2]).toMatchObject({
      name: "innerf12",
      parentSpanId: spans[0]?.id,
    });
    expect(spans[3]).toMatchObject({
      name: "innerf13",
      parentSpanId: spans[0]?.id,
    });
    expect(spans[4]).toMatchObject({
      name: "innerf111",
      parentSpanId: spans[1]?.id,
    });
  });

  it("track decorator", async () => {
    class TestClass {
      @track({ type: "llm" })
      async llmCall() {
        return "llm result";
      }

      @track({ name: "translate" })
      async translate(text: string) {
        return `translated: ${text}`;
      }

      @track({ name: "initial", projectName: "track-decorator-test" })
      async execute() {
        const result = await this.llmCall();
        return this.translate(result);
      }
    }

    const test = new TestClass();
    await test.execute();
    await trackOpikClient.flush();

    expect(createTracesSpy).toHaveBeenCalledTimes(1);
    expect(createSpansSpy).toHaveBeenCalledTimes(1);

    const { spans } = createSpansSpy.mock.calls[0][0];
    expect(spans[0]).toMatchObject({
      name: "initial",
      parentSpanId: undefined,
    });
    expect(spans[1]).toMatchObject({
      name: "llmCall",
      parentSpanId: spans[0]?.id,
    });
    expect(spans[2]).toMatchObject({
      name: "translate",
      parentSpanId: spans[0]?.id,
    });
  });
});
