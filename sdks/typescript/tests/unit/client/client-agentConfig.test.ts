import { Opik } from "opik";
import { MockInstance } from "vitest";
import { ConfigManager, Blueprint } from "@/agent-config";
import { getGlobalBlueprintRegistry } from "@/agent-config/blueprintCache";
import { OpikApiError } from "@/rest_api";
import * as OpikApi from "@/rest_api/api";
import { trackStorage } from "@/decorators/track";
import { Prompt } from "@/prompt/Prompt";
import { ChatPrompt } from "@/prompt/ChatPrompt";
import { ConfigMismatchError } from "@/errors/agent-config/errors";
import { PromptType } from "@/prompt/types";
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

describe("createConfig prompt project validation", () => {
  let client: Opik;

  /** Build a minimal Prompt with a given projectName (no backend needed). */
  function makePrompt(projectName?: string): Prompt {
    return new Prompt(
      {
        name: "test-prompt",
        prompt: "Hello {{name}}",
        type: PromptType.MUSTACHE,
        synced: false,
        projectName,
      },
      client,
    );
  }

  /** Build a minimal ChatPrompt with a given projectName (no backend needed). */
  function makeChatPrompt(projectName?: string): ChatPrompt {
    return new ChatPrompt(
      {
        name: "test-chat-prompt",
        messages: [{ role: "user", content: "Hi" }],
        type: PromptType.MUSTACHE,
        synced: false,
        projectName,
      },
      client,
    );
  }

  beforeEach(() => {
    client = new Opik({ projectName: "test-project" });
  });

  it("should throw ConfigMismatchError when a Prompt field belongs to a different project", async () => {
    const prompt = makePrompt("other-project");

    await expect(
      client.createConfig(
        { systemPrompt: prompt },
        { projectName: "test-project" },
      ),
    ).rejects.toBeInstanceOf(ConfigMismatchError);
  });

  it("should throw ConfigMismatchError when a ChatPrompt field belongs to a different project", async () => {
    const chatPrompt = makeChatPrompt("other-project");

    await expect(
      client.createConfig(
        { systemPrompt: chatPrompt },
        { projectName: "test-project" },
      ),
    ).rejects.toBeInstanceOf(ConfigMismatchError);
  });

  it("should include the field name in the error message", async () => {
    const prompt = makePrompt("wrong-project");

    await expect(
      client.createConfig(
        { myField: prompt },
        { projectName: "test-project" },
      ),
    ).rejects.toThrow("myField");
  });

  it("should not throw ConfigMismatchError when a Prompt field has projectName matching the config project", async () => {
    const prompt = makePrompt("test-project");

    try {
      await client.createConfig({ systemPrompt: prompt }, { projectName: "test-project" });
    } catch (error) {
      if (error instanceof ConfigMismatchError) {
        throw new Error(`Should not throw ConfigMismatchError, but got: ${(error as Error).message}`);
      }
    }
  });

  it("should not throw ConfigMismatchError when a Prompt field has no projectName set", async () => {
    const prompt = makePrompt(undefined);

    try {
      await client.createConfig({ systemPrompt: prompt }, { projectName: "test-project" });
    } catch (error) {
      if (error instanceof ConfigMismatchError) {
        throw new Error(`Should not throw ConfigMismatchError, but got: ${(error as Error).message}`);
      }
    }
  });

  it("should not throw ConfigMismatchError for plain scalar values (no prompt instances)", async () => {
    const getLatestSpy = vi
      .spyOn(client.api.agentConfigs, "getLatestBlueprint")
      .mockImplementation(() =>
        (() => { throw new OpikApiError({ message: "Not found", statusCode: 404 }); })()
      );
    const createSpy = vi
      .spyOn(client.api.agentConfigs, "createAgentConfig")
      .mockImplementation(mockAPIFunction);
    const getBySpy = vi
      .spyOn(client.api.agentConfigs, "getBlueprintById")
      .mockImplementation(() =>
        createMockHttpResponsePromise({
          id: "bp-1",
          type: "blueprint" as OpikApi.AgentBlueprintPublicType,
          values: [],
        } as OpikApi.AgentBlueprintPublic)
      );
    const retrieveProjectSpy = vi
      .spyOn(client.api.projects, "retrieveProject")
      .mockImplementation(() =>
        createMockHttpResponsePromise({ id: "proj-1", name: "test-project" })
      );

    await expect(
      client.createConfig(
        { temperature: 0.7, model: "gpt-4" },
        { projectName: "test-project" },
      ),
    ).resolves.toBeDefined();

    getLatestSpy.mockRestore();
    createSpy.mockRestore();
    getBySpy.mockRestore();
    retrieveProjectSpy.mockRestore();
  });

  it("should validate against the resolved project when no explicit projectName option is given", async () => {
    // client was created with projectName: "test-project", so that's what gets used
    const prompt = makePrompt("different-project");

    await expect(
      client.createConfig({ systemPrompt: prompt }), // no options.projectName
    ).rejects.toBeInstanceOf(ConfigMismatchError);
  });

  it("should throw ConfigMismatchError for a prompt with a different projectName (e.g. from getPrompt)", async () => {
    // Simulates a prompt returned by getPrompt({ projectName: "other-project" })
    const prompt = new Prompt(
      {
        name: "fetched-prompt",
        prompt: "Hello",
        type: PromptType.MUSTACHE,
        synced: true,
        projectName: "other-project",
      },
      client,
    );

    await expect(
      client.createConfig({ systemPrompt: prompt }, { projectName: "test-project" }),
    ).rejects.toBeInstanceOf(ConfigMismatchError);
  });

  it("should throw ConfigMismatchError for a searchPrompts result from a different project", async () => {
    // searchPrompts uses this.resolveProjectName() → "other-project" if client configured that way
    const otherClient = new Opik({ projectName: "other-project" });
    const prompt = new Prompt(
      {
        name: "search-result",
        prompt: "Hello",
        type: PromptType.MUSTACHE,
        synced: true,
        projectName: "other-project",
      },
      otherClient,
    );

    await expect(
      client.createConfig({ systemPrompt: prompt }, { projectName: "test-project" }),
    ).rejects.toBeInstanceOf(ConfigMismatchError);
  });
});

describe("getOrCreateConfig prompt project validation", () => {
  let client: Opik;
  let retrieveProjectSpy: MockInstance<typeof client.api.projects.retrieveProject>;
  let getBlueprintByEnvSpy: MockInstance<typeof client.api.agentConfigs.getBlueprintByEnv>;
  let getLatestBlueprintSpy: MockInstance<typeof client.api.agentConfigs.getLatestBlueprint>;

  /** Wrap a getOrCreateConfig call inside the track context required by the implementation. */
  function callInsideTrack<T extends Record<string, unknown>>(
    fallback: T,
    opts?: { projectName?: string },
  ) {
    return trackStorage.run(
      { span: { update: vi.fn() }, trace: { update: vi.fn() } } as unknown as Parameters<
        typeof trackStorage.run
      >[0],
      () => client.getOrCreateConfig({ fallback, ...opts }),
    );
  }

  function makePrompt(projectName?: string): Prompt {
    return new Prompt(
      { name: "p", prompt: "Hi", type: PromptType.MUSTACHE, synced: false, projectName },
      client,
    );
  }

  function makeChatPrompt(projectName?: string): ChatPrompt {
    return new ChatPrompt(
      { name: "p", messages: [{ role: "user", content: "Hi" }], type: PromptType.MUSTACHE, synced: false, projectName },
      client,
    );
  }

  beforeEach(() => {
    client = new Opik({ projectName: "test-project" });

    retrieveProjectSpy = vi
      .spyOn(client.api.projects, "retrieveProject")
      .mockImplementation(() =>
        createMockHttpResponsePromise({ id: "proj-1", name: "test-project" })
      );

    // Simulate empty project: both env-tagged and project-wide lookups return 404
    getBlueprintByEnvSpy = vi
      .spyOn(client.api.agentConfigs, "getBlueprintByEnv")
      .mockImplementation(() => {
        throw new OpikApiError({ message: "Not found", statusCode: 404 });
      });

    getLatestBlueprintSpy = vi
      .spyOn(client.api.agentConfigs, "getLatestBlueprint")
      .mockImplementation(() => {
        throw new OpikApiError({ message: "Not found", statusCode: 404 });
      });
  });

  afterEach(() => {
    retrieveProjectSpy.mockRestore();
    getBlueprintByEnvSpy.mockRestore();
    getLatestBlueprintSpy.mockRestore();
  });

  it("should throw ConfigMismatchError when fallback has a Prompt from a different project", async () => {
    const prompt = makePrompt("other-project");

    await expect(callInsideTrack({ systemPrompt: prompt })).rejects.toBeInstanceOf(
      ConfigMismatchError,
    );
  });

  it("should throw ConfigMismatchError when fallback has a ChatPrompt from a different project", async () => {
    const chatPrompt = makeChatPrompt("other-project");

    await expect(callInsideTrack({ systemPrompt: chatPrompt })).rejects.toBeInstanceOf(
      ConfigMismatchError,
    );
  });

  it("should include the field name in the error message", async () => {
    const prompt = makePrompt("wrong-project");

    await expect(callInsideTrack({ myField: prompt })).rejects.toThrow("myField");
  });

  it("should not throw ConfigMismatchError when fallback prompt belongs to the same project", async () => {
    const prompt = makePrompt("test-project");

    try {
      await callInsideTrack({ systemPrompt: prompt });
    } catch (error) {
      if (error instanceof ConfigMismatchError) {
        throw new Error(`Should not throw ConfigMismatchError, but got: ${(error as Error).message}`);
      }
      // Other errors (serialization, blueprint field validation, etc.) are acceptable
    }
  });

  it("should not throw ConfigMismatchError when fallback prompt has no projectName", async () => {
    const prompt = makePrompt(undefined);

    try {
      await callInsideTrack({ systemPrompt: prompt });
    } catch (error) {
      if (error instanceof ConfigMismatchError) {
        throw new Error(`Should not throw ConfigMismatchError, but got: ${(error as Error).message}`);
      }
    }
  });

  it("should validate against the resolved project when no explicit projectName option is given", async () => {
    // client was created with projectName: "test-project"
    const prompt = makePrompt("different-project");

    await expect(callInsideTrack({ systemPrompt: prompt })).rejects.toBeInstanceOf(
      ConfigMismatchError,
    );
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

describe("getOrCreateConfig — prompt readiness before auto-create", () => {
  let client: Opik;
  const notFound = new OpikApiError({ message: "Not found", statusCode: 404, rawResponse: {} as Response, body: undefined });

  beforeEach(() => {
    client = new Opik({ projectName: "test-project" });
    vi.spyOn(client.api.projects, "retrieveProject").mockResolvedValue(
      { id: "project-id", name: "test-project" } as never
    );
  });

  afterEach(() => {
    vi.restoreAllMocks();
    getGlobalBlueprintRegistry().clear();
  });

  function callInsideTrack<T extends Record<string, unknown>>(fallback: T) {
    return trackStorage.run(
      { span: { update: vi.fn() }, trace: { update: vi.fn() } } as unknown as Parameters<typeof trackStorage.run>[0],
      () => client.getOrCreateConfig({ fallback })
    );
  }

  function makeSyncedPromptLike(): InstanceType<typeof Prompt> {
    const obj = Object.create(Prompt.prototype) as InstanceType<typeof Prompt>;
    Object.defineProperty(obj, "synced", { get: () => true, configurable: true });
    Object.defineProperty(obj, "commit", { get: () => "commit-abc", configurable: true });
    return obj;
  }

  function makeNeverSyncingPromptLike(): InstanceType<typeof Prompt> {
    const obj = Object.create(Prompt.prototype) as InstanceType<typeof Prompt>;
    Object.defineProperty(obj, "synced", { get: () => false, configurable: true });
    Object.defineProperty(obj, "commit", { get: () => undefined, configurable: true });
    Object.defineProperty(obj, "ready", { value: () => new Promise<void>(() => {}), configurable: true });
    return obj;
  }

  it("returns backend config when all prompts are already synced", async () => {
    vi.spyOn(client.api.agentConfigs, "getBlueprintByEnv").mockImplementation(() =>
      createMockHttpResponsePromise(mockBlueprintResponse)
    );

    const prompt = makeSyncedPromptLike();
    const config = await callInsideTrack({ temperature: prompt });

    expect(config.isFallback).toBe(false);
    expect(config.blueprintId).toBe("blueprint-id-1");
  });

  it("returns backend config even when prompt is unsynced", async () => {
    vi.spyOn(client.api.agentConfigs, "getBlueprintByEnv").mockImplementation(() =>
      createMockHttpResponsePromise(mockBlueprintResponse)
    );

    const prompt = makeNeverSyncingPromptLike();
    const config = await callInsideTrack({ temperature: prompt });

    expect(config.isFallback).toBe(false);
    expect(config.blueprintId).toBe("blueprint-id-1");
  });

  it("returns fallback when prompt sync times out before auto-creating config", async () => {
    vi.useFakeTimers();

    try {
      // Empty project: both env and latest return 404
      vi.spyOn(client.api.agentConfigs, "getBlueprintByEnv").mockRejectedValue(notFound);
      vi.spyOn(client.api.agentConfigs, "getLatestBlueprint").mockRejectedValue(notFound);
      const createSpy = vi.spyOn(client.api.agentConfigs, "createAgentConfig").mockImplementation(mockAPIFunction);

      // Prompt with ready() that never resolves (simulates sync hanging indefinitely)
      const prompt = makeNeverSyncingPromptLike();

      const promise = callInsideTrack({ system_prompt: prompt });

      // Advance timers in chunks past AGENT_CONFIG_PROMPT_READY_TIMEOUT_MS (5500ms).
      // IMPORTANT: We use chunked advancement with vi.advanceTimersByTimeAsync() instead of a single
      // large advance. This is necessary due to a Vitest fake timer edge case with Promise.allSettled():
      // _allPromptsSynced() uses Promise.race([Promise.allSettled(prompts.map(v => v.ready())), timeout]).
      // When advancing timers in one large chunk, the microtask queue (Promise.then callbacks) doesn't
      // fully flush before the race evaluates, causing the promise chain to hang. Chunked advancement
      // allows the event loop to fully process microtasks between each timer advance, avoiding the issue.
      await vi.advanceTimersByTimeAsync(2000);
      await vi.advanceTimersByTimeAsync(2000);
      await vi.advanceTimersByTimeAsync(2000);

      const config = await promise;

      // Unsynced prompts were not persisted to backend due to timeout
      expect(createSpy).not.toHaveBeenCalled();
      expect(config.isFallback).toBe(true);
      expect(config.system_prompt).toBe(prompt);
    } finally {
      vi.useRealTimers();
    }
  });

  it("returns fallback when prompt sync failed (ready resolved but synced is false)", async () => {
    // Empty project: both env and latest return 404
    vi.spyOn(client.api.agentConfigs, "getBlueprintByEnv").mockRejectedValue(notFound);
    vi.spyOn(client.api.agentConfigs, "getLatestBlueprint").mockRejectedValue(notFound);
    const createSpy = vi.spyOn(client.api.agentConfigs, "createAgentConfig").mockImplementation(mockAPIFunction);

    // Prompt whose ready() resolves immediately but synced stays false (sync failed)
    const prompt = Object.create(Prompt.prototype) as InstanceType<typeof Prompt>;
    Object.defineProperty(prompt, "synced", { get: () => false, configurable: true });
    Object.defineProperty(prompt, "commit", { get: () => undefined, configurable: true });
    Object.defineProperty(prompt, "ready", { value: () => Promise.resolve(), configurable: true });

    const config = await callInsideTrack({ system_prompt: prompt });

    expect(createSpy).not.toHaveBeenCalled();
    expect(config.isFallback).toBe(true);
    expect(config.system_prompt).toBe(prompt);
  });
});
