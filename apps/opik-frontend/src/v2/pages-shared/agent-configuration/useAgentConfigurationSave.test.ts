import { BlueprintValueType } from "@/types/agent-configs";
import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import { NewFieldDraft } from "./NewBlueprintFieldEditor";
import { isMessageEmpty, validateNewField } from "./useAgentConfigurationSave";

const makeMessage = (
  content: LLMMessage["content"],
  role = LLM_MESSAGE_ROLE.user,
): LLMMessage => ({
  id: crypto.randomUUID(),
  content,
  role,
});

const makeDraft = (overrides: Partial<NewFieldDraft> = {}): NewFieldDraft => ({
  id: "draft-1",
  key: "valid_key",
  type: BlueprintValueType.STRING,
  value: "some value",
  messages: [],
  ...overrides,
});

describe("isMessageEmpty", () => {
  it("returns true for empty string content", () => {
    expect(isMessageEmpty(makeMessage(""))).toBe(true);
  });

  it("returns true for whitespace-only string content", () => {
    expect(isMessageEmpty(makeMessage("   \n\t  "))).toBe(true);
  });

  it("returns false for non-empty string content", () => {
    expect(isMessageEmpty(makeMessage("hello"))).toBe(false);
  });

  it("returns true for empty array content", () => {
    expect(isMessageEmpty(makeMessage([]))).toBe(true);
  });

  it("returns true when all text parts are empty", () => {
    expect(
      isMessageEmpty(
        makeMessage([
          { type: "text", text: "" },
          { type: "text", text: "   " },
        ]),
      ),
    ).toBe(true);
  });

  it("returns false when any text part has content", () => {
    expect(
      isMessageEmpty(
        makeMessage([
          { type: "text", text: "" },
          { type: "text", text: "hello" },
        ]),
      ),
    ).toBe(false);
  });

  it("returns true for non-string non-array content", () => {
    expect(isMessageEmpty(makeMessage(undefined as never))).toBe(true);
  });
});

describe("validateNewField", () => {
  const noExisting = new Set<string>();
  const noSiblings = new Set<string>();

  describe("key validation", () => {
    it("returns error for empty key", () => {
      expect(
        validateNewField(makeDraft({ key: "" }), noExisting, noSiblings),
      ).toBe("Field name is required");
    });

    it("returns error for whitespace-only key", () => {
      expect(
        validateNewField(makeDraft({ key: "   " }), noExisting, noSiblings),
      ).toBe("Field name is required");
    });

    it("returns error for invalid key pattern", () => {
      expect(
        validateNewField(makeDraft({ key: "2bad" }), noExisting, noSiblings),
      ).toBe(
        "Use letters, digits and underscore; start with a letter or underscore",
      );
    });

    it("returns error when key exists in existing keys", () => {
      expect(
        validateNewField(
          makeDraft({ key: "taken" }),
          new Set(["taken"]),
          noSiblings,
        ),
      ).toBe("A field with this name already exists");
    });

    it("returns error for duplicate sibling key", () => {
      expect(
        validateNewField(
          makeDraft({ key: "dup" }),
          noExisting,
          new Set(["dup"]),
        ),
      ).toBe("Duplicate field name in the new fields");
    });
  });

  describe("PROMPT type - TEXT structure", () => {
    it("returns empty string for non-empty text prompt", () => {
      expect(
        validateNewField(
          makeDraft({
            type: BlueprintValueType.PROMPT,
            promptStructure: PROMPT_TEMPLATE_STRUCTURE.TEXT,
            value: "some template",
          }),
          noExisting,
          noSiblings,
        ),
      ).toBe("");
    });

    it("returns error for empty text prompt", () => {
      expect(
        validateNewField(
          makeDraft({
            type: BlueprintValueType.PROMPT,
            promptStructure: PROMPT_TEMPLATE_STRUCTURE.TEXT,
            value: "   ",
          }),
          noExisting,
          noSiblings,
        ),
      ).toBe("Prompt must not be empty");
    });
  });

  describe("PROMPT type - CHAT structure", () => {
    it("returns error when messages array is empty", () => {
      expect(
        validateNewField(
          makeDraft({
            type: BlueprintValueType.PROMPT,
            messages: [],
          }),
          noExisting,
          noSiblings,
        ),
      ).toBe("Add at least one message");
    });

    it("returns error when all messages are empty", () => {
      expect(
        validateNewField(
          makeDraft({
            type: BlueprintValueType.PROMPT,
            messages: [makeMessage(""), makeMessage("   ")],
          }),
          noExisting,
          noSiblings,
        ),
      ).toBe("Messages must not be empty");
    });

    it("returns empty string when at least one message has content", () => {
      expect(
        validateNewField(
          makeDraft({
            type: BlueprintValueType.PROMPT,
            messages: [makeMessage(""), makeMessage("hello")],
          }),
          noExisting,
          noSiblings,
        ),
      ).toBe("");
    });
  });

  describe("BOOLEAN type", () => {
    it("returns empty string regardless of value", () => {
      expect(
        validateNewField(
          makeDraft({ type: BlueprintValueType.BOOLEAN, value: "" }),
          noExisting,
          noSiblings,
        ),
      ).toBe("");
    });
  });

  describe("scalar types delegate to validateBlueprintFieldValue", () => {
    it("returns error for invalid INT value", () => {
      expect(
        validateNewField(
          makeDraft({ type: BlueprintValueType.INT, value: "abc" }),
          noExisting,
          noSiblings,
        ),
      ).toBeTruthy();
    });

    it("returns empty string for valid INT value", () => {
      expect(
        validateNewField(
          makeDraft({ type: BlueprintValueType.INT, value: "42" }),
          noExisting,
          noSiblings,
        ),
      ).toBe("");
    });
  });
});
