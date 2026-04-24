import { trackStorage } from "@/decorators/track";
import { createTypedConfig } from "@/agent-config/Config";
import { inferBackendType } from "@/typeHelpers";
import { BasePrompt } from "@/prompt/BasePrompt";
import { PromptVersion } from "@/prompt/PromptVersion";

const fieldNames = new Set(["temperature", "model"]);

function makeConfig(overrides?: Partial<Parameters<typeof createTypedConfig>[0]>) {
  return createTypedConfig({
    values: { temperature: 0.5, model: "gpt-4" },
    fieldNames,
    blueprintId: "bp-123",
    blueprintVersion: "v2",
    isFallback: false,
    maskId: undefined,
    ...overrides,
  });
}

describe("Config", () => {
  it("exposes typed values", () => {
    const cfg = makeConfig();
    expect(cfg.temperature).toBe(0.5);
    expect(cfg.model).toBe("gpt-4");
  });

  it("exposes meta properties", () => {
    const cfg = makeConfig();
    expect(cfg.blueprintId).toBe("bp-123");
    expect(cfg.blueprintVersion).toBe("v2");
    expect(cfg.isFallback).toBe(false);
  });

  it("injects trace metadata on field access inside track()", () => {
    const updateSpy = vi.fn();
    const mockCtx = {
      span: { update: updateSpy },
      trace: { update: updateSpy },
    };

    trackStorage.run(mockCtx as unknown as Parameters<typeof trackStorage.run>[0], () => {
      const cfg = makeConfig();
      void cfg.temperature;
    });

    expect(updateSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        metadata: expect.objectContaining({
          agent_configuration: expect.objectContaining({
            _blueprint_id: "bp-123",
            blueprint_version: "v2",
          }),
        }),
      })
    );
  });

  it("omits _mask_id from trace metadata when maskId is undefined", () => {
    const updateSpy = vi.fn();
    const mockCtx = {
      span: { update: updateSpy },
      trace: { update: updateSpy },
    };

    trackStorage.run(mockCtx as unknown as Parameters<typeof trackStorage.run>[0], () => {
      const cfg = makeConfig({ maskId: undefined });
      void cfg.temperature;
    });

    const call = updateSpy.mock.calls[0][0];
    expect(call.metadata.agent_configuration).not.toHaveProperty("_mask_id");
  });

  it("includes _mask_id in trace metadata when maskId is set", () => {
    const updateSpy = vi.fn();
    const mockCtx = {
      span: { update: updateSpy },
      trace: { update: updateSpy },
    };

    trackStorage.run(mockCtx as unknown as Parameters<typeof trackStorage.run>[0], () => {
      const cfg = makeConfig({ maskId: "mask-abc" });
      void cfg.temperature;
    });

    expect(updateSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        metadata: expect.objectContaining({
          agent_configuration: expect.objectContaining({
            _mask_id: "mask-abc",
          }),
        }),
      })
    );
  });

  it("does not inject metadata outside track()", () => {
    const updateSpy = vi.fn();
    const cfg = makeConfig();
    void cfg.temperature;
    expect(updateSpy).not.toHaveBeenCalled();
  });

  it("isFallback is true when configured so", () => {
    const cfg = makeConfig({ isFallback: true, blueprintId: undefined, blueprintVersion: undefined });
    expect(cfg.isFallback).toBe(true);
  });
});

describe("Config — prompt field serialisation in metadata", () => {
  function makePromptLike(commit: string): BasePrompt {
    const obj = Object.create(BasePrompt.prototype);
    Object.defineProperty(obj, "commit", { get: () => commit, configurable: true });
    Object.defineProperty(obj, "versionId", { get: () => "v-id-1", configurable: true });
    Object.defineProperty(obj, "synced", { get: () => true, configurable: true });
    return obj;
  }

  function makePromptVersion(commit: string): PromptVersion {
    return new PromptVersion({
      name: "p",
      prompt: "Hello",
      commit,
      promptId: "pid-1",
      versionId: "vid-1",
      type: "mustache",
    });
  }

  it("serialises a prompt field to its commit string in metadata values", () => {
    const prompt = makePromptLike("commit-abc");
    const names = new Set(["system_prompt"]);

    const updateSpy = vi.fn();
    const mockCtx = { span: { update: updateSpy }, trace: { update: updateSpy } };

    trackStorage.run(mockCtx as unknown as Parameters<typeof trackStorage.run>[0], () => {
      const cfg = createTypedConfig({
        values: { system_prompt: prompt },
        fieldNames: names,
        blueprintId: "bp-1",
        blueprintVersion: "v1",
        isFallback: false,
        maskId: undefined,
      });
      void cfg.system_prompt;
    });

    const call = updateSpy.mock.calls[0][0];
    const fieldEntry = call.metadata.agent_configuration.values["system_prompt"];
    expect(fieldEntry.type).toBe("prompt");
    expect(fieldEntry.value).toBe("commit-abc");
  });

  it("serialises a prompt_commit (PromptVersion) field to its commit string in metadata values", () => {
    const pv = makePromptVersion("commit-xyz");
    const names = new Set(["pv"]);

    const updateSpy = vi.fn();
    const mockCtx = { span: { update: updateSpy }, trace: { update: updateSpy } };

    trackStorage.run(mockCtx as unknown as Parameters<typeof trackStorage.run>[0], () => {
      const cfg = createTypedConfig({
        values: { pv },
        fieldNames: names,
        blueprintId: "bp-1",
        blueprintVersion: "v1",
        isFallback: false,
        maskId: undefined,
      });
      void cfg.pv;
    });

    const call = updateSpy.mock.calls[0][0];
    const fieldEntry = call.metadata.agent_configuration.values["pv"];
    expect(fieldEntry.type).toBe("prompt_commit");
    expect(fieldEntry.value).toBe("commit-xyz");
  });

  it("sets value to undefined in metadata when prompt field is undefined", () => {
    const names = new Set(["system_prompt"]);

    const updateSpy = vi.fn();
    const mockCtx = { span: { update: updateSpy }, trace: { update: updateSpy } };

    trackStorage.run(mockCtx as unknown as Parameters<typeof trackStorage.run>[0], () => {
      const cfg = createTypedConfig({
        values: { system_prompt: undefined },
        fieldNames: names,
        blueprintId: "bp-1",
        blueprintVersion: "v1",
        isFallback: false,
        maskId: undefined,
      });
      void cfg.system_prompt;
    });

    const call = updateSpy.mock.calls[0][0];
    // undefined values are skipped entirely
    expect(call.metadata.agent_configuration.values["system_prompt"]).toBeUndefined();
  });

  it("injects metadata with undefined commit for an unsynced prompt passed directly to createTypedConfig", () => {
    // Prompts should be awaited at getOrCreate time (client level), not injection time.
    // If someone bypasses that and passes an unsynced prompt directly, commit is undefined.
    const unsyncedPrompt = Object.create(BasePrompt.prototype) as BasePrompt;
    Object.defineProperty(unsyncedPrompt, "commit", { get: () => undefined, configurable: true });
    Object.defineProperty(unsyncedPrompt, "synced", { get: () => false, configurable: true });

    const updateSpy = vi.fn();
    const mockCtx = { span: { update: updateSpy }, trace: { update: updateSpy } };
    const names = new Set(["system_prompt"]);

    trackStorage.run(mockCtx as unknown as Parameters<typeof trackStorage.run>[0], () => {
      const cfg = createTypedConfig({
        values: { system_prompt: unsyncedPrompt },
        fieldNames: names,
        blueprintId: "bp-1",
        blueprintVersion: "v1",
        isFallback: false,
        maskId: undefined,
      });
      void cfg.system_prompt;
    });

    expect(updateSpy).toHaveBeenCalled();
    const call = updateSpy.mock.calls[0][0];
    const fieldEntry = call.metadata.agent_configuration.values["system_prompt"];
    expect(fieldEntry.type).toBe("prompt");
    expect(fieldEntry.value).toBeUndefined();
  });
});

// Keep inferBackendType in scope to avoid unused import warning
void inferBackendType;
