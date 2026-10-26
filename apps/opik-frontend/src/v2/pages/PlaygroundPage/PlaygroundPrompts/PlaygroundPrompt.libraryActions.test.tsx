import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";

import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import usePlaygroundStore from "@/store/PlaygroundStore";
import { PlaygroundPromptType } from "@/types/playground";
import useProjectPromptsList from "@/api/prompts/useProjectPromptsList";
import { useFetchPrompt } from "@/api/prompts/usePromptById";
import { useFetchPromptVersion } from "@/api/prompts/usePromptVersionById";
import {
  DropdownModelCheck,
  ModelResolver,
  ProviderResolver,
} from "@/hooks/useLLMProviderModelsData";
import PlaygroundPrompt from "./PlaygroundPrompt";

vi.mock("@/api/prompts/useProjectPromptsList", () => ({ default: vi.fn() }));
vi.mock("@/api/prompts/usePromptById", () => ({
  default: () => ({ data: undefined, error: null }),
  useFetchPrompt: vi.fn(),
}));
vi.mock("@/api/prompts/usePromptVersionById", () => ({
  default: () => ({ data: undefined }),
  useFetchPromptVersion: vi.fn(),
}));
vi.mock("@tanstack/react-query", () => ({
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
}));
vi.mock("@/contexts/PermissionsContext", () => ({
  usePermissions: () => ({
    permissions: {
      canViewPrompts: true,
      canCreatePrompts: true,
      canEditPrompts: true,
    },
  }),
}));
vi.mock("@/store/AppStore", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/store/AppStore")>()),
  useActiveProjectId: () => "project-1",
}));
vi.mock("@/hooks/usePlaygroundDataset", () => ({
  usePlaygroundDataset: () => ({ datasetId: null }),
}));
vi.mock("@/hooks/useLastPickedModel", () => ({ default: () => ["", vi.fn()] }));
vi.mock("@/hooks/useOpenAiPipelineMode", () => ({ default: () => undefined }));
vi.mock("@/hooks/usePromptVersionLabel", () => ({ default: () => undefined }));
vi.mock("@/hooks/useLoadChatPrompt", () => ({
  default: () => ({
    loadedChatPromptRef: { current: null },
    chatPromptTemplate: "",
    hasUnsavedChatPromptChanges: false,
  }),
}));
vi.mock("./usePromptBadgeColor", () => ({
  default: (_promptId: string, color: unknown) => color,
}));
vi.mock("@/shared/TooltipWrapper/TooltipWrapper", () => ({
  default: ({
    content,
    children,
  }: {
    content: string;
    children: React.ReactNode;
  }) => <span data-tooltip={content}>{children}</span>,
}));
vi.mock("@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessages", () => ({
  default: ({ messages }: { messages: LLMMessage[] }) => (
    <ul>
      {messages.map((message) => (
        <li key={message.id}>{`${message.role}: ${message.content}`}</li>
      ))}
    </ul>
  ),
}));
vi.mock(
  "@/v2/pages-shared/llm/LoadedPromptDisplay/LoadedPromptDisplay",
  () => ({ default: () => null }),
);
vi.mock("@/v2/pages-shared/llm/PromptLibraryMenu/PromptLibraryMenu", () => ({
  default: ({
    chooseTemplateStructure,
    onSelect,
    trigger,
  }: {
    chooseTemplateStructure?: boolean;
    trigger: React.ReactNode;
    onSelect: (selection: {
      promptId: string;
      versionId?: string;
      templateStructure?: PROMPT_TEMPLATE_STRUCTURE;
    }) => void;
  }) => (
    <div data-testid="library-menu" data-chooses-type={chooseTemplateStructure}>
      {trigger}
      <button
        onClick={() =>
          onSelect({
            promptId: "system-text",
            templateStructure: PROMPT_TEMPLATE_STRUCTURE.TEXT,
          })
        }
      >
        Pick text prompt
      </button>
      <button
        onClick={() =>
          onSelect({
            promptId: "system-text",
            versionId: "text-v1",
            templateStructure: PROMPT_TEMPLATE_STRUCTURE.TEXT,
          })
        }
      >
        Pick text prompt v1
      </button>
      <button
        onClick={() =>
          onSelect({
            promptId: "greeter",
            versionId: "chat-v2",
            templateStructure: PROMPT_TEMPLATE_STRUCTURE.CHAT,
          })
        }
      >
        Pick chat prompt
      </button>
    </div>
  ),
}));
vi.mock(
  "@/v2/pages-shared/llm/LLMPromptMessages/AddNewPromptVersionDialog",
  () => ({
    default: ({
      open,
      templateStructure = PROMPT_TEMPLATE_STRUCTURE.TEXT,
      template,
      onSave,
    }: {
      open: boolean;
      templateStructure?: PROMPT_TEMPLATE_STRUCTURE;
      template: string;
      onSave: (version: object) => void;
    }) =>
      open ? (
        <div data-testid={`save-${templateStructure}-dialog`}>
          <span>{template}</span>
          <button
            onClick={() =>
              onSave({
                id: "saved-v1",
                prompt_id: "saved-text",
                template: "You are kind.",
              })
            }
          >
            Confirm save
          </button>
        </div>
      ) : null,
  }),
);
vi.mock("@/v2/pages-shared/llm/PromptModelSelect/PromptModelSelect", () => ({
  default: () => null,
}));
vi.mock("@/v2/pages-shared/llm/PromptModelSettings/PromptModelConfigs", () => ({
  default: () => null,
}));
vi.mock("@/v2/pages/PlaygroundPage/PlaygroundRunButton", () => ({
  default: () => null,
}));

const CARD_ID = "card-1";

const seedPlaygroundPrompt = (prompt: Partial<PlaygroundPromptType>) =>
  act(() =>
    usePlaygroundStore.getState().setPromptMap([CARD_ID], {
      [CARD_ID]: {
        id: CARD_ID,
        name: "Prompt",
        model: "",
        provider: "",
        configs: {},
        messages: [],
        ...prompt,
      },
    }),
  );

const storedMessages = () =>
  usePlaygroundStore.getState().promptMap[CARD_ID].messages;

const renderCard = () =>
  render(
    <PlaygroundPrompt
      workspaceName="default"
      index={0}
      promptId={CARD_ID}
      providerKeys={[]}
      hasLoadedProviderKeys={false}
      hasRegistryModels={false}
      providerResolver={vi.fn(() => "") as unknown as ProviderResolver}
      modelResolver={vi.fn((model) => model) as unknown as ModelResolver}
      isDropdownModel={vi.fn(() => false) as unknown as DropdownModelCheck}
    />,
  );

const tooltipOf = (testId: string) =>
  screen
    .getByTestId(testId)
    .closest("[data-tooltip]")
    ?.getAttribute("data-tooltip");

const SYSTEM_MESSAGE: LLMMessage = {
  id: "system",
  role: LLM_MESSAGE_ROLE.system,
  content: "Old system text",
};
const USER_MESSAGE: LLMMessage = {
  id: "user",
  role: LLM_MESSAGE_ROLE.user,
  content: "Hi {{name}}",
};

const fetchPrompt = vi.fn();
const fetchPromptVersion = vi.fn();

const mockLibraryTotal = (total: number) =>
  vi.mocked(useProjectPromptsList).mockReturnValue({
    data: { content: [], total },
  } as unknown as ReturnType<typeof useProjectPromptsList>);

beforeEach(() => {
  fetchPrompt.mockReset();
  fetchPromptVersion.mockReset();
  mockLibraryTotal(3);
  fetchPrompt.mockResolvedValue({
    id: "system-text",
    latest_version: { id: "text-v2", template: "You are terse." },
  });
  fetchPromptVersion.mockResolvedValue({
    id: "text-v1",
    template: "You are kind.",
  });
  vi.mocked(useFetchPrompt).mockReturnValue(fetchPrompt);
  vi.mocked(useFetchPromptVersion).mockReturnValue(fetchPromptVersion);
});

describe("PlaygroundPrompt header Load", () => {
  it("asks for the prompt type before listing prompts", () => {
    seedPlaygroundPrompt({ messages: [SYSTEM_MESSAGE, USER_MESSAGE] });
    renderCard();

    expect(screen.getByTestId("library-menu")).toHaveAttribute(
      "data-chooses-type",
      "true",
    );
    expect(tooltipOf("load-prompt-button")).toBe("Load a prompt");
  });

  it("disables Load and says why when the library has no prompts", () => {
    mockLibraryTotal(0);
    seedPlaygroundPrompt({ messages: [SYSTEM_MESSAGE, USER_MESSAGE] });
    renderCard();

    expect(screen.getByTestId("load-prompt-button")).toBeDisabled();
    expect(tooltipOf("load-prompt-button")).toBe("No saved prompts to load");
    expect(screen.queryByTestId("library-menu")).not.toBeInTheDocument();
  });

  it("replaces every message with one user message holding the picked text prompt", async () => {
    seedPlaygroundPrompt({ messages: [SYSTEM_MESSAGE, USER_MESSAGE] });
    renderCard();

    await act(async () => {
      fireEvent.click(screen.getByText("Pick text prompt"));
    });

    expect(fetchPrompt).toHaveBeenCalledWith({ promptId: "system-text" });
    expect(storedMessages()).toEqual([
      {
        id: expect.any(String),
        role: LLM_MESSAGE_ROLE.user,
        content: "You are terse.",
        promptId: "system-text",
        promptVersionId: "text-v2",
      },
    ]);
    expect(
      usePlaygroundStore.getState().promptMap[CARD_ID].loadedChatPromptId,
    ).toBeUndefined();
  });

  it("loads the picked version of a text prompt", async () => {
    seedPlaygroundPrompt({ messages: [SYSTEM_MESSAGE, USER_MESSAGE] });
    renderCard();

    await act(async () => {
      fireEvent.click(screen.getByText("Pick text prompt v1"));
    });

    expect(fetchPromptVersion).toHaveBeenCalledWith({ versionId: "text-v1" });
    expect(storedMessages()).toHaveLength(1);
    expect(storedMessages()[0]).toMatchObject({
      content: "You are kind.",
      promptId: "system-text",
      promptVersionId: "text-v1",
    });
  });

  it("links a picked chat prompt to the whole variant", () => {
    seedPlaygroundPrompt({ messages: [SYSTEM_MESSAGE, USER_MESSAGE] });
    renderCard();

    fireEvent.click(screen.getByText("Pick chat prompt"));

    expect(usePlaygroundStore.getState().promptMap[CARD_ID]).toMatchObject({
      loadedChatPromptId: "greeter",
      loadedChatPromptVersionId: "chat-v2",
    });
    expect(fetchPrompt).not.toHaveBeenCalled();
  });
});

describe("PlaygroundPrompt header Save", () => {
  it("saves a variant with several messages as a chat prompt", () => {
    seedPlaygroundPrompt({ messages: [SYSTEM_MESSAGE, USER_MESSAGE] });
    renderCard();

    expect(tooltipOf("playground-save-prompt-button")).toBe(
      "Save as chat prompt",
    );
    fireEvent.click(screen.getByTestId("playground-save-prompt-button"));

    expect(screen.getByTestId("save-chat-dialog")).toBeInTheDocument();
    expect(screen.queryByTestId("save-text-dialog")).not.toBeInTheDocument();
  });

  it("saves a variant with one message as a text prompt, and links that message", () => {
    seedPlaygroundPrompt({ messages: [SYSTEM_MESSAGE] });
    renderCard();

    expect(tooltipOf("playground-save-prompt-button")).toBe(
      "Save as text prompt",
    );
    fireEvent.click(screen.getByTestId("playground-save-prompt-button"));

    const dialog = screen.getByTestId("save-text-dialog");
    expect(dialog).toHaveTextContent("Old system text");
    expect(screen.queryByTestId("save-chat-dialog")).not.toBeInTheDocument();

    fireEvent.click(screen.getByText("Confirm save"));

    expect(storedMessages()).toEqual([
      {
        ...SYSTEM_MESSAGE,
        content: "You are kind.",
        promptId: "saved-text",
        promptVersionId: "saved-v1",
      },
    ]);
  });

  it("keeps saving a variant linked to a chat prompt as chat, even with one message", () => {
    seedPlaygroundPrompt({
      messages: [SYSTEM_MESSAGE],
      loadedChatPromptId: "greeter",
      loadedChatPromptVersionId: "chat-v2",
    });
    renderCard();

    expect(tooltipOf("playground-save-prompt-button")).toBe(
      "Save as chat prompt",
    );
    fireEvent.click(screen.getByTestId("playground-save-prompt-button"));

    expect(screen.getByTestId("save-chat-dialog")).toBeInTheDocument();
  });
});
