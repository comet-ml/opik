import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import usePlaygroundStore from "@/store/PlaygroundStore";
import usePromptById from "@/api/prompts/usePromptById";
import usePromptVersionById from "@/api/prompts/usePromptVersionById";
import { TooltipProvider } from "@/ui/tooltip";
import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { PlaygroundPromptType } from "@/types/playground";
import {
  ModelResolver,
  ProviderResolver,
} from "@/hooks/useLLMProviderModelsData";
import PlaygroundPrompt from "./PlaygroundPrompt";

vi.mock("@/api/prompts/usePromptById", () => ({ default: vi.fn() }));
vi.mock("@/api/prompts/usePromptVersionById", () => ({ default: vi.fn() }));

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
vi.mock("@/hooks/useLastPickedModel", () => ({ default: () => ["", vi.fn()] }));
vi.mock("@/hooks/useOpenAiPipelineMode", () => ({ default: () => undefined }));
vi.mock("@/hooks/usePromptVersionLabel", () => ({ default: () => undefined }));
vi.mock("./usePromptBadgeColor", () => ({
  default: (_promptId: string, color: unknown) => color,
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
  () => ({
    default: ({
      name,
      hasUnsavedChanges,
      onClear,
    }: {
      name?: string;
      hasUnsavedChanges?: boolean;
      onClear?: () => void;
    }) => (
      <div>
        <span>{`Loaded: ${name}`}</span>
        {hasUnsavedChanges && <span>Unsaved changes</span>}
        <button onClick={onClear}>Detach</button>
      </div>
    ),
  }),
);
vi.mock("@/v2/pages-shared/llm/PromptLibraryMenu/PromptLibraryMenu", () => ({
  default: ({
    onSelect,
  }: {
    onSelect: (selection: { promptId: string }) => void;
  }) => (
    <button onClick={() => onSelect({ promptId: "other" })}>Pick Other</button>
  ),
}));
vi.mock("@/v2/pages-shared/llm/PromptModelSelect/PromptModelSelect", () => ({
  default: () => null,
}));
vi.mock("@/v2/pages-shared/llm/PromptModelSettings/PromptModelConfigs", () => ({
  default: () => null,
}));
vi.mock(
  "@/v2/pages-shared/llm/LLMPromptMessages/AddNewPromptVersionDialog",
  () => ({
    default: ({
      onSave,
    }: {
      onSave: (version: { id: string }, name: string, promptId: string) => void;
    }) => (
      <button onClick={() => onSave({ id: "v5" }, "Greeter", "greeter")}>
        Save as v5
      </button>
    ),
  }),
);
vi.mock("@/v2/pages/PlaygroundPage/PlaygroundRunButton", () => ({
  default: () => null,
}));

type TemplateMessage = { role: LLM_MESSAGE_ROLE; content: string };

const TEMPLATES: Record<string, TemplateMessage[]> = {
  v1: [
    { role: LLM_MESSAGE_ROLE.system, content: "You are kind." },
    { role: LLM_MESSAGE_ROLE.user, content: "Say hi to {{name}}" },
  ],
  v2: [
    { role: LLM_MESSAGE_ROLE.system, content: "You are terse." },
    { role: LLM_MESSAGE_ROLE.user, content: "Greet {{name}}" },
  ],
  v3: [{ role: LLM_MESSAGE_ROLE.user, content: "Another prompt" }],
  v4: [{ role: LLM_MESSAGE_ROLE.user, content: "Greet {{name}} warmly" }],
  v5: [
    { role: LLM_MESSAGE_ROLE.system, content: "You are kind." },
    { role: LLM_MESSAGE_ROLE.user, content: "Say hi to {{name}} SAVED" },
  ],
};

const PROMPT_NAMES: Record<string, string> = {
  greeter: "Greeter",
  other: "Other",
};

let latestVersionIds: Record<string, string>;

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
        messages: [{ id: "blank", role: LLM_MESSAGE_ROLE.user, content: "" }],
        ...prompt,
      },
    }),
  );

const storedPrompt = () => usePlaygroundStore.getState().promptMap[CARD_ID];

const reloadWithStoredPrompt = (prompt: Partial<PlaygroundPromptType>) => {
  localStorage.setItem(
    "PLAYGROUND_STATE",
    JSON.stringify({
      state: {
        promptIds: [CARD_ID],
        promptMap: {
          [CARD_ID]: {
            id: CARD_ID,
            name: "Prompt",
            model: "",
            provider: "",
            configs: {},
            ...prompt,
          },
        },
      },
      version: 0,
    }),
  );
  return act(() => usePlaygroundStore.persist.rehydrate());
};

const editedV1Messages = () =>
  TEMPLATES.v1.map((message, index) => ({
    id: `m${index}`,
    ...message,
    content:
      message.role === LLM_MESSAGE_ROLE.user
        ? `${message.content} STORED-EDIT`
        : message.content,
  }));

const appendToUserMessage = (text: string) =>
  act(() =>
    usePlaygroundStore.getState().updatePrompt(CARD_ID, {
      messages: storedPrompt().messages.map((message) =>
        message.role === LLM_MESSAGE_ROLE.user
          ? { ...message, content: `${message.content} ${text}` }
          : message,
      ),
    }),
  );

const renderCard = () =>
  render(
    <QueryClientProvider client={new QueryClient()}>
      <TooltipProvider>
        <PlaygroundPrompt
          workspaceName="default"
          index={0}
          promptId={CARD_ID}
          providerKeys={[]}
          isPendingProviderKeys
          providerResolver={vi.fn(() => "") as unknown as ProviderResolver}
          modelResolver={vi.fn((model) => model) as unknown as ModelResolver}
        />
      </TooltipProvider>
    </QueryClientProvider>,
  );

const leaveAndComeBack = (card: ReturnType<typeof renderCard>) => {
  card.unmount();
  return renderCard();
};

beforeEach(() => {
  latestVersionIds = { greeter: "v2", other: "v3" };

  vi.mocked(usePromptById).mockImplementation(({ promptId }, options) => {
    const name =
      options?.enabled === false ? undefined : PROMPT_NAMES[promptId];
    return {
      data: name && {
        id: promptId,
        name,
        latest_version: { id: latestVersionIds[promptId] },
      },
      isSuccess: Boolean(name),
      error: null,
    } as unknown as ReturnType<typeof usePromptById>;
  });

  vi.mocked(usePromptVersionById).mockImplementation(
    ({ versionId }, options) => {
      const template =
        options?.enabled === false ? undefined : TEMPLATES[versionId];
      return {
        data: template && { id: versionId, template: JSON.stringify(template) },
        isSuccess: Boolean(template),
      } as unknown as ReturnType<typeof usePromptVersionById>;
    },
  );
});

describe("PlaygroundPrompt with a prompt loaded from the library", () => {
  it("keeps an unsaved edit when the user leaves the playground and comes back", () => {
    seedPlaygroundPrompt({
      loadedChatPromptId: "greeter",
      loadedChatPromptVersionId: "v1",
      messages: TEMPLATES.v1.map((message, index) => ({
        id: `m${index}`,
        ...message,
      })),
    });
    const card = renderCard();
    appendToUserMessage("UNSAVED-EDIT");

    leaveAndComeBack(card);

    expect(
      screen.getByText("user: Say hi to {{name}} UNSAVED-EDIT"),
    ).toBeInTheDocument();
    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
  });

  it("keeps the edit when the library got a newer version while the user was away", () => {
    seedPlaygroundPrompt({ loadedChatPromptId: "greeter" });
    const card = renderCard();
    expect(screen.getByText("user: Greet {{name}}")).toBeInTheDocument();
    expect(storedPrompt().loadedChatPromptVersionId).toBe("v2");
    appendToUserMessage("UNSAVED-EDIT");

    latestVersionIds.greeter = "v4";
    leaveAndComeBack(card);

    expect(
      screen.getByText("user: Greet {{name}} UNSAVED-EDIT"),
    ).toBeInTheDocument();
  });

  it("keeps edits made after saving a new version when the user comes back", () => {
    seedPlaygroundPrompt({
      loadedChatPromptId: "greeter",
      loadedChatPromptVersionId: "v1",
    });
    const card = renderCard();
    appendToUserMessage("SAVED");
    fireEvent.click(screen.getByRole("button", { name: "Save as v5" }));
    appendToUserMessage("AFTER-SAVE");

    leaveAndComeBack(card);

    expect(
      screen.getByText("user: Say hi to {{name}} SAVED AFTER-SAVE"),
    ).toBeInTheDocument();
  });

  it("replaces the edited messages when the user loads another prompt", () => {
    seedPlaygroundPrompt({ loadedChatPromptId: "greeter" });
    const card = renderCard();
    appendToUserMessage("UNSAVED-EDIT");
    leaveAndComeBack(card);

    fireEvent.click(screen.getByRole("button", { name: "Detach" }));
    fireEvent.click(screen.getByRole("button", { name: "Pick Other" }));

    expect(screen.getByText("user: Another prompt")).toBeInTheDocument();
    expect(screen.queryByText(/UNSAVED-EDIT/)).not.toBeInTheDocument();
    expect(screen.getByText("Loaded: Other")).toBeInTheDocument();
  });
});

describe("PlaygroundPrompt with a library prompt stored before this fix", () => {
  it("keeps the stored edit of a prompt pinned to a version", async () => {
    await reloadWithStoredPrompt({
      loadedChatPromptId: "greeter",
      loadedChatPromptVersionId: "v1",
      messages: editedV1Messages(),
    });

    renderCard();

    expect(
      screen.getByText("user: Say hi to {{name}} STORED-EDIT"),
    ).toBeInTheDocument();
    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
  });

  it("loads the latest version once into a prompt that follows it, since the applied version is unknown", async () => {
    await reloadWithStoredPrompt({
      loadedChatPromptId: "greeter",
      messages: editedV1Messages(),
    });

    renderCard();

    expect(screen.getByText("user: Greet {{name}}")).toBeInTheDocument();
    expect(screen.queryByText(/STORED-EDIT/)).not.toBeInTheDocument();
    expect(storedPrompt().appliedChatPromptVersionId).toBe("v2");
  });

  it("still fills a pinned prompt that has only a blank message", async () => {
    await reloadWithStoredPrompt({
      loadedChatPromptId: "greeter",
      loadedChatPromptVersionId: "v1",
      messages: [{ id: "blank", role: LLM_MESSAGE_ROLE.user, content: "" }],
    });

    renderCard();

    expect(screen.getByText("user: Say hi to {{name}}")).toBeInTheDocument();
  });
});
