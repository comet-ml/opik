import { afterEach, beforeEach, describe, it, expect } from "vitest";
import {
  hasPythonSyntaxError,
  CodeMetricParamsSchema,
  OptimizationConfigSchema,
  OptimizationConfigFormType,
  convertFormDataToStudioConfig,
  convertOptimizationStudioToFormData,
  getOptimizationDefaultConfigByProvider,
} from "./schema";
import {
  COMPOSED_PROVIDER_TYPE,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import {
  DEFAULT_CUSTOM_CONFIGS,
  DEFAULT_GEMINI_CONFIGS,
  DEFAULT_OPEN_AI_CONFIGS,
  DEFAULT_OPEN_ROUTER_CONFIGS,
} from "@/constants/llm";
import { METRIC_TYPE, OPTIMIZER_TYPE } from "@/types/optimizations";
import { LLM_MESSAGE_ROLE } from "@/types/llm";
import { resolveEffort } from "@/lib/modelUtils";
import {
  ModelFlags,
  resetModelRegistryStoreForTesting,
  setLatestModelFlags,
} from "@/lib/modelRegistryStore";

const VALID_CODE_METRIC = `
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult


class LabelMatch(BaseMetric):
    def __init__(self, name: str = "label_match"):
        super().__init__(name=name)

    def score(self, output: str, **kwargs) -> ScoreResult:
        label = str(kwargs.get("label", "")).strip().lower()
        return ScoreResult(name=self.name, value=1.0 if label else 0.0)
`;

// Missing the colon after the class definition — a plain syntax error, not a
// semantic/runtime one, so the Lezer-based check must flag it.
const SYNTAX_ERROR_CODE_METRIC = `
from opik.evaluation.metrics import BaseMetric


class BrokenMetric(BaseMetric)
    def __init__(self, name: str = "broken"):
        super().__init__(name=name)
`;

describe("hasPythonSyntaxError", () => {
  it("returns false for valid Python", () => {
    expect(hasPythonSyntaxError(VALID_CODE_METRIC)).toBe(false);
  });

  it("returns true for a missing colon", () => {
    expect(hasPythonSyntaxError(SYNTAX_ERROR_CODE_METRIC)).toBe(true);
  });

  it("returns false for valid code that reads a required kwarg (no false positive)", () => {
    // Regression guard: the syntax check must never flag a semantically
    // dynamic (but syntactically valid) access like kwargs["x"] — only real
    // syntax errors are in scope (OPIK-7172).
    const code = `
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult


class StrictKwargMetric(BaseMetric):
    def score(self, output, **kwargs):
        expected = kwargs["expected_value"]
        return ScoreResult(name=self.name, value=1.0 if output == expected else 0.0)
`;
    expect(hasPythonSyntaxError(code)).toBe(false);
  });

  it("flags empty code as a parse error (callers guard on `code &&` instead)", () => {
    // The Lezer parser reports an empty program as an error node, so this
    // function alone would flag "" too. `CodeMetricParamsSchema` below never
    // hits that path in practice: it only calls this once `.min(1)` has
    // already confirmed `code` is non-empty (`params.code && ...`).
    expect(hasPythonSyntaxError("")).toBe(true);
  });
});

describe("CodeMetricParamsSchema", () => {
  it("accepts valid Python code", () => {
    const result = CodeMetricParamsSchema.safeParse({
      code: VALID_CODE_METRIC,
    });
    expect(result.success).toBe(true);
  });

  it("rejects code with a syntax error and anchors the issue to the 'code' field", () => {
    const result = CodeMetricParamsSchema.safeParse({
      code: SYNTAX_ERROR_CODE_METRIC,
    });
    expect(result.success).toBe(false);
    if (!result.success) {
      const codeIssue = result.error.issues.find(
        (issue) => issue.path.join(".") === "code",
      );
      expect(codeIssue).toBeDefined();
      expect(codeIssue?.message).toMatch(/syntax error/i);
    }
  });

  it("accepts an optional rename-capable arguments map", () => {
    const result = CodeMetricParamsSchema.safeParse({
      code: VALID_CODE_METRIC,
      arguments: { reference: "expected_answer" },
    });
    expect(result.success).toBe(true);
  });
});

describe("OptimizationConfigSchema — code metric syntax-error submission block", () => {
  const baseConfig: Omit<
    OptimizationConfigFormType,
    "metricType" | "metricParams"
  > = {
    name: "",
    datasetId: "dataset-1",
    optimizerType: OPTIMIZER_TYPE.GEPA,
    optimizerParams: {},
    messages: [
      {
        id: "1",
        role: LLM_MESSAGE_ROLE.user,
        content: "Classify: {{text}}",
      },
    ],
    modelName: "anthropic/claude-haiku",
    modelConfig: {},
  };

  it("passes end-to-end validation for a valid code metric", () => {
    const result = OptimizationConfigSchema.safeParse({
      ...baseConfig,
      metricType: METRIC_TYPE.CODE,
      metricParams: { code: VALID_CODE_METRIC },
    });
    expect(result.success).toBe(true);
  });

  it("blocks submission end-to-end when the code metric has a syntax error", () => {
    // This is the exact resolver (`zodResolver(OptimizationConfigSchema)`)
    // NewRunSidebar wires up, so a failing parse here is what stops RHF's
    // `handleSubmit` from ever invoking the submit callback in the real form.
    const result = OptimizationConfigSchema.safeParse({
      ...baseConfig,
      metricType: METRIC_TYPE.CODE,
      metricParams: { code: SYNTAX_ERROR_CODE_METRIC },
    });
    expect(result.success).toBe(false);
  });
});

describe("convertOptimizationStudioToFormData — seeded prompt shape", () => {
  // A new run must start as system + user: the system message holds the
  // instructions (the only role a Studio run optimizes) and the user message
  // holds the template variables, so the optimizer cannot rewrite the message
  // carrying them (OPIK-7510). Seeding a lone user message did the opposite.
  it("seeds a system and a user message for a new run", () => {
    const { messages } = convertOptimizationStudioToFormData(undefined, [
      "gpt-4o-mini",
    ]);

    expect(messages.map((m) => m.role)).toEqual([
      LLM_MESSAGE_ROLE.system,
      LLM_MESSAGE_ROLE.user,
    ]);
    expect(messages.every((m) => m.content === "")).toBe(true);
    expect(new Set(messages.map((m) => m.id)).size).toBe(2);
  });

  it("keeps an existing run's messages untouched", () => {
    const { messages } = convertOptimizationStudioToFormData(
      {
        studio_config: {
          prompt: {
            messages: [{ role: "user", content: "Answer {question}" }],
          },
          // optimizer/evaluation are read unconditionally by the converter, so
          // a rerun payload always carries them.
          optimizer: { type: OPTIMIZER_TYPE.GEPA },
          evaluation: { metrics: [{ type: METRIC_TYPE.EQUALS }] },
        },
      } as never,
      ["gpt-4o-mini"],
    );

    expect(messages).toHaveLength(1);
    expect(messages[0].role).toBe(LLM_MESSAGE_ROLE.user);
    expect(messages[0].content).toBe("Answer {question}");
  });
});

describe("convertFormDataToStudioConfig — Gemini thinking level", () => {
  const formData = (modelConfig: Record<string, unknown>) =>
    ({
      name: "run",
      datasetId: "d",
      optimizerType: OPTIMIZER_TYPE.GEPA,
      optimizerParams: {},
      metricType: METRIC_TYPE.EQUALS,
      metricParams: {},
      messages: [{ id: "1", role: LLM_MESSAGE_ROLE.user, content: "hi" }],
      modelName: PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE,
      modelConfig,
    }) as unknown as OptimizationConfigFormType;

  // The optimizer renders the same Gemini config panel as the playground, so a level picked
  // there has to survive serialization instead of being dropped as a flat field.
  it("nests a selected thinking level under custom_parameters", () => {
    const config = convertFormDataToStudioConfig(
      formData({ temperature: 0.5, thinkingLevel: "off" }),
      "my-dataset",
    );

    expect(config.llm_model.parameters).toMatchObject({
      temperature: 0.5,
      custom_parameters: { thinking: { level: "off" } },
    });
    expect(config.llm_model.parameters).not.toHaveProperty("thinking_level");
  });

  // The control shows the model's default even when the config holds no level, so the request has
  // to carry that same default rather than silently falling back to the provider's own.
  it("sends the model's default when the config holds no level", () => {
    const config = convertFormDataToStudioConfig(
      formData({ temperature: 0.5 }),
      "my-dataset",
    );

    expect(config.llm_model.parameters).toMatchObject({
      custom_parameters: { thinking: { level: "off" } },
    });
  });

  it("adds nothing for a model without thinking support", () => {
    const config = convertFormDataToStudioConfig(
      {
        ...formData({ temperature: 0.5 }),
        modelName: PROVIDER_MODEL_TYPE.GEMINI_2_0_FLASH,
      } as unknown as OptimizationConfigFormType,
      "my-dataset",
    );

    expect(
      (config.llm_model.parameters as Record<string, unknown>)
        .custom_parameters,
    ).toBeUndefined();
  });
});

describe("convertFormDataToStudioConfig — controls the optimizer does not offer", () => {
  const formData = (modelConfig: Record<string, unknown>) =>
    ({
      name: "run",
      datasetId: "d",
      optimizerType: OPTIMIZER_TYPE.GEPA,
      optimizerParams: {},
      metricType: METRIC_TYPE.EQUALS,
      metricParams: {},
      messages: [{ id: "1", role: LLM_MESSAGE_ROLE.user, content: "hi" }],
      modelName: PROVIDER_MODEL_TYPE.GPT_4O,
      modelConfig,
    }) as unknown as OptimizationConfigFormType;

  // Throttling and max concurrency drive the playground's batch runner, so the optimizer panel
  // does not offer them. Reloading a run saved before that leaves the values in the form, and
  // serializing them forwards a parameter nobody can see to the provider.
  it("drops the playground runner parameters it no longer shows", () => {
    const parameters = convertFormDataToStudioConfig(
      formData({ temperature: 0.5, throttling: 3, maxConcurrentRequests: 8 }),
      "my-dataset",
    ).llm_model.parameters as Record<string, unknown>;

    expect(parameters).toEqual({ temperature: 0.5 });
  });

  // The optimizer reaches OpenAI through LiteLLM on Chat Completions whatever the key's pipeline
  // mode, and Chat Completions rejects max.
  it("sends a stored max reasoning effort as high", () => {
    const parameters = convertFormDataToStudioConfig(
      {
        ...formData({ maxCompletionTokens: 4000, reasoningEffort: "max" }),
        modelName: PROVIDER_MODEL_TYPE.GPT_6_SOL,
      } as unknown as OptimizationConfigFormType,
      "my-dataset",
    ).llm_model.parameters as Record<string, unknown>;

    expect(parameters.reasoning_effort).toBe("high");
  });
});

describe("convertFormDataToStudioConfig — Anthropic effort", () => {
  const formData = (modelConfig: Record<string, unknown>) =>
    ({
      name: "run",
      datasetId: "d",
      optimizerType: OPTIMIZER_TYPE.GEPA,
      optimizerParams: {},
      metricType: METRIC_TYPE.EQUALS,
      metricParams: {},
      messages: [{ id: "1", role: LLM_MESSAGE_ROLE.user, content: "hi" }],
      modelName: PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6,
      modelConfig,
    }) as unknown as OptimizationConfigFormType;

  it("nests the selected effort under custom_parameters.output_config", () => {
    const parameters = convertFormDataToStudioConfig(
      formData({ temperature: 0.5, thinkingEffort: "low" }),
      "my-dataset",
    ).llm_model.parameters as Record<string, unknown>;

    expect(parameters.custom_parameters).toEqual({
      output_config: { effort: "low" },
    });
    expect(parameters).not.toHaveProperty("thinking_effort");
  });

  it("keeps a saved run's effort when the run is reloaded and resubmitted", () => {
    const saved = {
      studio_config: {
        prompt: { messages: [{ role: "user", content: "hi" }] },
        llm_model: {
          model: PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6,
          parameters: {
            temperature: 0.5,
            custom_parameters: { output_config: { effort: "low" } },
          },
        },
        optimizer: { type: OPTIMIZER_TYPE.GEPA },
        evaluation: { metrics: [{ type: METRIC_TYPE.EQUALS }] },
      },
    } as never;

    const reloaded = convertOptimizationStudioToFormData(saved, [
      PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6,
    ]);
    const parameters = convertFormDataToStudioConfig(reloaded, "my-dataset")
      .llm_model.parameters as Record<string, unknown>;

    expect(parameters.custom_parameters).toEqual({
      output_config: { effort: "low" },
    });
  });
});

describe("getOptimizationDefaultConfigByProvider", () => {
  it.each([
    {
      provider: PROVIDER_TYPE.OPEN_AI,
      model: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      expected: {
        temperature: 0,
        maxCompletionTokens: DEFAULT_OPEN_AI_CONFIGS.MAX_COMPLETION_TOKENS,
        topP: DEFAULT_OPEN_AI_CONFIGS.TOP_P,
        frequencyPenalty: DEFAULT_OPEN_AI_CONFIGS.FREQUENCY_PENALTY,
        presencePenalty: DEFAULT_OPEN_AI_CONFIGS.PRESENCE_PENALTY,
      },
    },
    {
      provider: PROVIDER_TYPE.GEMINI,
      model: PROVIDER_MODEL_TYPE.GEMINI_2_0_FLASH,
      expected: {
        temperature: 0,
        maxCompletionTokens: DEFAULT_GEMINI_CONFIGS.MAX_COMPLETION_TOKENS,
        topP: DEFAULT_GEMINI_CONFIGS.TOP_P,
      },
    },
    {
      provider: PROVIDER_TYPE.OPEN_ROUTER,
      model: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI,
      expected: {
        maxTokens: DEFAULT_OPEN_ROUTER_CONFIGS.MAX_TOKENS,
        temperature: 0,
        topP: DEFAULT_OPEN_ROUTER_CONFIGS.TOP_P,
        topK: DEFAULT_OPEN_ROUTER_CONFIGS.TOP_K,
        frequencyPenalty: DEFAULT_OPEN_ROUTER_CONFIGS.FREQUENCY_PENALTY,
        presencePenalty: DEFAULT_OPEN_ROUTER_CONFIGS.PRESENCE_PENALTY,
        repetitionPenalty: DEFAULT_OPEN_ROUTER_CONFIGS.REPETITION_PENALTY,
        minP: DEFAULT_OPEN_ROUTER_CONFIGS.MIN_P,
        topA: DEFAULT_OPEN_ROUTER_CONFIGS.TOP_A,
      },
    },
    {
      provider: PROVIDER_TYPE.CUSTOM,
      model: "custom-llm/mock-model",
      expected: {
        temperature: 0,
        maxCompletionTokens: DEFAULT_CUSTOM_CONFIGS.MAX_COMPLETION_TOKENS,
        topP: DEFAULT_CUSTOM_CONFIGS.TOP_P,
        frequencyPenalty: DEFAULT_CUSTOM_CONFIGS.FREQUENCY_PENALTY,
        presencePenalty: DEFAULT_CUSTOM_CONFIGS.PRESENCE_PENALTY,
        custom_parameters: null,
      },
    },
  ])(
    "seeds the playground's $provider controls, without the runner ones",
    ({ provider, model, expected }) => {
      expect(
        getOptimizationDefaultConfigByProvider(
          provider as COMPOSED_PROVIDER_TYPE,
          model as PROVIDER_MODEL_TYPE,
        ),
      ).toEqual(expected);
    },
  );

  it.each([
    { model: PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6, temperature: 0 },
    { model: PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6, temperature: 0 },
    { model: PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7, temperature: undefined },
    { model: PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5, temperature: undefined },
  ])(
    "seeds Anthropic temperature $temperature for $model",
    ({ model, temperature }) => {
      const config = getOptimizationDefaultConfigByProvider(
        PROVIDER_TYPE.ANTHROPIC as COMPOSED_PROVIDER_TYPE,
        model,
      ) as Record<string, unknown>;

      expect(config.temperature).toBe(temperature);
    },
  );
});

describe("convertFormDataToStudioConfig — parameter names", () => {
  const formData = (model: string, modelConfig: Record<string, unknown>) =>
    ({
      name: "run",
      datasetId: "d",
      optimizerType: OPTIMIZER_TYPE.GEPA,
      optimizerParams: {},
      metricType: METRIC_TYPE.EQUALS,
      metricParams: {},
      messages: [{ id: "1", role: LLM_MESSAGE_ROLE.user, content: "hi" }],
      modelName: model,
      modelConfig,
    }) as unknown as OptimizationConfigFormType;

  // The runner hands these to LiteLLM unchanged, and an unknown name such as topP is dropped on the
  // way to the provider without an error.
  it.each([
    {
      model: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      modelConfig: {
        temperature: 0.3,
        maxCompletionTokens: 123,
        topP: 0.5,
        frequencyPenalty: 0.1,
        presencePenalty: 0.2,
      },
      expected: {
        temperature: 0.3,
        max_completion_tokens: 123,
        top_p: 0.5,
        frequency_penalty: 0.1,
        presence_penalty: 0.2,
      },
    },
    {
      model: PROVIDER_MODEL_TYPE.GPT_5_NANO,
      modelConfig: { maxCompletionTokens: 500, reasoningEffort: "low" },
      expected: { max_completion_tokens: 500, reasoning_effort: "low" },
    },
    {
      model: PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5,
      modelConfig: { topP: 0.55, maxCompletionTokens: 77 },
      expected: { top_p: 0.55, max_completion_tokens: 77 },
    },
    {
      model: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI,
      modelConfig: { maxTokens: 300, temperature: 0.4, topK: 3 },
      expected: {
        max_tokens: 300,
        temperature: 0.4,
        custom_parameters: { top_k: 3 },
      },
    },
  ])(
    "sends $model settings under the API's names",
    ({ model, modelConfig, expected }) => {
      expect(
        convertFormDataToStudioConfig(formData(model, modelConfig), "ds")
          .llm_model.parameters,
      ).toEqual(expected);
    },
  );
});

describe("convertOptimizationStudioToFormData — re-run round trip", () => {
  const savedRun = (model: string, parameters: Record<string, unknown>) =>
    ({
      studio_config: {
        prompt: { messages: [{ role: "user", content: "hi" }] },
        llm_model: { model, parameters },
        optimizer: { type: OPTIMIZER_TYPE.GEPA },
        evaluation: { metrics: [{ type: METRIC_TYPE.EQUALS }] },
      },
    }) as never;

  const rerun = (model: string, parameters: Record<string, unknown>) => {
    const form = convertOptimizationStudioToFormData(
      savedRun(model, parameters),
      [model],
    );
    return {
      modelConfig: form.modelConfig as Record<string, unknown>,
      sent: convertFormDataToStudioConfig(form, "ds").llm_model.parameters,
    };
  };

  // Runs saved before the parameters took the API's names hold the form's own keys.
  it.each([
    { saved: { top_p: 0.55, max_completion_tokens: 77 } },
    { saved: { topP: 0.55, maxCompletionTokens: 77 } },
  ])(
    "keeps a Claude run's top P instead of the default temperature ($saved)",
    ({ saved }) => {
      const { modelConfig, sent } = rerun(
        PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5,
        saved,
      );

      expect(modelConfig).toMatchObject({
        topP: 0.55,
        maxCompletionTokens: 77,
      });
      expect(modelConfig.temperature).toBeUndefined();
      expect(sent).toEqual({ top_p: 0.55, max_completion_tokens: 77 });
    },
  );

  it.each([
    PROVIDER_MODEL_TYPE.GEMINI_3_FLASH,
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_FLASH_PREVIEW,
  ])(
    "shows a %s run's saved thinking level instead of the model default",
    (model) => {
      const { modelConfig, sent } = rerun(model, {
        custom_parameters: { thinking: { level: "low" } },
      });

      expect(modelConfig.thinkingLevel).toBe("low");
      expect(sent).toMatchObject({
        custom_parameters: { thinking: { level: "low" } },
      });
    },
  );

  it("shows a Claude run's saved effort instead of the model default", () => {
    const { modelConfig, sent } = rerun(PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6, {
      temperature: 0.6,
      max_completion_tokens: 4000,
      custom_parameters: { output_config: { effort: "low" } },
    });

    expect(modelConfig.thinkingEffort).toBe("low");
    expect(sent).toEqual({
      temperature: 0.6,
      max_completion_tokens: 4000,
      custom_parameters: { output_config: { effort: "low" } },
    });
  });

  it("keeps an OpenRouter run's settings nested under custom_parameters", () => {
    const { modelConfig, sent } = rerun(
      PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI,
      {
        max_tokens: 300,
        temperature: 0.4,
        custom_parameters: { top_k: 3, min_p: 0.1 },
      },
    );

    expect(modelConfig).toMatchObject({ maxTokens: 300, topK: 3, minP: 0.1 });
    expect(sent).toMatchObject({
      max_tokens: 300,
      temperature: 0.4,
      custom_parameters: { top_k: 3, min_p: 0.1 },
    });
  });

  it("prefers an OpenRouter run's nested value over a flat copy of it", () => {
    const { modelConfig, sent } = rerun(
      PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI,
      { top_k: 40, custom_parameters: { top_k: 3 } },
    );

    expect(modelConfig.topK).toBe(3);
    expect(sent).toMatchObject({ custom_parameters: { top_k: 3 } });
    expect(sent).not.toHaveProperty("top_k");
  });

  // The demo template is written with max_tokens, which OpenAI models take as max_completion_tokens
  // in the form; sending both makes OpenAI reject the request.
  it("reads max_tokens as the max output tokens of a non-OpenRouter model", () => {
    const { modelConfig, sent } = rerun(PROVIDER_MODEL_TYPE.GPT_4O_MINI, {
      temperature: 0.7,
      max_tokens: 500,
    });

    expect(modelConfig.maxCompletionTokens).toBe(500);
    expect(modelConfig).not.toHaveProperty("maxTokens");
    expect(sent).toMatchObject({
      temperature: 0.7,
      max_completion_tokens: 500,
    });
    expect(sent).not.toHaveProperty("max_tokens");
  });
});

describe("convertOptimizationStudioToFormData — OpenRouter effort on re-run", () => {
  const REASONING_LISTS: Record<
    string,
    Pick<ModelFlags, "supportedParameters" | "reasoningEfforts">
  > = {
    [PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO]: {
      supportedParameters: [
        "max_completion_tokens",
        "max_tokens",
        "reasoning",
        "reasoning_effort",
      ],
      reasoningEfforts: ["high", "medium", "low", "minimal"],
    },
    [PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_FLASH_PREVIEW]: {
      supportedParameters: [
        "max_tokens",
        "reasoning",
        "reasoning_effort",
        "temperature",
        "top_p",
      ],
      reasoningEfforts: ["high", "medium", "low", "minimal"],
    },
    [PROVIDER_MODEL_TYPE.OPENAI_O3_MINI_HIGH]: {
      supportedParameters: ["max_tokens", "reasoning", "reasoning_effort"],
      reasoningEfforts: ["high"],
    },
  };

  const loadRegistryLists = () =>
    setLatestModelFlags(
      new Map(
        Object.entries(REASONING_LISTS).map(([model, lists]) => [
          model,
          { reasoning: true, structuredOutput: false, ...lists },
        ]),
      ),
    );

  const savedRun = (model: string, parameters: Record<string, unknown>) =>
    ({
      studio_config: {
        prompt: { messages: [{ role: "user", content: "hi" }] },
        llm_model: { model, parameters },
        optimizer: { type: OPTIMIZER_TYPE.GEPA },
        evaluation: { metrics: [{ type: METRIC_TYPE.EQUALS }] },
      },
    }) as never;

  const toForm = (model: string, parameters: Record<string, unknown>) =>
    convertOptimizationStudioToFormData(savedRun(model, parameters), [model]);

  const panelAndRequest = (form: OptimizationConfigFormType) => ({
    panel: resolveEffort(
      form.modelName as PROVIDER_MODEL_TYPE,
      form.modelConfig as Record<string, unknown>,
    ),
    sent: convertFormDataToStudioConfig(form, "ds").llm_model.parameters,
  });

  beforeEach(loadRegistryLists);

  afterEach(() => {
    resetModelRegistryStoreForTesting();
  });

  it.each([
    PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO,
    PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_FLASH_PREVIEW,
  ])("shows and sends a %s run's saved effort", (model) => {
    const saved = {
      max_tokens: 700,
      custom_parameters: { reasoning: { effort: "low" } },
    };

    const { panel, sent } = panelAndRequest(toForm(model, saved));

    expect(panel).toEqual({ reasoningEffort: "low" });
    expect(sent).toEqual(saved);
  });

  it("keeps the other values saved next to the effort", () => {
    const saved = {
      max_tokens: 700,
      custom_parameters: {
        reasoning: { effort: "high", exclude: true },
        provider: { order: ["openai"] },
      },
    };

    const { panel, sent } = panelAndRequest(
      toForm(PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO, saved),
    );

    expect(panel).toEqual({ reasoningEffort: "high" });
    expect(sent).toEqual(saved);
  });

  it("keeps a run saved without an effort at Default", () => {
    const { panel, sent } = panelAndRequest(
      toForm(PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO, { max_tokens: 700 }),
    );

    expect(panel).toEqual({});
    expect(sent).toEqual({ max_tokens: 700 });
  });

  it("shows Default and sends no effort for a level the model does not offer", () => {
    const { panel, sent } = panelAndRequest(
      toForm(PROVIDER_MODEL_TYPE.OPENAI_O3_MINI_HIGH, {
        max_tokens: 700,
        custom_parameters: { reasoning: { effort: "low" } },
      }),
    );

    expect(panel).toEqual({});
    expect(sent).toEqual({ max_tokens: 700 });
  });

  it("keeps the effort when the registry arrives after the form is seeded", () => {
    resetModelRegistryStoreForTesting();
    const form = toForm(PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO, {
      max_tokens: 700,
      custom_parameters: { reasoning: { effort: "low" } },
    });

    loadRegistryLists();

    expect(panelAndRequest(form).sent).toEqual({
      max_tokens: 700,
      custom_parameters: { reasoning: { effort: "low" } },
    });
  });
});

describe("convertOptimizationStudioToFormData — keeping or replacing the saved model", () => {
  const savedRun = (optimizerParameters: Record<string, unknown> = {}) =>
    ({
      studio_config: {
        prompt: { messages: [{ role: "user", content: "hi" }] },
        llm_model: {
          model: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI,
          parameters: {
            max_tokens: 300,
            temperature: 0.4,
            custom_parameters: { reasoning: { effort: "low" }, top_k: 3 },
          },
        },
        optimizer: {
          type: OPTIMIZER_TYPE.GEPA,
          parameters: optimizerParameters,
        },
        evaluation: { metrics: [{ type: METRIC_TYPE.EQUALS }] },
      },
    }) as never;

  it("keeps the saved model and its settings when the workspace can run it", () => {
    const form = convertOptimizationStudioToFormData(savedRun(), [
      PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI,
    ]);

    expect(form.modelName).toBe(PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI);
    expect(
      convertFormDataToStudioConfig(form, "ds").llm_model.parameters,
    ).toMatchObject({
      max_tokens: 300,
      temperature: 0.4,
      custom_parameters: { top_k: 3 },
    });
  });

  it("starts the replacement model from its own defaults, without the saved settings", () => {
    const form = convertOptimizationStudioToFormData(savedRun(), [
      PROVIDER_MODEL_TYPE.GPT_4O_MINI,
    ]);
    const sent = convertFormDataToStudioConfig(form, "ds").llm_model.parameters;

    expect(form.modelName).toBe(PROVIDER_MODEL_TYPE.GPT_4O_MINI);
    expect(form.modelConfig).toEqual(
      getOptimizationDefaultConfigByProvider(
        PROVIDER_TYPE.OPEN_AI as COMPOSED_PROVIDER_TYPE,
        PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      ),
    );
    expect(sent).not.toHaveProperty("custom_parameters");
    expect(sent).not.toHaveProperty("max_tokens");
    expect(sent?.temperature).toBe(0);
  });

  it.each([
    {
      availableModels: [
        PROVIDER_MODEL_TYPE.GPT_4O_MINI,
        PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI,
      ],
      expected: {
        model: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI,
        model_parameters: { custom_parameters: { top_k: 3 } },
      },
    },
    {
      availableModels: [PROVIDER_MODEL_TYPE.GPT_4O_MINI],
      expected: { model: undefined, model_parameters: undefined },
    },
  ])(
    "keeps an algorithm model's settings only with the model ($expected.model)",
    ({ availableModels, expected }) => {
      const form = convertOptimizationStudioToFormData(
        savedRun({
          model: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI,
          model_parameters: { custom_parameters: { top_k: 3 } },
          seed: 42,
        }),
        availableModels,
      );
      const optimizerParams = form.optimizerParams as Record<string, unknown>;

      expect(optimizerParams.model).toEqual(expected.model);
      expect(optimizerParams.model_parameters).toEqual(
        expected.model_parameters,
      );
      expect(optimizerParams.seed).toBe(42);
    },
  );
});
