import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { Prompt } from "@/prompt/Prompt";
import { ChatPrompt } from "@/prompt/ChatPrompt";
import {
  setGlobalClient,
  resetGlobalClient,
} from "@/client/globalClient";
import type { OpikClient } from "@/client/Client";

function createMockGlobalClient(): OpikClient {
  return {
    createPrompt: vi.fn(),
    createChatPrompt: vi.fn(),
    api: {
      prompts: {
        updatePrompt: vi.fn().mockResolvedValue(undefined),
      },
      requestOptions: {},
    },
    deletePrompts: vi.fn().mockResolvedValue(undefined),
  } as unknown as OpikClient;
}

function syncedPromptResult(overrides?: object) {
  return {
    synced: true,
    id: "prompt-id",
    versionId: "version-id",
    commit: "abc123de",
    changeDescription: undefined,
    tags: undefined,
    ...overrides,
  };
}

describe("Prompt — direct instantiation", () => {
  let mockClient: OpikClient;

  beforeEach(() => {
    mockClient = createMockGlobalClient();
    setGlobalClient(mockClient);
  });

  afterEach(() => {
    resetGlobalClient();
  });

  it("uses global client when no opik argument is passed", () => {
    new Prompt({ name: "greeting", prompt: "Hello {{name}}" });
    expect(mockClient.createPrompt).toHaveBeenCalledOnce();
  });

  it("format() works immediately without awaiting sync", () => {
    const prompt = new Prompt({ name: "greeting", prompt: "Hello {{name}}" });
    expect(prompt.format({ name: "World" })).toBe("Hello World");
  });

  it("synced is false before ready() resolves", () => {
    vi.mocked(mockClient.createPrompt).mockResolvedValue(syncedPromptResult() as unknown as Prompt);
    const prompt = new Prompt({ name: "greeting", prompt: "Hello {{name}}" });
    expect(prompt.synced).toBe(false);
  });

  it("populates id, versionId, commit and sets synced after ready()", async () => {
    vi.mocked(mockClient.createPrompt).mockResolvedValue(
      syncedPromptResult() as unknown as Prompt,
    );
    const prompt = new Prompt({ name: "greeting", prompt: "Hello {{name}}" });

    await prompt.ready();

    expect(prompt.synced).toBe(true);
    expect(prompt.id).toBe("prompt-id");
    expect(prompt.versionId).toBe("version-id");
    expect(prompt.commit).toBe("abc123de");
  });

  it("passes correct args to createPrompt", async () => {
    vi.mocked(mockClient.createPrompt).mockResolvedValue(
      syncedPromptResult() as unknown as Prompt,
    );
    const prompt = new Prompt({
      name: "greeting",
      prompt: "Hello {{name}}",
      type: "mustache",
      description: "A greeting",
      tags: ["hello"],
    });

    await prompt.ready();

    expect(mockClient.createPrompt).toHaveBeenCalledWith(
      expect.objectContaining({
        name: "greeting",
        prompt: "Hello {{name}}",
        type: "mustache",
        description: "A greeting",
        tags: ["hello"],
      }),
    );
  });

  it("stays unsynced and logs warning when createPrompt throws", async () => {
    const warnSpy = vi.spyOn(console, "warn").mockImplementation(() => {});
    vi.mocked(mockClient.createPrompt).mockRejectedValue(new Error("Network error"));

    const prompt = new Prompt({ name: "greeting", prompt: "Hello {{name}}" });
    await prompt.ready();

    expect(prompt.synced).toBe(false);
    expect(prompt.id).toBeUndefined();
    warnSpy.mockRestore();
  });

  it("ready() resolves immediately when created via fromApiResponse (already synced)", async () => {
    const prompt = Prompt.fromApiResponse(
      { name: "greeting" },
      {
        id: "v-id",
        promptId: "p-id",
        template: "Hello {{name}}",
        commit: "abc123de",
        type: "mustache",
      },
      mockClient,
    );

    await prompt.ready();

    expect(prompt.synced).toBe(true);
    expect(mockClient.createPrompt).not.toHaveBeenCalled();
  });
});

describe("ChatPrompt — direct instantiation", () => {
  let mockClient: OpikClient;

  beforeEach(() => {
    mockClient = createMockGlobalClient();
    setGlobalClient(mockClient);
  });

  afterEach(() => {
    resetGlobalClient();
  });

  it("uses global client when no opik argument is passed", () => {
    const messages = [{ role: "user" as const, content: "Hello {{name}}" }];
    new ChatPrompt({ name: "greeting", messages });
    expect(mockClient.createChatPrompt).toHaveBeenCalledOnce();
  });

  it("format() works immediately without awaiting sync", () => {
    const messages = [{ role: "user" as const, content: "Hello {{name}}" }];
    const chatPrompt = new ChatPrompt({ name: "greeting", messages });

    expect(chatPrompt.format({ name: "World" })).toEqual([
      { role: "user", content: "Hello World" },
    ]);
  });

  it("populates id, versionId, commit and sets synced after ready()", async () => {
    vi.mocked(mockClient.createChatPrompt).mockResolvedValue(
      syncedPromptResult() as unknown as ChatPrompt,
    );
    const messages = [{ role: "user" as const, content: "Hello {{name}}" }];
    const chatPrompt = new ChatPrompt({ name: "greeting", messages });

    await chatPrompt.ready();

    expect(chatPrompt.synced).toBe(true);
    expect(chatPrompt.id).toBe("prompt-id");
    expect(chatPrompt.versionId).toBe("version-id");
    expect(chatPrompt.commit).toBe("abc123de");
  });

  it("stays unsynced and logs warning when createChatPrompt throws", async () => {
    const warnSpy = vi.spyOn(console, "warn").mockImplementation(() => {});
    vi.mocked(mockClient.createChatPrompt).mockRejectedValue(new Error("Network error"));

    const messages = [{ role: "user" as const, content: "Hello {{name}}" }];
    const chatPrompt = new ChatPrompt({ name: "greeting", messages });
    await chatPrompt.ready();

    expect(chatPrompt.synced).toBe(false);
    expect(chatPrompt.id).toBeUndefined();
    warnSpy.mockRestore();
  });
});
