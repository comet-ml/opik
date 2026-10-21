import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";

import usePlaygroundStore from "@/store/PlaygroundStore";
import { DatasetItem } from "@/types/datasets";
import { PlaygroundPromptType } from "@/types/playground";
import { LLM_MESSAGE_ROLE, MessageContent } from "@/types/llm";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import useMediaUrlCheckBeforeRun from "./useMediaUrlCheckBeforeRun";

const mocks = vi.hoisted(() => ({
  checkMediaUrls: vi.fn(),
  hydrate: vi.fn(),
}));

vi.mock("@/api/playground/useMediaUrlsCheckMutation", () => ({
  default: () => ({ mutateAsync: mocks.checkMediaUrls }),
}));
vi.mock("@/v2/pages/PlaygroundPage/useHydrateDatasetItemData", () => ({
  useHydrateDatasetItemData: () => mocks.hydrate,
}));

const GOOD_URL = "https://cdn.example.com/cat.png";
const BROKEN_URL = "https://cdn.example.com/missing.png";

const prompt = (id: string, content: MessageContent): PlaygroundPromptType => ({
  id,
  name: id,
  model: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
  provider: PROVIDER_TYPE.OPEN_AI,
  configs: {},
  messages: [{ id: `${id}-message`, role: LLM_MESSAGE_ROLE.user, content }],
});

const image = (url: string) => ({
  type: "image_url" as const,
  image_url: { url },
});

const setPrompts = (...prompts: PlaygroundPromptType[]) =>
  usePlaygroundStore.setState({
    promptIds: prompts.map((p) => p.id),
    promptMap: Object.fromEntries(prompts.map((p) => [p.id, p])),
  });

const renderCheck = (datasetItems: DatasetItem[] = []) => {
  const runAll = vi.fn();
  const runSingle = vi.fn();
  const { result } = renderHook(() =>
    useMediaUrlCheckBeforeRun({ datasetItems, runAll, runSingle }),
  );
  return { result, runAll, runSingle };
};

describe("useMediaUrlCheckBeforeRun", () => {
  beforeEach(() => {
    mocks.checkMediaUrls.mockReset();
    mocks.hydrate.mockReset();
    mocks.hydrate.mockImplementation(
      async (item?: DatasetItem) => item?.data ?? {},
    );
  });

  it("runs at once and makes no check call when the run has no media URLs", async () => {
    setPrompts(prompt("p1", "Hello"));
    const { result, runAll } = renderCheck();

    await act(() => result.current.runAll());

    expect(mocks.checkMediaUrls).not.toHaveBeenCalled();
    expect(runAll).toHaveBeenCalledTimes(1);
    expect(result.current.mediaUrlFailures).toBeNull();
  });

  it("runs after the check when every URL loads", async () => {
    setPrompts(prompt("p1", [image(GOOD_URL)]));
    mocks.checkMediaUrls.mockResolvedValue([{ url: GOOD_URL, status: "ok" }]);
    const { result, runAll } = renderCheck();

    await act(() => result.current.runAll());

    expect(mocks.checkMediaUrls).toHaveBeenCalledWith([GOOD_URL]);
    expect(runAll).toHaveBeenCalledTimes(1);
    expect(result.current.mediaUrlFailures).toBeNull();
  });

  it("holds the run and lists the failures when a dataset row has a broken URL", async () => {
    setPrompts(prompt("p1", [image("{{photo}}")]));
    const failure = { url: BROKEN_URL, status: "http_error", http_status: 404 };
    mocks.checkMediaUrls.mockResolvedValue([
      { url: GOOD_URL, status: "ok" },
      failure,
    ]);
    const { result, runAll } = renderCheck([
      { id: "item-1", data: { photo: GOOD_URL } } as DatasetItem,
      { id: "item-2", data: { photo: BROKEN_URL } } as DatasetItem,
    ]);

    await act(() => result.current.runAll());

    expect(mocks.checkMediaUrls).toHaveBeenCalledWith([GOOD_URL, BROKEN_URL]);
    expect(runAll).not.toHaveBeenCalled();
    expect(result.current.mediaUrlFailures).toEqual([failure]);
  });

  it("checks the full list when the page only holds a cut copy of it", async () => {
    setPrompts(prompt("p1", [image("{{photos}}")]));
    const fullList = JSON.stringify([GOOD_URL, BROKEN_URL]);
    mocks.hydrate.mockResolvedValue({ photos: fullList });
    mocks.checkMediaUrls.mockResolvedValue([
      { url: GOOD_URL, status: "ok" },
      { url: BROKEN_URL, status: "http_error", http_status: 404 },
    ]);
    const { result, runAll } = renderCheck([
      { id: "item-1", data: { photos: fullList.slice(0, 20) } } as DatasetItem,
    ]);

    await act(() => result.current.runAll());

    expect(mocks.checkMediaUrls).toHaveBeenCalledWith([GOOD_URL, BROKEN_URL]);
    expect(runAll).not.toHaveBeenCalled();
  });

  it("fetches no rows for a prompt without media", async () => {
    setPrompts(prompt("p1", "Answer {{question}}"));
    const { result, runAll } = renderCheck([
      { id: "item-1", data: { question: "q" } } as DatasetItem,
    ]);

    await act(() => result.current.runAll());

    expect(mocks.hydrate).not.toHaveBeenCalled();
    expect(mocks.checkMediaUrls).not.toHaveBeenCalled();
    expect(runAll).toHaveBeenCalledTimes(1);
  });

  it("starts nothing on Cancel", async () => {
    setPrompts(prompt("p1", [image(BROKEN_URL)]));
    mocks.checkMediaUrls.mockResolvedValue([
      { url: BROKEN_URL, status: "timeout" },
    ]);
    const { result, runAll } = renderCheck();

    await act(() => result.current.runAll());
    act(() => result.current.cancelPendingRun());

    expect(runAll).not.toHaveBeenCalled();
    expect(result.current.mediaUrlFailures).toBeNull();
  });

  it("runs on Run anyway", async () => {
    setPrompts(prompt("p1", [image(BROKEN_URL)]));
    mocks.checkMediaUrls.mockResolvedValue([
      { url: BROKEN_URL, status: "blocked" },
    ]);
    const { result, runAll } = renderCheck();

    await act(() => result.current.runAll());
    expect(runAll).not.toHaveBeenCalled();

    act(() => result.current.runPendingRunAnyway());

    expect(runAll).toHaveBeenCalledTimes(1);
    expect(result.current.mediaUrlFailures).toBeNull();
  });

  it("ignores Run while the dialog is open", async () => {
    setPrompts(prompt("p1", [image(BROKEN_URL)]));
    mocks.checkMediaUrls.mockResolvedValue([
      { url: BROKEN_URL, status: "unreachable" },
    ]);
    const { result, runAll } = renderCheck();

    await act(() => result.current.runAll());
    await act(() => result.current.runAll());

    expect(mocks.checkMediaUrls).toHaveBeenCalledTimes(1);
    expect(runAll).not.toHaveBeenCalled();
  });

  it("runs as before when the check itself fails", async () => {
    setPrompts(prompt("p1", [image(GOOD_URL)]));
    mocks.checkMediaUrls.mockRejectedValue(new Error("timeout of 8000ms"));
    const { result, runAll } = renderCheck();

    await act(() => result.current.runAll());

    expect(mocks.checkMediaUrls).toHaveBeenCalledTimes(1);
    expect(runAll).toHaveBeenCalledTimes(1);
    expect(result.current.mediaUrlFailures).toBeNull();
  });

  it("checks only the URLs of the prompt that is run on its own", async () => {
    setPrompts(
      prompt("p1", [image(GOOD_URL)]),
      prompt("p2", [image(BROKEN_URL)]),
    );
    mocks.checkMediaUrls.mockResolvedValue([{ url: GOOD_URL, status: "ok" }]);
    const { result, runSingle } = renderCheck();

    await act(() => result.current.runSingle("p1"));

    expect(mocks.checkMediaUrls).toHaveBeenCalledWith([GOOD_URL]);
    expect(runSingle).toHaveBeenCalledWith("p1");
  });

  it("ignores a second click while a check is running", async () => {
    setPrompts(prompt("p1", [image(GOOD_URL)]));
    let answer: (value: unknown) => void = () => {};
    mocks.checkMediaUrls.mockReturnValue(
      new Promise((resolve) => {
        answer = resolve;
      }),
    );
    const { result, runAll } = renderCheck();

    await act(async () => {
      const first = result.current.runAll();
      const second = result.current.runAll();
      answer([{ url: GOOD_URL, status: "ok" }]);
      await Promise.all([first, second]);
    });

    expect(mocks.checkMediaUrls).toHaveBeenCalledTimes(1);
    expect(runAll).toHaveBeenCalledTimes(1);
  });
});
