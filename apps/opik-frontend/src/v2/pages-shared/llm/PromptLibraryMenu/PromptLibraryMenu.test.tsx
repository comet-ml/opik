import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import { Prompt, PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import useProjectPromptsList from "@/api/prompts/useProjectPromptsList";
import PromptLibraryMenu from "./PromptLibraryMenu";

vi.mock("@/api/prompts/useProjectPromptsList", () => ({ default: vi.fn() }));
vi.mock("@/api/prompts/usePromptVersionsById", () => ({
  default: () => ({ data: undefined, isLoading: false }),
}));

const textPrompt = {
  id: "text-1",
  name: "Support agent system prompt",
  template_structure: PROMPT_TEMPLATE_STRUCTURE.TEXT,
  version_count: 1,
} as Prompt;
const chatPrompt = {
  id: "chat-1",
  name: "Greeter conversation",
  template_structure: PROMPT_TEMPLATE_STRUCTURE.CHAT,
  version_count: 2,
} as Prompt;

const mockLibrary = (prompts: Prompt[], isLoading = false) =>
  vi.mocked(useProjectPromptsList).mockReturnValue({
    data: isLoading ? undefined : { content: prompts, total: prompts.length },
    isLoading,
  } as unknown as ReturnType<typeof useProjectPromptsList>);

const renderMenu = (props: { chooseTemplateStructure?: boolean } = {}) => {
  const onSelect = vi.fn();
  render(
    <PromptLibraryMenu
      projectId="project-1"
      onSelect={onSelect}
      enableVersionSelect={false}
      trigger={<button>Load</button>}
      {...props}
    />,
  );
  fireEvent.click(screen.getByText("Load"));
  return onSelect;
};

beforeEach(() => {
  mockLibrary([textPrompt, chatPrompt]);
});

describe("PromptLibraryMenu with the prompt type choice", () => {
  it("asks for the prompt type when the library has text and chat prompts", () => {
    renderMenu({ chooseTemplateStructure: true });

    expect(screen.getByText("Text prompt")).toBeInTheDocument();
    expect(screen.getByText("Chat prompt")).toBeInTheDocument();
    expect(
      screen.getByText("Loads into a single message, e.g. system prompt."),
    ).toBeInTheDocument();
    expect(screen.queryByPlaceholderText("Search")).not.toBeInTheDocument();
    expect(screen.queryByText(textPrompt.name)).not.toBeInTheDocument();
  });

  it("lists only the chosen type, and passes the type of the picked prompt", () => {
    const onSelect = renderMenu({ chooseTemplateStructure: true });

    fireEvent.click(screen.getByText("Text prompt"));

    expect(screen.getByText("Text prompts")).toBeInTheDocument();
    expect(screen.getByPlaceholderText("Search")).toBeInTheDocument();
    expect(screen.queryByText(chatPrompt.name)).not.toBeInTheDocument();

    fireEvent.click(screen.getByText(textPrompt.name));

    expect(onSelect).toHaveBeenCalledWith({
      promptId: textPrompt.id,
      templateStructure: PROMPT_TEMPLATE_STRUCTURE.TEXT,
    });
  });

  it("goes back to the type choice from a list", () => {
    renderMenu({ chooseTemplateStructure: true });

    fireEvent.click(screen.getByText("Chat prompt"));
    expect(screen.getByText(chatPrompt.name)).toBeInTheDocument();

    fireEvent.click(screen.getByText("Chat prompts"));

    expect(screen.getByText("Text prompt")).toBeInTheDocument();
    expect(screen.queryByText(chatPrompt.name)).not.toBeInTheDocument();
  });

  it("opens the list directly when the library has only one prompt type", () => {
    mockLibrary([chatPrompt]);
    renderMenu({ chooseTemplateStructure: true });

    expect(screen.getByText(chatPrompt.name)).toBeInTheDocument();
    expect(screen.queryByText("Text prompt")).not.toBeInTheDocument();
    expect(screen.queryByText("Chat prompts")).not.toBeInTheDocument();
  });

  it("shows neither the choice nor a search box while the library loads", () => {
    mockLibrary([], true);
    renderMenu({ chooseTemplateStructure: true });

    expect(screen.queryByText("Text prompt")).not.toBeInTheDocument();
    expect(screen.queryByPlaceholderText("Search")).not.toBeInTheDocument();
  });

  it("lists every prompt without asking when the choice is not requested", () => {
    renderMenu();

    expect(screen.getByText(textPrompt.name)).toBeInTheDocument();
    expect(screen.getByText(chatPrompt.name)).toBeInTheDocument();
    expect(screen.queryByText("Text prompt")).not.toBeInTheDocument();
  });
});
