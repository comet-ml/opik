import { describe, expect, it } from "vitest";
import { canShowMessagesTab } from "./canShowMessagesTab";

describe("canShowMessagesTab", () => {
  it("keeps the viewer gate open for renderable messages with malformed tool calls", () => {
    expect(
      canShowMessagesTab(
        { messages: [{ role: "user", content: "Hello" }] },
        {
          messages: [
            {
              role: "assistant",
              content: "The response is still renderable",
              tool_calls: [null],
            },
          ],
        },
      ),
    ).toBe(true);
  });

  it("closes the viewer gate for unsupported non-empty fields", () => {
    expect(
      canShowMessagesTab(
        { messages: [{ role: "user", content: "Hello" }] },
        { unsupported: true },
      ),
    ).toBe(false);
  });
});
