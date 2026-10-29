import { describe, it, expect } from "vitest";
import { partitionMessageFields } from "./partitionMessageFields";

describe("partitionMessageFields", () => {
  describe("mixed selections", () => {
    it("keeps scalar columns selected alongside a conversation", () => {
      const { messageData, remainingData } = partitionMessageFields({
        messages: [{ role: "user", content: "How do I cancel?" }],
        expected_answer: "Full refund",
        difficulty: 3,
      });

      expect(Object.keys(messageData)).toEqual(["messages"]);
      expect(remainingData).toEqual({
        expected_answer: "Full refund",
        difficulty: 3,
      });
    });

    it("keeps an object column that is not a conversation", () => {
      const { messageData, remainingData } = partitionMessageFields({
        messages: [{ role: "system", content: "Be terse." }],
        metadata: { source: "import", rows: 2 },
      });

      expect(Object.keys(messageData)).toEqual(["messages"]);
      expect(remainingData).toEqual({
        metadata: { source: "import", rows: 2 },
      });
    });
  });

  describe("single-sided selections", () => {
    it("reports no message data for plain columns", () => {
      const data = { question: "How do I cancel?", expected: "Full refund" };
      const { messageData, remainingData } = partitionMessageFields(data);

      expect(messageData).toEqual({});
      expect(remainingData).toEqual(data);
    });

    it("reports no remaining data when every column is a conversation", () => {
      const { messageData, remainingData } = partitionMessageFields({
        messages: [{ role: "user", content: "Hello" }],
      });

      expect(Object.keys(messageData)).toEqual(["messages"]);
      expect(remainingData).toEqual({});
    });
  });

  describe("edge cases", () => {
    it("handles undefined input", () => {
      expect(partitionMessageFields(undefined)).toEqual({
        messageData: {},
        remainingData: {},
      });
    });

    it("handles an empty selection", () => {
      expect(partitionMessageFields({})).toEqual({
        messageData: {},
        remainingData: {},
      });
    });

    it("treats null, empty string, and zero as remaining data", () => {
      const data = { a: null, b: "", c: 0 };
      const { messageData, remainingData } = partitionMessageFields(data);

      expect(messageData).toEqual({});
      expect(remainingData).toEqual(data);
    });
  });
});
