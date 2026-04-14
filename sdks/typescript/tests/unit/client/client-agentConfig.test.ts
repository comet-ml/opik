import { Opik } from "opik";
import { MockInstance } from "vitest";
import { ConfigManager, Blueprint } from "@/agent-config";
import { OpikApiError } from "@/rest_api";
import * as OpikApi from "@/rest_api/api";
import { trackStorage } from "@/decorators/track";
import { Prompt } from "@/prompt/Prompt";
import { ChatPrompt } from "@/prompt/ChatPrompt";
import {
  mockAPIFunction,
  createMockHttpResponsePromise,
} from "../../mockUtils";

const mockBlueprintResponse: OpikApi.AgentBlueprintPublic = {
  id: "blueprint-id-1",
  type: "blueprint",
  description: "Test blueprint",
  envs: [],
  createdBy: "user@example.com",
  createdAt: new Date("2024-01-01"),
  values: [
    { key: "temperature", value: "0.8", type: "float" },
    { key: "model", value: "gpt-4", type: "string" },
    { key: "maxTokens", value: "100", type: "integer" },
    { key: "stream", value: "true", type: "boolean" },
  ],
};


describe("ConfigManager", () => {
  let client: Opik;
  let createAgentConfigSpy: MockInstance<
    typeof client.api.agentConfigs.createAgentConfig
  >;
  let updateAgentConfigSpy: MockInstance<
    typeof client.api.agentConfigs.updateAgentConfig
  >;
  let getBlueprintByIdSpy: MockInstance<
    typeof client.api.agentConfigs.getBlueprintById
  >;
  let getLatestBlueprintSpy: MockInstance<
    typeof client.api.agentConfigs.getLatestBlueprint
  >;
  let getBlueprintByEnvSpy: MockInstance<
    typeof client.api.agentConfigs.getBlueprintByEnv
  >;
  let createOrUpdateEnvsSpy: MockInstance<
    typeof client.api.agentConfigs.createOrUpdateEnvs
  >;
  let retrieveProjectSpy: MockInstance<
    typeof client.api.projects.retrieveProject
  >;

  beforeEach(() => {
    client = new Opik({ projectName: "test-project" });

    createAgentConfigSpy = vi
      .spyOn(client.api.agentConfigs, "createAgentConfig")
      .mockImplementation(mockAPIFunction);

    updateAgentConfigSpy = vi
      .spyOn(client.api.agentConfigs, "updateAgentConfig")
      .mockImplementation(mockAPIFunction);

    getBlueprintByIdSpy = vi
      .spyOn(client.api.agentConfigs, "getBlueprintById")
      .mockImplementation(() =>
        createMockHttpResponsePromise(mockBlueprintResponse)
      );

    getLatestBlueprintSpy = vi
      .spyOn(client.api.agentConfigs, "getLatestBlueprint")
      .mockImplementation(() =>
        createMockHttpResponsePromise(mockBlueprintResponse)
      );

    getBlueprintByEnvSpy = vi
      .spyOn(client.api.agentConfigs, "getBlueprintByEnv")
      .mockImplementation(() =>
        createMockHttpResponsePromise(mockBlueprintResponse)
      );

    createOrUpdateEnvsSpy = vi
      .spyOn(client.api.agentConfigs, "createOrUpdateEnvs")
      .mockImplementation(mockAPIFunction);

    retrieveProjectSpy = vi
      .spyOn(client.api.projects, "retrieveProject")
      .mockImplementation(() =>
        createMockHttpResponsePromise({ id: "project-id-1", name: "test-project" })
      );
  });

  afterEach(() => {
    createAgentConfigSpy.mockRestore();
    updateAgentConfigSpy.mockRestore();
    getBlueprintByIdSpy.mockRestore();
    getLatestBlueprintSpy.mockRestore();
    getBlueprintByEnvSpy.mockRestore();
    createOrUpdateEnvsSpy.mockRestore();
    retrieveProjectSpy.mockRestore();
  });

  describe("createBlueprint", () => {
    it("should call createAgentConfig (POST) then getBlueprintById and return a Blueprint", async () => {
      const manager = new ConfigManager("test-project", client);

      const blueprint = await manager.createBlueprint({
        values: [
          { key: "temperature", value: "0.8", type: "string" },
          { key: "model", value: "gpt-4", type: "string" },
        ],
        description: "Test blueprint",
      });

      expect(createAgentConfigSpy).toHaveBeenCalledOnce();
      expect(updateAgentConfigSpy).not.toHaveBeenCalled();
      const createCall = createAgentConfigSpy.mock.calls[0][0];
      expect(createCall.projectName).toBe("test-project");
      expect(createCall.blueprint.type).toBe("blueprint");
      expect(createCall.blueprint.values).toEqual(
        expect.arrayContaining([
          expect.objectContaining({ key: "temperature", value: "0.8", type: "string" }),
          expect.objectContaining({ key: "model", value: "gpt-4", type: "string" }),
        ])
      );

      expect(getBlueprintByIdSpy).toHaveBeenCalledOnce();
      expect(blueprint).toBeInstanceOf(Blueprint);
      expect(blueprint.id).toBe("blueprint-id-1");
    });

    it("should pass serialized values through unchanged", async () => {
      const manager = new ConfigManager("test-project", client);
      await manager.createBlueprint({
        values: [
          { key: "temperature", value: "0.8", type: "float" },
          { key: "maxTokens", value: "100", type: "integer" },
          { key: "stream", value: "true", type: "boolean" },
          { key: "model", value: "gpt-4", type: "string" },
        ],
      });

      const createCall = createAgentConfigSpy.mock.calls[0][0];
      expect(createCall.blueprint.values).toEqual(
        expect.arrayContaining([
          expect.objectContaining({ key: "temperature", value: "0.8", type: "float" }),
          expect.objectContaining({ key: "maxTokens", value: "100", type: "integer" }),
          expect.objectContaining({ key: "stream", value: "true", type: "boolean" }),
          expect.objectContaining({ key: "model", value: "gpt-4", type: "string" }),
        ])
      );
    });

    it("should use a client-side generated UUID in the POST body", async () => {
      const manager = new ConfigManager("test-project", client);
      await manager.createBlueprint({ values: [{ key: "key", value: "val", type: "string" }] });

      const createCall = createAgentConfigSpy.mock.calls[0][0];
      expect(createCall.id).toBeDefined();
      expect(typeof createCall.id).toBe("string");
      if (createCall.id) {
        expect(createCall.id.length).toBeGreaterThan(0);
        expect(getBlueprintByIdSpy.mock.calls[0][0]).toBe(createCall.id);
      }
    });
  });

  describe("updateBlueprint", () => {
    it("should call updateAgentConfig (PATCH) then getBlueprintById and return a Blueprint", async () => {
      const manager = new ConfigManager("test-project", client);

      const blueprint = await manager.updateBlueprint({
        values: [
          { key: "temperature", value: "0.9", type: "float" },
          { key: "model", value: "gpt-4o", type: "string" },
        ],
        description: "Updated blueprint",
      });

      expect(updateAgentConfigSpy).toHaveBeenCalledOnce();
      expect(createAgentConfigSpy).not.toHaveBeenCalled();
      const updateCall = updateAgentConfigSpy.mock.calls[0][0];
      expect(updateCall.projectName).toBe("test-project");
      expect(updateCall.blueprint.type).toBe("blueprint");
      expect(updateCall.blueprint.values).toEqual(
        expect.arrayContaining([
          expect.objectContaining({ key: "temperature", value: "0.9", type: "float" }),
          expect.objectContaining({ key: "model", value: "gpt-4o", type: "string" }),
        ])
      );

      expect(getBlueprintByIdSpy).toHaveBeenCalledOnce();
      expect(blueprint).toBeInstanceOf(Blueprint);
    });

    it("should use a client-side generated UUID in the PATCH body", async () => {
      const manager = new ConfigManager("test-project", client);
      await manager.updateBlueprint({ values: [{ key: "key", value: "val", type: "string" }] });

      const updateCall = updateAgentConfigSpy.mock.calls[0][0];
      expect(updateCall.blueprint.id).toBeDefined();
      expect(typeof updateCall.blueprint.id).toBe("string");
      if (updateCall.blueprint.id) {
        expect(getBlueprintByIdSpy.mock.calls[0][0]).toBe(updateCall.blueprint.id);
      }
    });
  });

  describe("createMask", () => {
    it("should call updateAgentConfig (PATCH) with type=mask and return the mask ID", async () => {
      const manager = new ConfigManager("test-project", client);
      const maskId = await manager.createMask({
        values: [{ key: "temperature", value: "0.5", type: "float" }],
        description: "A/B variant",
      });

      expect(updateAgentConfigSpy).toHaveBeenCalledOnce();
      expect(createAgentConfigSpy).not.toHaveBeenCalled();
      const updateCall = updateAgentConfigSpy.mock.calls[0][0];
      expect(updateCall.blueprint.type).toBe("mask");

      expect(typeof maskId).toBe("string");
      expect(maskId).toBeDefined();
      expect(maskId.length).toBeGreaterThan(0);
    });

    it("should pass serialized mask values through unchanged", async () => {
      const manager = new ConfigManager("test-project", client);
      await manager.createMask({
        values: [{ key: "temperature", value: "0.5", type: "float" }],
      });

      const updateCall = updateAgentConfigSpy.mock.calls[0][0];
      expect(updateCall.blueprint.values).toEqual(
        expect.arrayContaining([
          expect.objectContaining({ key: "temperature", value: "0.5", type: "float" }),
        ])
      );
    });
  });

  describe("getBlueprint", () => {
    it("should call getLatestBlueprint when no options provided", async () => {
      const manager = new ConfigManager("test-project", client);
      const blueprint = await manager.getBlueprint();

      expect(retrieveProjectSpy).toHaveBeenCalledOnce();
      expect(getLatestBlueprintSpy).toHaveBeenCalledWith(
        "project-id-1",
        expect.objectContaining({ maskId: undefined })
      );
      expect(blueprint).toBeInstanceOf(Blueprint);
    });

    it("should call getBlueprintById when id is provided", async () => {
      const manager = new ConfigManager("test-project", client);
      const blueprint = await manager.getBlueprint({ id: "blueprint-id-1" });

      expect(retrieveProjectSpy).not.toHaveBeenCalled();
      expect(getBlueprintByIdSpy).toHaveBeenCalledWith(
        "blueprint-id-1",
        expect.objectContaining({ maskId: undefined })
      );
      expect(blueprint).toBeInstanceOf(Blueprint);
      expect(blueprint?.id).toBe("blueprint-id-1");
    });

    it("should call getBlueprintByEnv when env is provided", async () => {
      const manager = new ConfigManager("test-project", client);
      const blueprint = await manager.getBlueprint({ env: "production" });

      expect(retrieveProjectSpy).toHaveBeenCalledOnce();
      expect(getBlueprintByEnvSpy).toHaveBeenCalledWith(
        "production",
        "project-id-1",
        expect.objectContaining({ maskId: undefined })
      );
      expect(blueprint).toBeInstanceOf(Blueprint);
    });

    it("should pass maskId to the underlying API call", async () => {
      const manager = new ConfigManager("test-project", client);
      await manager.getBlueprint({ id: "bp-1", maskId: "mask-xyz" });

      expect(getBlueprintByIdSpy).toHaveBeenCalledWith(
        "bp-1",
        expect.objectContaining({ maskId: "mask-xyz" })
      );
    });

    it("should return null on 404", async () => {
      getBlueprintByIdSpy.mockImplementationOnce(() => {
        throw new OpikApiError({ message: "Not found", statusCode: 404 });
      });

      const manager = new ConfigManager("test-project", client);
      const result = await manager.getBlueprint({ id: "nonexistent" });
      expect(result).toBeNull();
    });

    it("should return null when latest blueprint is not found", async () => {
      getLatestBlueprintSpy.mockImplementationOnce(() => {
        throw new OpikApiError({ message: "Not found", statusCode: 404 });
      });

      const manager = new ConfigManager("test-project", client);
      const result = await manager.getBlueprint();
      expect(result).toBeNull();
    });
  });
});

describe("Blueprint value object", () => {
  let blueprint: Blueprint;

  beforeEach(async () => {
    blueprint = await Blueprint.fromApiResponse(mockBlueprintResponse);
  });

  it("should expose id, type, description, envs, createdBy, createdAt", () => {
    expect(blueprint.id).toBe("blueprint-id-1");
    expect(blueprint.type).toBe("blueprint");
    expect(blueprint.description).toBe("Test blueprint");
    expect(blueprint.envs).toEqual([]);
    expect(blueprint.createdBy).toBe("user@example.com");
    expect(blueprint.createdAt).toEqual(new Date("2024-01-01"));
  });

  it("should return key→value map with deserialized types from values getter", () => {
    expect(blueprint.values).toEqual({
      temperature: 0.8,
      model: "gpt-4",
      maxTokens: 100,
      stream: true,
    });
  });

  it("should return a fresh copy from values getter (not mutate internal state)", () => {
    const v1 = blueprint.values;
    (v1 as Record<string, unknown>).temperature = "mutated";
    expect(blueprint.values.temperature).toBe(0.8);
  });

  it("should return deserialized value from get(key)", () => {
    expect(blueprint.get("temperature")).toBe(0.8);
    expect(blueprint.get("model")).toBe("gpt-4");
    expect(blueprint.get("maxTokens")).toBe(100);
    expect(blueprint.get("stream")).toBe(true);
  });

  it("should return undefined for missing key with no default", () => {
    expect(blueprint.get("nonexistent")).toBeUndefined();
  });

  it("should return defaultValue for missing key when default provided", () => {
    expect(blueprint.get("nonexistent", "fallback")).toBe("fallback");
  });

  it("should return all keys from keys()", () => {
    expect(blueprint.keys()).toEqual(["temperature", "model", "maxTokens", "stream"]);
  });

  it("should throw when API response has no id", async () => {
    await expect(
      Blueprint.fromApiResponse({ type: "blueprint", values: [] })
    ).rejects.toThrow("missing required field 'id'");
  });
});

describe("Blueprint prompt class hints", () => {
  function makePromptResponse(commit: string): OpikApi.AgentBlueprintPublic {
    return {
      id: "bp-prompt",
      type: "blueprint",
      values: [{ key: "p", value: commit, type: "prompt" }],
    };
  }

  const chatTemplate = JSON.stringify([{ role: "user", content: "Hi" }]);
  const textTemplate = "Hello";

  function mockOpikWithPromptCommit(templateStructure: string) {
    const template = templateStructure === "chat" ? chatTemplate : textTemplate;
    const opik = {
      api: {
        prompts: {
          getPromptByCommit: vi.fn().mockResolvedValue({
            id: "prompt-id",
            name: "my-prompt",
            templateStructure,
            requestedVersion: {
              id: "version-id",
              promptId: "prompt-id",
              commit: "abc12345",
              template,
              type: "mustache",
            },
          }),
        },
      },
    };
    return opik as unknown as Parameters<typeof Blueprint.fromApiResponse>[1];
  }

  it("templateStructure=chat returns ChatPrompt", async () => {
    const opik = mockOpikWithPromptCommit("chat");
    const bp = await Blueprint.fromApiResponse(
      makePromptResponse("abc12345"),
      opik
    );
    expect(bp.values["p"]).toBeInstanceOf(ChatPrompt);
  });

  it("templateStructure=text returns Prompt", async () => {
    const opik = mockOpikWithPromptCommit("text");
    const bp = await Blueprint.fromApiResponse(
      makePromptResponse("abc12345"),
      opik
    );
    expect(bp.values["p"]).toBeInstanceOf(Prompt);
  });
});

describe("getOrCreateConfig option exclusivity", () => {
  let client: Opik;

  beforeEach(() => {
    client = new Opik({ projectName: "test-project" });
  });

  function callInsideTrack(opts: { fallback?: { model: string }; projectName?: string; env?: string; version?: string }) {
    return trackStorage.run(
      { span: { update: vi.fn() }, trace: { update: vi.fn() } } as unknown as Parameters<typeof trackStorage.run>[0],
      () => client.getOrCreateConfig(opts as Parameters<typeof client.getOrCreateConfig>[0])
    );
  }

  it("should throw when both version and env are specified", async () => {
    await expect(
      callInsideTrack({ fallback: { model: "gpt-4" }, version: "v1", env: "prod" })
    ).rejects.toThrow("Only one of 'version' or 'env'");
  });
});
