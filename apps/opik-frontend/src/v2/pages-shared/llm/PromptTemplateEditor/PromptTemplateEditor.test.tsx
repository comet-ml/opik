import React from "react";
import { describe, expect, it, vi } from "vitest";
import { render } from "@testing-library/react";

import PromptTemplateEditor from "@/v2/pages-shared/llm/PromptTemplateEditor/PromptTemplateEditor";
import { PromptTemplateEditorState } from "@/v2/pages-shared/llm/PromptTemplateEditor/usePromptTemplateEditor";

const { messagesProps } = vi.hoisted(() => ({
  messagesProps: { current: null as Record<string, unknown> | null },
}));

vi.mock("@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessages", () => ({
  default: (props: Record<string, unknown>) => {
    messagesProps.current = props;
    return null;
  },
}));
vi.mock("@/v2/pages-shared/llm/FormFieldCard", () => ({
  FormFieldCard: ({ children }: { children: React.ReactNode }) => (
    <div>{children}</div>
  ),
  FormFieldModeSelect: () => null,
}));
vi.mock(
  "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDataViewer/CodeBlock/CodeBlockCopy",
  () => ({ default: () => null }),
);

describe("PromptTemplateEditor chat messages", () => {
  it("keeps the message editors at the default size", () => {
    const editor = {
      isChatPrompt: true,
      chatViewMode: "pretty",
      messages: [],
      setMessages: vi.fn(),
      handleAddMessage: vi.fn(),
    } as unknown as PromptTemplateEditorState;

    render(<PromptTemplateEditor editor={editor} />);

    expect(messagesProps.current).not.toBeNull();
    expect(messagesProps.current?.size).toBeUndefined();
  });
});
