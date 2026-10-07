import React, { useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import LLMPromptMessages from "@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";

const { focusMessage } = vi.hoisted(() => ({ focusMessage: vi.fn() }));

// The real message renders CodeMirror, which doesn't mount under jsdom; the
// stub keeps the handle the list calls, so the test sees which message it focuses.
vi.mock("@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessage", async () => {
  const { forwardRef, useImperativeHandle } = await import("react");
  return {
    default: forwardRef<unknown, { message: LLMMessage }>(
      ({ message }, ref) => {
        useImperativeHandle(ref, () => ({
          insertAtCursor: vi.fn(),
          focus: () => focusMessage(message.id),
        }));
        return <div data-testid={`message-${message.id}`} />;
      },
    ),
  };
});

const createMessage = (id: string): LLMMessage => ({
  id,
  role: LLM_MESSAGE_ROLE.user,
  content: "",
});

const Harness = ({
  initialMessages,
  addAt = "end",
  autoFocusFirstMessage,
}: {
  initialMessages: LLMMessage[];
  addAt?: "start" | "end";
  autoFocusFirstMessage?: boolean;
}) => {
  const [messages, setMessages] = useState(initialMessages);
  return (
    <LLMPromptMessages
      messages={messages}
      onChange={setMessages}
      onAddMessage={() =>
        setMessages((previous) => {
          const added = createMessage(`added-${previous.length}`);
          return addAt === "start" ? [added, ...previous] : [...previous, added];
        })
      }
      autoFocusFirstMessage={autoFocusFirstMessage}
    />
  );
};

describe("LLMPromptMessages focus", () => {
  beforeEach(() => {
    focusMessage.mockClear();
  });

  afterEach(() => {
    cleanup();
    document.body.innerHTML = "";
  });

  describe("+ Message", () => {
    it("should focus the message it added", () => {
      render(<Harness initialMessages={[createMessage("first")]} />);

      fireEvent.click(screen.getByRole("button", { name: "Message" }));

      expect(focusMessage).toHaveBeenCalledTimes(1);
      expect(focusMessage).toHaveBeenCalledWith("added-1");
    });

    it("should focus the added message even when it is not the last one", () => {
      render(
        <Harness
          initialMessages={[createMessage("first"), createMessage("second")]}
          addAt="start"
        />,
      );

      fireEvent.click(screen.getByRole("button", { name: "Message" }));

      expect(focusMessage).toHaveBeenCalledTimes(1);
      expect(focusMessage).toHaveBeenCalledWith("added-2");
    });

    it("should not move focus when messages change without the button", () => {
      const { rerender } = render(
        <LLMPromptMessages
          messages={[createMessage("first")]}
          onChange={vi.fn()}
          onAddMessage={vi.fn()}
        />,
      );

      rerender(
        <LLMPromptMessages
          messages={[createMessage("first"), createMessage("loaded")]}
          onChange={vi.fn()}
          onAddMessage={vi.fn()}
        />,
      );

      expect(focusMessage).not.toHaveBeenCalled();
    });
  });

  describe("autoFocusFirstMessage", () => {
    it("should focus the first message when nothing else has focus", () => {
      render(
        <Harness
          initialMessages={[createMessage("first"), createMessage("second")]}
          autoFocusFirstMessage
        />,
      );

      expect(focusMessage).toHaveBeenCalledTimes(1);
      expect(focusMessage).toHaveBeenCalledWith("first");
    });

    it("should not take focus from an element that already has it", () => {
      const otherInput = document.createElement("input");
      document.body.appendChild(otherInput);
      otherInput.focus();

      render(
        <Harness
          initialMessages={[createMessage("first")]}
          autoFocusFirstMessage
        />,
      );

      expect(focusMessage).not.toHaveBeenCalled();
      expect(document.activeElement).toBe(otherInput);
    });

    it("should focus the first message only once", () => {
      const { rerender } = render(
        <LLMPromptMessages
          messages={[createMessage("first")]}
          onChange={vi.fn()}
          onAddMessage={vi.fn()}
          autoFocusFirstMessage
        />,
      );

      rerender(
        <LLMPromptMessages
          messages={[createMessage("replaced")]}
          onChange={vi.fn()}
          onAddMessage={vi.fn()}
          autoFocusFirstMessage
        />,
      );

      expect(focusMessage).toHaveBeenCalledTimes(1);
      expect(focusMessage).toHaveBeenCalledWith("first");
    });

    it("should not focus anything when it is off", () => {
      render(<Harness initialMessages={[createMessage("first")]} />);

      expect(focusMessage).not.toHaveBeenCalled();
    });
  });
});
