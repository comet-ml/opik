import { describe, expect, it } from "vitest";

import { DatasetItem } from "@/types/datasets";
import { PlaygroundPromptType } from "@/types/playground";
import { LLM_MESSAGE_ROLE, MessageContent } from "@/types/llm";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import {
  collectRunMediaUrls,
  describeMediaUrlFailure,
  getFailedMediaUrls,
  MAX_CHECKED_MEDIA_URLS,
} from "./mediaUrlCheck";

const prompt = (
  id: string,
  ...contents: MessageContent[]
): PlaygroundPromptType => ({
  id,
  name: id,
  model: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
  provider: PROVIDER_TYPE.OPEN_AI,
  configs: {},
  messages: contents.map((content, index) => ({
    id: `${id}-message-${index}`,
    role: LLM_MESSAGE_ROLE.user,
    content,
  })),
});

const rows = (...data: DatasetItem["data"][]): DatasetItem[] =>
  data.map(
    (item, index) => ({ id: `item-${index}`, data: item }) as DatasetItem,
  );

const text = (value: string) => ({ type: "text" as const, text: value });
const image = (url: string) => ({
  type: "image_url" as const,
  image_url: { url },
});
const video = (url: string) => ({
  type: "video_url" as const,
  video_url: { url },
});
const audio = (url: string) => ({
  type: "audio_url" as const,
  audio_url: { url },
});

describe("collectRunMediaUrls", () => {
  it("returns nothing for a prompt without media", () => {
    expect(
      collectRunMediaUrls(
        [prompt("p1", "Hello {{name}}")],
        rows({ name: "a" }),
      ),
    ).toEqual([]);
  });

  it("collects literal image, video and audio URLs once", () => {
    const prompts = [
      prompt("p1", [
        text("Describe"),
        image("https://cdn.example.com/cat.png"),
        video("https://cdn.example.com/clip.mp4"),
      ]),
      prompt("p2", [
        image("https://cdn.example.com/cat.png"),
        audio("http://cdn.example.com/voice.mp3"),
      ]),
    ];

    expect(collectRunMediaUrls(prompts, [])).toEqual([
      "https://cdn.example.com/cat.png",
      "https://cdn.example.com/clip.mp4",
      "http://cdn.example.com/voice.mp3",
    ]);
  });

  it("resolves media variables against every dataset row", () => {
    const prompts = [prompt("p1", [text("Look"), image("{{photo}}")])];

    expect(
      collectRunMediaUrls(
        prompts,
        rows(
          { photo: "https://cdn.example.com/1.png" },
          { photo: "https://cdn.example.com/2.png" },
          { photo: "https://cdn.example.com/1.png" },
        ),
      ),
    ).toEqual([
      "https://cdn.example.com/1.png",
      "https://cdn.example.com/2.png",
    ]);
  });

  it("expands a list variable into each of its URLs", () => {
    const prompts = [prompt("p1", [image("{{photos}}")])];

    expect(
      collectRunMediaUrls(
        prompts,
        rows({
          photos: JSON.stringify([
            "https://cdn.example.com/a.png",
            "https://cdn.example.com/b.png",
          ]),
        }),
      ),
    ).toEqual([
      "https://cdn.example.com/a.png",
      "https://cdn.example.com/b.png",
    ]);
  });

  it("skips data URIs, raw base64 and unresolved variables", () => {
    const prompts = [
      prompt("p1", [
        image("data:image/png;base64,iVBORw0KGgo="),
        image("iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB"),
        image("{{missing}}"),
      ]),
    ];

    expect(collectRunMediaUrls(prompts, rows({ other: "x" }))).toEqual([]);
  });

  it("skips a row whose text variables are missing, like the run does", () => {
    const prompts = [prompt("p1", [text("{{question}}"), image("{{photo}}")])];

    expect(
      collectRunMediaUrls(
        prompts,
        rows(
          { photo: "https://cdn.example.com/no-question.png" },
          { question: "q", photo: "https://cdn.example.com/ok.png" },
        ),
      ),
    ).toEqual(["https://cdn.example.com/ok.png"]);
  });

  it(`stops at ${MAX_CHECKED_MEDIA_URLS} unique URLs`, () => {
    const prompts = [prompt("p1", [image("{{photo}}")])];
    const items = rows(
      ...Array.from({ length: 30 }, (_, index) => ({
        photo: `https://cdn.example.com/${index}.png`,
      })),
    );

    const urls = collectRunMediaUrls(prompts, items);

    expect(urls).toHaveLength(MAX_CHECKED_MEDIA_URLS);
    expect(urls[0]).toBe("https://cdn.example.com/0.png");
    expect(urls[MAX_CHECKED_MEDIA_URLS - 1]).toBe(
      `https://cdn.example.com/${MAX_CHECKED_MEDIA_URLS - 1}.png`,
    );
  });
});

describe("getFailedMediaUrls", () => {
  it("keeps only the URLs that did not pass", () => {
    const blocked = {
      url: "http://169.254.169.254/x",
      status: "blocked" as const,
    };

    expect(
      getFailedMediaUrls([
        { url: "https://a.example.com/ok.png", status: "ok" },
        { url: "data:image/png;base64,AAAA", status: "skipped" },
        blocked,
      ]),
    ).toEqual([blocked]);
  });
});

describe("describeMediaUrlFailure", () => {
  it.each([
    [
      { status: "http_error", http_status: 404 },
      "The host answered with HTTP 404",
    ],
    [
      { status: "not_media", http_status: 200, content_type: "text/html" },
      "Not an image, video or audio file (text/html)",
    ],
    [
      { status: "blocked" },
      "Blocked: it points to a private or internal address",
    ],
    [{ status: "timeout" }, "The host did not answer in time"],
  ] as const)("describes %o", (result, expected) => {
    expect(describeMediaUrlFailure({ url: "https://x", ...result })).toBe(
      expected,
    );
  });
});
