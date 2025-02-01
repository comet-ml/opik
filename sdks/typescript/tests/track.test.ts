import { Opik, track } from "@opik";
import { MockInstance } from "vitest";
import { delay } from "./utils";

async function mockAPIPromise<T>() {
  return {} as T;
}

describe.only("Track decorator", () => {
  let client: Opik;
  let createSpansSpy: MockInstance<typeof client.api.spans.createSpans>;
  let updateSpansSpy: MockInstance<typeof client.api.spans.updateSpan>;
  let createTracesSpy: MockInstance<typeof client.api.traces.createTraces>;
  let updateTracesSpy: MockInstance<typeof client.api.traces.updateTrace>;

  beforeEach(() => {
    client = new Opik({
      projectName: "opik-sdk-typescript",
    });

    createSpansSpy = vi
      .spyOn(client.api.spans, "createSpans")
      .mockImplementation(mockAPIPromise);

    updateSpansSpy = vi
      .spyOn(client.api.spans, "updateSpan")
      .mockImplementation(mockAPIPromise);

    createTracesSpy = vi
      .spyOn(client.api.traces, "createTraces")
      .mockImplementation(mockAPIPromise);

    updateTracesSpy = vi
      .spyOn(client.api.traces, "updateTrace")
      .mockImplementation(mockAPIPromise);
  });

  afterEach(() => {
    createSpansSpy.mockRestore();
    createTracesSpy.mockRestore();
    updateSpansSpy.mockRestore();
    updateTracesSpy.mockRestore();
  });

  it("combine async and sync functions + different arguments and outputs", async () => {
    const f1 = track(function innerf1(message: string) {
      console.log("f1");
      const promise = f11(1, 2);
      f12();
      f13({ a: "b" });
      return promise;
    });

    const f11 = track(async function innerf11(a: number, b: number) {
      console.log("f11");
      await delay(10);
      f111();
      return a + b;
    });

    const f111 = track(function innerf111() {
      console.log("f111");
      return "f111";
    });

    const f12 = track(function innerf12() {
      console.log("f12");
      return "f12";
    });

    const f13 = track(function innerf13(obj: any) {
      console.log("f13");
      return { hello: "world" };
    });

    await f1("test:f1");

    expect(createTracesSpy).toHaveBeenCalledTimes(1);
    expect(createSpansSpy).toHaveBeenCalledTimes(5);
  });
});
