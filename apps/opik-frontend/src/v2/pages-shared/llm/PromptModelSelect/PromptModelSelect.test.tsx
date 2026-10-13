import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";

import PromptModelSelect from "./PromptModelSelect";
import {
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
  ProviderModelsMap,
  ProviderObject,
} from "@/types/providers";
import { TooltipProvider } from "@/ui/tooltip";

const createProvider = (provider: PROVIDER_TYPE): ProviderObject =>
  ({
    id: provider,
    created_at: "",
    provider,
    ui_composed_provider: provider,
    configuration: {},
    read_only: false,
  }) as ProviderObject;

const PROVIDERS = [
  createProvider(PROVIDER_TYPE.OPEN_AI),
  createProvider(PROVIDER_TYPE.ANTHROPIC),
];

const PROVIDER_MODELS: ProviderModelsMap = {
  [PROVIDER_TYPE.OPEN_AI]: [
    { label: "GPT-4o", value: PROVIDER_MODEL_TYPE.GPT_4O },
    { label: "GPT-4o Mini", value: PROVIDER_MODEL_TYPE.GPT_4O_MINI },
  ],
  [PROVIDER_TYPE.ANTHROPIC]: [
    { label: "Claude Haiku 4.5", value: PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5 },
    { label: "Claude Opus 4.1", value: PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_1 },
  ],
};

vi.mock("@/api/provider-keys/useProviderKeys", () => ({
  default: () => ({ data: { content: PROVIDERS } }),
}));
vi.mock("@/hooks/useLLMProviderModelsData", () => ({
  default: () => ({ providerModels: PROVIDER_MODELS }),
}));
vi.mock("@/contexts/PermissionsContext", () => ({
  usePermissions: () => ({ permissions: { canUpdateAIProviders: true } }),
}));
vi.mock(
  "@/v2/pages-shared/llm/ManageAIProviderDialog/ManageAIProviderDialog",
  () => ({ default: () => null }),
);

const renderPicker = () => {
  const onChange = vi.fn();
  render(
    <TooltipProvider>
      <PromptModelSelect
        value=""
        provider=""
        workspaceName="default"
        onChange={onChange}
      />
    </TooltipProvider>,
  );
  fireEvent.keyDown(screen.getByRole("combobox"), { key: "Enter" });
  return {
    onChange,
    listViewport: screen.getByTestId("arrow-scroll-viewport"),
  };
};

// happy-dom has no layout engine, so the list is given the heights of an overflowing one.
const makeOverflow = (listViewport: HTMLElement) => {
  Object.defineProperty(listViewport, "scrollHeight", { value: 1000 });
  Object.defineProperty(listViewport, "clientHeight", { value: 200 });
  fireEvent.scroll(listViewport);
};

describe("PromptModelSelect", () => {
  let scrollBy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    scrollBy = vi.fn();
    vi.spyOn(Element.prototype, "scrollBy").mockImplementation(scrollBy);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("keeps the search box and Manage AI providers outside the scrolling provider list", () => {
    const { listViewport } = renderPicker();

    expect(listViewport).toContainElement(screen.getByText("OpenAI"));
    expect(listViewport).toContainElement(screen.getByText("Anthropic"));
    expect(listViewport).not.toContainElement(
      screen.getByPlaceholderText("Search model"),
    );
    expect(listViewport).not.toContainElement(
      screen.getByRole("button", { name: "Manage AI providers" }),
    );
  });

  it("scrolls the provider list with its down arrow", () => {
    const { listViewport } = renderPicker();
    makeOverflow(listViewport);

    fireEvent.pointerDown(screen.getByTestId("scroll-down-arrow"));

    expect(scrollBy).toHaveBeenCalledOnce();
    expect(scrollBy.mock.contexts[0]).toBe(listViewport);
  });

  it("picks a model from a provider row's side menu", () => {
    const { onChange } = renderPicker();

    fireEvent.mouseEnter(screen.getByText("Anthropic"));
    fireEvent.pointerUp(
      within(screen.getByRole("dialog")).getByRole("option", {
        name: "Claude Opus 4.1",
      }),
    );

    expect(onChange).toHaveBeenCalledWith(
      PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_1,
      PROVIDER_TYPE.ANTHROPIC,
    );
  });
});
