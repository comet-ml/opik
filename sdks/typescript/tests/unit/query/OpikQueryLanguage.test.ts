import { describe, it, expect } from "vitest";
import { OpikQueryLanguage } from "../../../src/opik/query/OpikQueryLanguage";

describe("OpikQueryLanguage", () => {
  describe("valid OQL expressions", () => {
    it("should parse simple string equality", () => {
      const oql = new OpikQueryLanguage('name = "test"');
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(1);
      expect(parsed![0]).toMatchObject({
        field: "name",
        operator: "=",
        value: "test",
      });
    });

    it("should parse numeric greater than", () => {
      const oql = new OpikQueryLanguage("usage.total_tokens > 100");
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(1);
      expect(parsed![0]).toMatchObject({
        field: "usage.total_tokens",
        operator: ">",
        value: "100",
      });
    });

    it("should parse contains operator", () => {
      const oql = new OpikQueryLanguage('tags contains "important"');
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(1);
      expect(parsed![0]).toMatchObject({
        field: "tags",
        operator: "contains",
        value: "important",
      });
    });

    it("should parse feedback scores with quoted key", () => {
      const oql = new OpikQueryLanguage(
        'feedback_scores."Answer Relevance" < 0.8'
      );
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(1);
      expect(parsed![0]).toMatchObject({
        field: "feedback_scores",
        key: "Answer Relevance",
        operator: "<",
        value: "0.8",
      });
    });

    it("should parse metadata with key", () => {
      const oql = new OpikQueryLanguage('metadata.version = "1.0"');
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(1);
      expect(parsed![0]).toMatchObject({
        field: "metadata",
        key: "version",
        operator: "=",
        value: "1.0",
      });
    });

    it("should parse decimal numbers", () => {
      const oql = new OpikQueryLanguage("total_estimated_cost >= 1.5");
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(1);
      expect(parsed![0]).toMatchObject({
        field: "total_estimated_cost",
        operator: ">=",
        value: "1.5",
      });
    });

    it("should parse negative numbers", () => {
      const oql = new OpikQueryLanguage("duration > -10");
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(1);
      expect(parsed![0]).toMatchObject({
        field: "duration",
        operator: ">",
        value: "-10",
      });
    });

    it("should parse not_contains operator", () => {
      const oql = new OpikQueryLanguage('output not_contains "error"');
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(1);
      expect(parsed![0]).toMatchObject({
        field: "output",
        operator: "not_contains",
        value: "error",
      });
    });

    it("should parse starts_with operator", () => {
      const oql = new OpikQueryLanguage('name starts_with "test_"');
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(1);
      expect(parsed![0]).toMatchObject({
        field: "name",
        operator: "starts_with",
        value: "test_",
      });
    });

    it("should parse ends_with operator", () => {
      const oql = new OpikQueryLanguage('name ends_with "_test"');
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(1);
      expect(parsed![0]).toMatchObject({
        field: "name",
        operator: "ends_with",
        value: "_test",
      });
    });

    it("should parse not equals operator", () => {
      const oql = new OpikQueryLanguage('name != "failed"');
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(1);
      expect(parsed![0]).toMatchObject({
        field: "name",
        operator: "!=",
        value: "failed",
      });
    });

    it("should parse is_empty operator for lists", () => {
      const oql = new OpikQueryLanguage("tags is_empty");
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(1);
      expect(parsed![0]).toMatchObject({
        field: "tags",
        operator: "is_empty",
        value: null,
      });
    });

    it("should parse is_not_empty operator for lists", () => {
      const oql = new OpikQueryLanguage("tags is_not_empty");
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(1);
      expect(parsed![0]).toMatchObject({
        field: "tags",
        operator: "is_not_empty",
        value: null,
      });
    });

    it("should parse is_empty operator for feedback scores", () => {
      const oql = new OpikQueryLanguage("feedback_scores is_empty");
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(1);
      expect(parsed![0]).toMatchObject({
        field: "feedback_scores",
        operator: "is_empty",
        value: null,
      });
    });

    it("should parse is_not_empty operator for feedback scores", () => {
      const oql = new OpikQueryLanguage("feedback_scores is_not_empty");
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(1);
      expect(parsed![0]).toMatchObject({
        field: "feedback_scores",
        operator: "is_not_empty",
        value: null,
      });
    });

    it("should parse valueless operators in complex query", () => {
      const oql = OpikQueryLanguage.forTraces(
        'tags is_not_empty and duration > 100'
      );
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(2);
      expect(parsed![0]).toMatchObject({
        field: "tags",
        operator: "is_not_empty",
        value: null,
      });
      expect(parsed![1]).toMatchObject({
        field: "duration",
        operator: ">",
        value: "100",
      });
    });

    it("should parse multiple filters with AND", () => {
      const oql = OpikQueryLanguage.forThreads(
        'status = "active" and duration > 100'
      );
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(2);
      expect(parsed![0]).toMatchObject({
        field: "status",
        operator: "=",
        value: "active",
      });
      expect(parsed![1]).toMatchObject({
        field: "duration",
        operator: ">",
        value: "100",
      });
    });

    it("should parse usage fields", () => {
      const oql = new OpikQueryLanguage("usage.prompt_tokens > 50");
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(1);
      expect(parsed![0]).toMatchObject({
        field: "usage.prompt_tokens",
        operator: ">",
        value: "50",
      });
    });

    it("should handle whitespace correctly", () => {
      const oql = new OpikQueryLanguage('  name   =   "test"  ');
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(1);
      expect(parsed![0]).toMatchObject({
        field: "name",
        operator: "=",
        value: "test",
      });
    });

    it("should handle quoted keys with spaces", () => {
      const oql = new OpikQueryLanguage('feedback_scores."My Score Name" > 5');
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(1);
      expect(parsed![0]).toMatchObject({
        field: "feedback_scores",
        key: "My Score Name",
        operator: ">",
        value: "5",
      });
    });

    it("should handle escaped quotes in keys", () => {
      const oql = new OpikQueryLanguage('feedback_scores."Score""Name" > 5');
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(1);
      expect(parsed![0]).toMatchObject({
        field: "feedback_scores",
        key: 'Score"Name',
        operator: ">",
        value: "5",
      });
    });
  });

  describe("invalid OQL expressions", () => {
    it("should throw error for unsupported field", () => {
      expect(() => {
        new OpikQueryLanguage('invalid_field = "test"');
      }).toThrow(/is not supported/);
    });

    it("should throw error for invalid value format", () => {
      expect(() => {
        new OpikQueryLanguage("name = test");
      }).toThrow(/Invalid value/);
    });

    it("should throw error for unsupported operator on field", () => {
      expect(() => {
        OpikQueryLanguage.forThreads('status >= "active"');
      }).toThrow(/Operator >= is not supported for field status/);
    });

    it("should throw error for invalid usage field", () => {
      expect(() => {
        new OpikQueryLanguage("usage.invalid_metric = 100");
      }).toThrow(/When querying usage, invalid_metric is not supported/);
    });

    it("should throw error for OR connector", () => {
      expect(() => {
        OpikQueryLanguage.forThreads(
          'status = "active" or duration > 100'
        );
      }).toThrow(/OR is not currently supported/);
    });

    it("should throw error for trailing characters", () => {
      expect(() => {
        new OpikQueryLanguage('name = "test" invalid');
      }).toThrow(/trailing characters/);
    });

    it("should throw error for missing closing quote in value", () => {
      expect(() => {
        new OpikQueryLanguage('name = "test');
      }).toThrow();
    });

    it("should throw error for missing closing quote in key", () => {
      expect(() => {
        new OpikQueryLanguage('feedback_scores."Answer Relevance < 0.8');
      }).toThrow(/Missing closing quote/);
    });

    it("should throw error for key on unsupported field", () => {
      expect(() => {
        new OpikQueryLanguage('name.key = "test"');
      }).toThrow(/is not supported, only the fields/);
    });

    it("should throw error for trace field in prompt query", () => {
      expect(() => {
        OpikQueryLanguage.forPrompts('usage.total_tokens > 100');
      }).toThrow(/is not supported/);
    });

    it("should throw error for span field in prompt query", () => {
      expect(() => {
        OpikQueryLanguage.forPrompts('model = "gpt-4"');
      }).toThrow(/is not supported/);
    });
  });

  describe("parsedFilters JSON output", () => {
    it("should produce valid JSON string", () => {
      const oql = new OpikQueryLanguage('name = "test"');

      expect(oql.parsedFilters).toBeTruthy();
      expect(() => JSON.parse(oql.parsedFilters!)).not.toThrow();

      const parsed = JSON.parse(oql.parsedFilters!);
      expect(parsed).toHaveLength(1);
      expect(parsed[0]).toMatchObject({
        field: "name",
        operator: "=",
        value: "test",
      });
    });

    it("should return null for empty query string", () => {
      const oql = new OpikQueryLanguage("");

      expect(oql.parsedFilters).toBeNull();
      expect(oql.getFilterExpressions()).toBeNull();
    });

    it("should return null for undefined query string", () => {
      const oql = new OpikQueryLanguage();

      expect(oql.parsedFilters).toBeNull();
      expect(oql.getFilterExpressions()).toBeNull();
    });
  });

  describe("all supported fields", () => {
    const traceFields = [
      "id",
      "name",
      "start_time",
      "end_time",
      "input",
      "output",
      "tags",
      "duration",
      "thread_id",
      "total_estimated_cost",
    ];

    const spanFields = ["model", "provider", "type"];

    const threadFields = ["status", "number_of_messages", "first_message"];

    const promptFields = [
      "id",
      "name",
      "description",
      "created_by",
      "last_updated_by",
      "template_structure",
      "tags",
      "version_count",
    ];

    it.each(traceFields)('should parse trace field "%s"', (field) => {
      const operator = field === "tags" ? "contains" : "=";
      const oql = OpikQueryLanguage.forTraces(`${field} ${operator} "test"`);
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(1);
      expect(parsed![0].field).toBe(field);
    });

    it.each(spanFields)('should parse span field "%s"', (field) => {
      const oql = OpikQueryLanguage.forSpans(`${field} = "test"`);
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(1);
      expect(parsed![0].field).toBe(field);
    });

    it.each(threadFields)('should parse thread field "%s"', (field) => {
      const operator = field === "tags" ? "contains" : "=";
      const oql = OpikQueryLanguage.forThreads(
        `${field} ${operator} "test"`
      );
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(1);
      expect(parsed![0].field).toBe(field);
    });

    it.each(promptFields)('should parse prompt field "%s"', (field) => {
      const operator =
        field === "tags" ? "contains" : field === "version_count" ? ">" : "=";
      const value = field === "version_count" ? "5" : '"test"';
      const oql = OpikQueryLanguage.forPrompts(`${field} ${operator} ${value}`);
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(1);
      expect(parsed![0].field).toBe(field);
    });
  });

  describe("complex queries", () => {
    it("should parse query with multiple conditions", () => {
      const oql = OpikQueryLanguage.forTraces(
        'name contains "test" and thread_id = "abc123" and duration > 100'
      );
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(3);
      expect(parsed![0]).toMatchObject({
        field: "name",
        operator: "contains",
        value: "test",
      });
      expect(parsed![1]).toMatchObject({
        field: "thread_id",
        operator: "=",
        value: "abc123",
      });
      expect(parsed![2]).toMatchObject({
        field: "duration",
        operator: ">",
        value: "100",
      });
    });

    it("should parse query with mixed operators", () => {
      const oql = new OpikQueryLanguage(
        'usage.total_tokens >= 100 and total_estimated_cost < 0.5 and name != "excluded"'
      );
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(3);
      expect(parsed![0]).toMatchObject({
        field: "usage.total_tokens",
        operator: ">=",
        value: "100",
      });
      expect(parsed![1]).toMatchObject({
        field: "total_estimated_cost",
        operator: "<",
        value: "0.5",
      });
      expect(parsed![2]).toMatchObject({
        field: "name",
        operator: "!=",
        value: "excluded",
      });
    });

    it("should parse prompt queries with multiple conditions", () => {
      const oql = OpikQueryLanguage.forPrompts(
        'tags contains "production" and version_count > 1 and created_by = "user@example.com"'
      );
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(3);
      expect(parsed![0]).toMatchObject({
        field: "tags",
        operator: "contains",
        value: "production",
      });
      expect(parsed![1]).toMatchObject({
        field: "version_count",
        operator: ">",
        value: "1",
      });
      expect(parsed![2]).toMatchObject({
        field: "created_by",
        operator: "=",
        value: "user@example.com",
      });
    });

    it("should parse prompt queries with date filters", () => {
      const oql = OpikQueryLanguage.forPrompts(
        'created_at >= "2024-01-01T00:00:00Z" and template_structure = "chat"'
      );
      const parsed = oql.getFilterExpressions();

      expect(parsed).toHaveLength(2);
      expect(parsed![0]).toMatchObject({
        field: "created_at",
        operator: ">=",
        value: "2024-01-01T00:00:00Z",
      });
      expect(parsed![1]).toMatchObject({
        field: "template_structure",
        operator: "=",
        value: "chat",
      });
    });
  });
});
