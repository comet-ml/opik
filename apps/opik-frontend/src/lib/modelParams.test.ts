import { afterEach, describe, expect, it } from "vitest";

import {
  adjustConfigForModel,
  getModelParams,
  getNearestEffortLevel,
  getParamControls,
  ParamBinding,
  resolveEffortLevel,
  resolveMaxOutputTokens,
  resolveSampling,
  toParamsRoute,
} from "@/lib/modelParams";
import {
  resetModelRegistryStoreForTesting,
  setLatestModelParams,
  setLatestProviderProfiles,
} from "@/lib/modelRegistryStore";
import { ModelParams } from "@/types/modelParams";

const BINDING: ParamBinding = {
  temperature: "temperature",
  top_p: "topP",
  max_output_tokens: "maxCompletionTokens",
  effort: "thinkingEffort",
};

const SAMPLING_MODEL: ModelParams = {
  temperature: { accepted: true, min: 0, max: 1, default: 0 },
  top_p: { accepted: true, min: 0, max: 1, default: 1 },
  max_output_tokens: { accepted: true, min: 1, max: 64000, default: 4000 },
  effort: {
    accepted: true,
    levels: ["low", "medium", "high"],
    default: "high",
  },
  sampling_exclusive: true,
};

const NO_SAMPLING_MODEL: ModelParams = {
  temperature: { accepted: false },
  top_p: { accepted: false },
  max_output_tokens: { accepted: true, min: 1, max: 128000, default: 4000 },
  effort: {
    accepted: true,
    levels: ["low", "medium", "high", "xhigh", "max"],
    default: "medium",
  },
  sampling_exclusive: true,
};

const NO_EFFORT_MODEL: ModelParams = {
  ...SAMPLING_MODEL,
  effort: { accepted: false },
};

describe("getModelParams", () => {
  const PROFILE: ModelParams = { temperature: { accepted: false } };
  const ROUTED: ModelParams = {
    ...SAMPLING_MODEL,
    routes: { responses_api: NO_SAMPLING_MODEL },
  };

  afterEach(() => {
    resetModelRegistryStoreForTesting();
  });

  it("answers a listed model with its own params", () => {
    setLatestModelParams(
      new Map([["acme", new Map([["acme-1", SAMPLING_MODEL]])]]),
    );
    setLatestProviderProfiles({ acme: PROFILE });

    expect(getModelParams("acme", "acme-1")).toBe(SAMPLING_MODEL);
  });

  it("answers an unlisted model with its provider's profile", () => {
    setLatestProviderProfiles({ acme: PROFILE });

    expect(getModelParams("acme", "acme-unlisted")).toBe(PROFILE);
  });

  it("answers nothing for a provider without a profile", () => {
    expect(getModelParams("acme", "acme-1")).toBeUndefined();
  });

  it("looks up the route's own params, and the base ones for a route it does not list", () => {
    setLatestModelParams(new Map([["acme", new Map([["acme-1", ROUTED]])]]));

    expect(getModelParams("acme", "acme-1", "responses_api")).toBe(
      NO_SAMPLING_MODEL,
    );
    expect(getModelParams("acme", "acme-1", "other_route")).toBe(ROUTED);
  });
});

describe("resolveEffortLevel", () => {
  it.each<[string, unknown[], string | undefined]>([
    ["keeps the first stored level the model offers", ["xhigh", "low"], "low"],
    ["falls back to the default", ["adaptive"], "high"],
    ["falls back to the default when nothing is stored", [], "high"],
  ])("%s", (_, stored, expected) => {
    expect(resolveEffortLevel(SAMPLING_MODEL.effort, stored)).toBe(expected);
  });

  it("answers nothing for a model that takes no effort", () => {
    expect(resolveEffortLevel(NO_EFFORT_MODEL.effort, ["low"])).toBeUndefined();
  });

  it("uses the first level when the default is not one of them", () => {
    expect(
      resolveEffortLevel(
        { accepted: true, levels: ["low", "high"], default: "medium" },
        [],
      ),
    ).toBe("low");
  });
});

describe("getNearestEffortLevel", () => {
  it.each<[string, string[], string | undefined]>([
    ["xhigh", ["low", "medium", "high", "max"], "high"],
    ["max", ["low", "medium", "high"], "high"],
    ["minimal", ["low", "medium", "high"], "low"],
    ["none", ["high", "low"], "low"],
    ["minimal", ["none", "low", "medium", "high"], "low"],
    ["none", ["none", "low", "medium"], "none"],
    ["low", ["none"], undefined],
    ["adaptive", ["low", "high"], undefined],
  ])("moves %s to the nearest of %j", (level, levels, expected) => {
    expect(getNearestEffortLevel(levels, level)).toBe(expected);
  });
});

describe("resolveMaxOutputTokens", () => {
  const param = SAMPLING_MODEL.max_output_tokens;

  it.each<[number | undefined | null, number]>([
    [undefined, 4000],
    [null, 4000],
    [0, 4000],
    [1000.7, 1000],
    [-5, 1],
    [999999, 64000],
  ])("sends %s as %s", (value, expected) => {
    expect(resolveMaxOutputTokens(param, value)).toBe(expected);
  });

  it("sends nothing for a model that refuses it", () => {
    expect(resolveMaxOutputTokens({ accepted: false }, 1000)).toBeUndefined();
  });
});

describe("resolveSampling", () => {
  it.each<[string, { temperature?: number; topP?: number }, object]>([
    [
      "temperature wins over top_p",
      { temperature: 0.5, topP: 0.9 },
      { temperature: 0.5 },
    ],
    ["top_p alone is kept", { topP: 0.9 }, { topP: 0.9 }],
    ["neither set takes the temperature default", {}, { temperature: 0 }],
  ])("exclusive: %s", (_, configs, expected) => {
    expect(resolveSampling(SAMPLING_MODEL, configs)).toEqual(expected);
  });

  it("sends neither to a model that refuses both", () => {
    expect(
      resolveSampling(NO_SAMPLING_MODEL, { temperature: 0.5, topP: 0.9 }),
    ).toEqual({});
  });

  it("keeps both on a model that takes them together, and invents neither", () => {
    const independent = { ...SAMPLING_MODEL, sampling_exclusive: false };

    expect(
      resolveSampling(independent, { temperature: 0.5, topP: 0.9 }),
    ).toEqual({ temperature: 0.5, topP: 0.9 });
    expect(resolveSampling(independent, {})).toEqual({});
  });
});

describe("getParamControls", () => {
  it("offers the sampling choice, the token limit and the effort, in that order", () => {
    expect(getParamControls(SAMPLING_MODEL, BINDING)).toEqual([
      {
        kind: "sampling_choice",
        temperature: {
          kind: "number",
          param: "temperature",
          configKey: "temperature",
          min: 0,
          max: 1,
          default: 0,
        },
        topP: {
          kind: "number",
          param: "top_p",
          configKey: "topP",
          min: 0,
          max: 1,
          default: 1,
        },
      },
      {
        kind: "number",
        param: "max_output_tokens",
        configKey: "maxCompletionTokens",
        min: 1,
        max: 64000,
        default: 4000,
      },
      {
        kind: "effort",
        configKey: "thinkingEffort",
        levels: ["low", "medium", "high"],
        default: "high",
      },
    ]);
  });

  it("offers no sampling control to a model that refuses both", () => {
    expect(
      getParamControls(NO_SAMPLING_MODEL, BINDING).map((c) => c.kind),
    ).toEqual(["number", "effort"]);
  });

  it("offers no effort control to a model that takes none", () => {
    expect(
      getParamControls(NO_EFFORT_MODEL, BINDING).map((c) => c.kind),
    ).toEqual(["sampling_choice", "number"]);
  });

  it("leaves out what the surface cannot store", () => {
    const controls = getParamControls(
      SAMPLING_MODEL,
      BINDING,
      new Set(["topP", "maxCompletionTokens"]),
    );

    expect(controls.map((c) => c.kind)).toEqual(["sampling_choice", "effort"]);
    expect(controls[0]).toMatchObject({ topP: undefined });
  });

  it("offers independent sliders when the model takes both together", () => {
    const controls = getParamControls(
      { ...SAMPLING_MODEL, sampling_exclusive: false },
      BINDING,
    );

    expect(
      controls.map((c) => (c.kind === "number" ? c.param : c.kind)),
    ).toEqual(["temperature", "top_p", "max_output_tokens", "effort"]);
  });

  it("offers no slider for a number without a range", () => {
    const controls = getParamControls(
      { max_output_tokens: { accepted: true, default: 4000 } },
      BINDING,
    );

    expect(controls).toEqual([]);
  });

  it("offers the penalties after the token limit, for a provider that binds them", () => {
    const penalty = { accepted: true, min: -2, max: 2, default: 0 };
    const controls = getParamControls(
      {
        ...SAMPLING_MODEL,
        sampling_exclusive: false,
        frequency_penalty: penalty,
        presence_penalty: { accepted: false },
      },
      {
        ...BINDING,
        frequency_penalty: "frequencyPenalty",
        presence_penalty: "presencePenalty",
      },
    );

    expect(
      controls.map((c) => (c.kind === "number" ? c.param : c.kind)),
    ).toEqual([
      "temperature",
      "top_p",
      "max_output_tokens",
      "frequency_penalty",
      "effort",
    ]);
  });

  it("offers nothing the provider has no config key for", () => {
    expect(
      getParamControls(SAMPLING_MODEL, { temperature: "temperature" }),
    ).toHaveLength(1);
  });
});

describe("adjustConfigForModel", () => {
  it("keeps an effort the next model offers and returns the same config", () => {
    const config = { thinkingEffort: "medium", temperature: 0.4 };

    expect(adjustConfigForModel(config, SAMPLING_MODEL, BINDING)).toBe(config);
  });

  it("moves an effort the next model does not offer to the nearest level", () => {
    expect(
      adjustConfigForModel(
        { thinkingEffort: "xhigh" },
        SAMPLING_MODEL,
        BINDING,
      ),
    ).toEqual({ thinkingEffort: "high" });
  });

  it("uses the default for an effort that is not a level at all", () => {
    expect(
      adjustConfigForModel(
        { thinkingEffort: "adaptive" },
        NO_SAMPLING_MODEL,
        BINDING,
      ),
    ).toEqual({ thinkingEffort: "medium" });
  });

  it("drops the effort for a model that takes none", () => {
    expect(
      adjustConfigForModel({ thinkingEffort: "low" }, NO_EFFORT_MODEL, BINDING),
    ).toEqual({ thinkingEffort: undefined });
  });

  it("leaves an unset effort unset", () => {
    const config = { maxCompletionTokens: 4000 };

    expect(adjustConfigForModel(config, SAMPLING_MODEL, BINDING)).toBe(config);
  });

  it("writes back no clamped number and keeps a refused one", () => {
    const config = { temperature: 0.4, maxCompletionTokens: 100000 };

    expect(adjustConfigForModel(config, NO_SAMPLING_MODEL, BINDING)).toBe(
      config,
    );
  });
});

describe("toParamsRoute", () => {
  it("names the Responses route only for a key set to it", () => {
    expect(toParamsRoute("responses_api")).toBe("responses_api");
    expect(toParamsRoute("chat_completions_api")).toBeUndefined();
    expect(toParamsRoute(undefined)).toBeUndefined();
  });
});
