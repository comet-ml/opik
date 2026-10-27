import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  onTestFinished,
  vi,
} from "vitest";
import { act, fireEvent, render, screen, within } from "@testing-library/react";

// The custom panel embeds the real CodeMirror editor for custom_parameters, which does not mount
// under this environment (a duplicate @codemirror/state breaks its instanceof checks).
vi.mock("@uiw/react-codemirror", () => ({
  default: () => <div data-testid="codemirror-stub" />,
}));

import PromptModelConfigs from "./PromptModelConfigs";
import {
  COMPOSED_PROVIDER_TYPE,
  LLMPromptConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import { getDefaultConfigByProvider } from "@/lib/playground";
import { TooltipProvider } from "@/ui/tooltip";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v2/constants/explainers";
import ANTHROPIC_FIXTURE from "@/lib/anthropicCatalog.fixture.json";
import GEMINI_FIXTURE from "@/lib/geminiCatalog.fixture.json";
import { buildParamsIndex } from "@/hooks/useLLMProviderModelsData";
import {
  resetModelRegistryStoreForTesting,
  setLatestModelParams,
  setLatestProviderProfiles,
} from "@/lib/modelRegistryStore";
import { LlmModelsByProvider } from "@/api/llm/useLlmModels";
import { ModelParamsByProvider } from "@/types/modelParams";

const renderTrigger = (
  provider: COMPOSED_PROVIDER_TYPE,
  model: PROVIDER_MODEL_TYPE | "" = PROVIDER_MODEL_TYPE.OPIK_FREE_MODEL,
  configs: Partial<LLMPromptConfigsType> = {},
  onChange = vi.fn(),
) =>
  render(
    <TooltipProvider delayDuration={700}>
      <PromptModelConfigs
        provider={provider}
        model={model}
        configs={configs}
        onChange={onChange}
      />
    </TooltipProvider>,
  );

const openPanel = () => {
  // Radix opens dropdowns on pointerdown, not click.
  fireEvent.pointerDown(
    screen.getByRole("button"),
    new PointerEvent("pointerdown", { bubbles: true, button: 0 }),
  );
};

describe("PromptModelConfigs trigger", () => {
  it("is hidden for the Opik free model, which has no parameters to set", () => {
    renderTrigger(PROVIDER_TYPE.OPIK_FREE);

    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("is enabled for a provider with parameters", () => {
    renderTrigger(PROVIDER_TYPE.OPEN_AI);

    expect(screen.getByRole("button")).toBeEnabled();
  });

  it("stays disabled before a provider is picked", () => {
    renderTrigger("");

    expect(screen.getByRole("button")).toBeDisabled();
  });
});

describe("PromptModelConfigs for Ollama and Bedrock", () => {
  it.each([
    ["Ollama", `${PROVIDER_TYPE.OLLAMA}:local`, "custom-llm/local/llama3.2"],
    [
      "Bedrock",
      `${PROVIDER_TYPE.BEDROCK}:aws`,
      "custom-llm/aws/openai.gpt-oss-120b-1:0",
    ],
  ])("opens the custom panel for %s", (_, provider, model) => {
    renderTrigger(
      provider,
      model as PROVIDER_MODEL_TYPE,
      getDefaultConfigByProvider(provider, model as PROVIDER_MODEL_TYPE),
    );

    openPanel();

    expect(screen.getByTestId("temperature-input")).toHaveValue("0");
    expect(screen.getByTestId("maxCompletionTokens-input")).toHaveValue("4000");
    expect(screen.getByTestId("topP-input")).toHaveValue("1");
    expect(screen.getByText("Extra body parameters (Optional)")).toBeVisible();
  });
});

describe("PromptModelConfigs panel header", () => {
  it("explains the parameters in a line under the heading, with no info icon", () => {
    renderTrigger(PROVIDER_TYPE.OPEN_AI, PROVIDER_MODEL_TYPE.GPT_4O);

    openPanel();

    const header = screen.getByRole("heading", {
      name: "Model parameters",
    }).parentElement!;
    expect(
      within(header).getByText(
        EXPLAINERS_MAP[EXPLAINER_ID.whats_these_configuration_things]
          .description,
      ),
    ).toBeVisible();
    expect(header.querySelector("svg")).not.toBeInTheDocument();
  });
});

describe("PromptModelConfigs typed values", () => {
  it("keeps a typed value when Escape closes the panel", () => {
    const onChange = vi.fn();
    renderTrigger(
      PROVIDER_TYPE.OPEN_AI,
      PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      getDefaultConfigByProvider(
        PROVIDER_TYPE.OPEN_AI,
        PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      ),
      onChange,
    );

    openPanel();
    const input = screen.getByTestId("temperature-input");
    fireEvent.change(input, { target: { value: "0.7" } });
    fireEvent.keyDown(input, { key: "Escape" });

    expect(onChange).toHaveBeenCalledWith({ temperature: 0.7 });
  });
});

describe("PromptModelConfigs when the provider profiles load after the model list", () => {
  beforeEach(() => {
    resetModelRegistryStoreForTesting();
    setLatestModelParams(
      buildParamsIndex(
        ANTHROPIC_FIXTURE.models as unknown as LlmModelsByProvider,
      ),
    );
  });

  afterEach(() => {
    resetModelRegistryStoreForTesting();
  });

  it("redraws the panel of an id the registry doesn't list from its profile", () => {
    render(
      <TooltipProvider delayDuration={700}>
        <PromptModelConfigs
          provider={PROVIDER_TYPE.ANTHROPIC}
          model={"claude-unlisted-1" as PROVIDER_MODEL_TYPE}
          configs={{ temperature: 0.4, maxCompletionTokens: 4000 }}
          onChange={vi.fn()}
        />
      </TooltipProvider>,
    );
    openPanel();
    expect(screen.getByTestId("temperature-input")).toBeInTheDocument();

    act(() => {
      setLatestProviderProfiles(
        ANTHROPIC_FIXTURE.profiles as unknown as ModelParamsByProvider,
      );
    });

    expect(screen.queryByTestId("temperature-input")).not.toBeInTheDocument();
    expect(screen.getByTestId("maxCompletionTokens-input")).toHaveValue("4000");
  });
});

describe("PromptModelConfigs panel and the rest of the page", () => {
  const renderOpenPanelBesideAButton = () => {
    renderTrigger(PROVIDER_TYPE.OPEN_AI, PROVIDER_MODEL_TYPE.GPT_4O);
    const outside = document.createElement("div");
    outside.tabIndex = 0;
    document.body.appendChild(outside);
    onTestFinished(() => outside.remove());
    openPanel();
    return outside;
  };

  const panelHeading = () =>
    screen.queryByRole("heading", { name: "Model parameters" });

  it("leaves the page clickable while open, so the first click outside reaches its target", () => {
    renderOpenPanelBesideAButton();

    expect(panelHeading()).toBeVisible();
    expect(document.body.style.pointerEvents).not.toBe("none");
  });

  it("closes on a pointer down outside", async () => {
    const outside = renderOpenPanelBesideAButton();
    // Radix starts listening for outside pointer downs on the next tick.
    await act(() => new Promise((resolve) => setTimeout(resolve, 0)));

    fireEvent.pointerDown(outside);

    expect(panelHeading()).not.toBeInTheDocument();
  });

  it("stays open when focus moves outside it, as into the thinking effort list", () => {
    const outside = renderOpenPanelBesideAButton();

    act(() => outside.focus());

    expect(panelHeading()).toBeVisible();
  });
});

describe("PromptModelConfigs for Gemini and Vertex AI", () => {
  beforeEach(() => {
    resetModelRegistryStoreForTesting();
    setLatestModelParams(
      buildParamsIndex(GEMINI_FIXTURE.models as unknown as LlmModelsByProvider),
    );
  });

  afterEach(() => {
    resetModelRegistryStoreForTesting();
  });

  // Both forms mount the slider under the same id. 2.0 Flash's catalog limit is 8,192 while the old
  // panel shows the stored value as it is, so the shown value tells them apart.
  it.each([
    [PROVIDER_TYPE.GEMINI, "gemini-2.0-flash", "8192"],
    [PROVIDER_TYPE.VERTEX_AI, "vertex_ai/gemini-2.0-flash-001", "8192"],
    [PROVIDER_TYPE.GEMINI, "gemini-9-unlisted", "20000"],
    [PROVIDER_TYPE.VERTEX_AI, "vertex_ai/gemini-9-unlisted", "20000"],
  ])(
    "opens the catalog panel only for a listed model: %s %s shows %s",
    (provider, model, shown) => {
      renderTrigger(provider, model as PROVIDER_MODEL_TYPE, {
        ...getDefaultConfigByProvider(provider, model as PROVIDER_MODEL_TYPE),
        maxCompletionTokens: 20000,
      });
      openPanel();

      expect(screen.getByTestId("maxOutputTokens-input")).toHaveValue(shown);
    },
  );
});
