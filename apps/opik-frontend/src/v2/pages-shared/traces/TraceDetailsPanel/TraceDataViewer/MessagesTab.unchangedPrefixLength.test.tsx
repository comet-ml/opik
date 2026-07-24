import { describe, it, expect, afterEach } from "vitest";
import { render, screen, cleanup, fireEvent } from "@testing-library/react";
import { TooltipProvider } from "@/ui/tooltip";
import MessagesTab from "./MessagesTab";

// Provider-agnostic: unchangedPrefixLength hides input-0..input-{N-1} behind a single disclosure
// row, the id scheme every registered format mapper (openai/langchain/anthropic) already uses
// for input messages by position in the raw input array. Not specific to Anthropic -- exercised
// here with a plain OpenAI-shaped payload to keep that separation explicit (see
// MessagesTab.anthropic.test.tsx for Anthropic-specific rendering behavior; this file tests
// unrelated, independently-shippable functionality).
//
// The hide is deliberately NOT implemented as per-message accordion collapse: MessagesTab
// hardcodes preserveKey="messages-tab-combined" (not per-trace), so its Expand/Collapse-all
// state persists to the SAME localStorage key across every trace shown in the app. An earlier
// version collapsed the already-shown prefix as ordinary accordion items, which meant a stale
// "expand all" preference left over from any other trace silently defeated the hint. The
// disclosure row sits outside accordion state entirely so neither Expand/Collapse-all nor a
// persisted preference from another trace can reveal or re-hide it.
afterEach(() => {
  cleanup();
  localStorage.clear();
});

const renderMessagesTab = (
  props: Omit<React.ComponentProps<typeof MessagesTab>, "media" | "isLoading">,
) =>
  render(
    <TooltipProvider>
      <MessagesTab media={[]} isLoading={false} {...props} />
    </TooltipProvider>,
  );

describe("MessagesTab unchangedPrefixLength", () => {
  const transformedInput = {
    messages: [
      { role: "user", content: "First message" },
      { role: "assistant", content: "Second message" },
      { role: "user", content: "Third message, genuinely new" },
    ],
  };
  const transformedOutput = {
    choices: [{ message: { role: "assistant", content: "Reply" }, index: 0 }],
  };

  it("hides input-0..input-{N-1} behind a disclosure row and leaves the rest visible", () => {
    renderMessagesTab({
      transformedInput,
      transformedOutput,
      unchangedPrefixLength: 2,
    });

    expect(screen.queryByText("First message")).not.toBeInTheDocument();
    expect(screen.queryByText("Second message")).not.toBeInTheDocument();
    expect(screen.queryAllByText("User")).toHaveLength(1);
    // The one remaining "Assistant" bubble is the output turn ("Reply"), not the hidden
    // input-1 message ("Second message") -- confirms the hide is scoped to the input prefix.
    expect(screen.queryAllByText("Assistant")).toHaveLength(1);
    expect(
      screen.getByText(/2 earlier messages already shown/),
    ).toBeInTheDocument();
    expect(
      screen.getByText("Third message, genuinely new"),
    ).toBeInTheDocument();
  });

  it("reveals the hidden prefix on click, unaffected by Expand/Collapse-all state", () => {
    renderMessagesTab({
      transformedInput,
      transformedOutput,
      unchangedPrefixLength: 2,
    });

    fireEvent.click(screen.getByText(/2 earlier messages already shown/));

    expect(
      screen.queryByText(/earlier messages already shown/),
    ).not.toBeInTheDocument();
    expect(screen.getByText("First message")).toBeInTheDocument();
    expect(screen.getByText("Second message")).toBeInTheDocument();
    expect(
      screen.getByText("Third message, genuinely new"),
    ).toBeInTheDocument();
  });

  it("leaves everything visible and expanded when unchangedPrefixLength is 0 or absent", () => {
    renderMessagesTab({ transformedInput, transformedOutput });

    expect(
      screen.queryByText(/earlier messages already shown/),
    ).not.toBeInTheDocument();
    const [firstUser] = screen.getAllByText("User");
    expect(firstUser.closest("[aria-expanded]")).toHaveAttribute(
      "data-state",
      "open",
    );
    expect(screen.getByText("First message")).toBeInTheDocument();
  });

  it("degrades gracefully when unchangedPrefixLength exceeds the actual message count", () => {
    expect(() =>
      renderMessagesTab({
        transformedInput,
        transformedOutput,
        unchangedPrefixLength: 50,
      }),
    ).not.toThrow();
  });

  it("still hides the prefix even with a stale persisted expand-all preference from another trace", () => {
    // Regression coverage for the actual bug report: MessagesTab's Expand/Collapse-all
    // preference is persisted under the SAME localStorage key for every trace in the app. Before
    // the disclosure row existed, a `true` value left over from any earlier trace (a short one
    // under the auto-expand threshold, or an explicit "Expand all" click) silently blew the
    // already-shown prefix back open on every subsequent trace, defeating already_shown_count
    // entirely. Simulate that stuck preference directly and confirm the hide still holds.
    localStorage.setItem("messages-tab-combined-llm-expand-all", "true");

    renderMessagesTab({
      transformedInput,
      transformedOutput,
      unchangedPrefixLength: 2,
    });

    expect(screen.queryByText("First message")).not.toBeInTheDocument();
    expect(
      screen.getByText(/2 earlier messages already shown/),
    ).toBeInTheDocument();
    // The stale "expand all" preference should still apply to the messages that ARE shown.
    expect(
      screen.getByText("Third message, genuinely new"),
    ).toBeInTheDocument();
  });
});
