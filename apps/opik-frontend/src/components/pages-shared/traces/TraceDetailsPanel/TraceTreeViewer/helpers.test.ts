import { describe, it, expect } from "vitest";
import { filterFunction } from "./helpers";
import { Span, Trace, TraceFeedbackScore, SPAN_TYPE } from "@/types/traces";
import { Filters } from "@/types/filters";
import { COLUMN_TYPE, COLUMN_CUSTOM_ID } from "@/types/shared";

describe("helpers.ts", () => {
  describe("filterFunction", () => {
    const mockTrace: Trace = {
      id: "trace-1",
      name: "Test Trace",
      input: { message: "test input" },
      output: { result: "test output" },
      start_time: "2023-01-01T00:00:00Z",
      end_time: "2023-01-01T01:00:00Z",
      duration: 3600000,
      created_at: "2023-01-01T00:00:00Z",
      last_updated_at: "2023-01-01T00:00:00Z",
      metadata: { environment: "test" },
      feedback_scores: [
        { name: "relevance", value: 0.8, source: "sdk" } as TraceFeedbackScore,
      ],
      comments: [],
      tags: ["test", "important"],
      project_id: "project-1",
      usage: {
        total_tokens: 100,
        prompt_tokens: 50,
        completion_tokens: 50,
      },
      total_estimated_cost: 0.001,
    };

    const mockSpan: Span = {
      id: "span-1",
      name: "Test Span",
      type: SPAN_TYPE.llm,
      parent_span_id: "trace-1",
      trace_id: "trace-1",
      project_id: "project-1",
      input: { prompt: "test prompt" },
      output: { response: "test response" },
      start_time: "2023-01-01T00:00:00Z",
      end_time: "2023-01-01T00:30:00Z",
      duration: 1800000,
      created_at: "2023-01-01T00:00:00Z",
      last_updated_at: "2023-01-01T00:00:00Z",
      metadata: { model: "gpt-4" },
      feedback_scores: [],
      comments: [],
      tags: ["llm"],
      model: "gpt-4",
      provider: "openai",
      usage: {
        total_tokens: 200,
        prompt_tokens: 100,
        completion_tokens: 100,
      },
      total_estimated_cost: 0.002,
      error_info: undefined,
    };

    const mockSpanWithError: Span = {
      ...mockSpan,
      id: "span-2",
      error_info: {
        message: "Test error",
        exception_type: "ValidationError",
        traceback: "",
      },
    };

    it("should return true when no filters or search are provided", () => {
      expect(filterFunction(mockTrace)).toBe(true);
      expect(filterFunction(mockSpan)).toBe(true);
    });

    it("should filter by search value across multiple fields", () => {
      // Search in type field
      expect(filterFunction(mockTrace, undefined, "trace")).toBe(true);
      expect(filterFunction(mockSpan, undefined, "llm")).toBe(true);

      // Search in name field
      expect(filterFunction(mockTrace, undefined, "Test")).toBe(true);
      expect(filterFunction(mockSpan, undefined, "Span")).toBe(true);

      // Search in input field
      expect(filterFunction(mockTrace, undefined, "test input")).toBe(true);
      expect(filterFunction(mockSpan, undefined, "prompt")).toBe(true);

      // Search in output field
      expect(filterFunction(mockTrace, undefined, "test output")).toBe(true);
      expect(filterFunction(mockSpan, undefined, "response")).toBe(true);

      // Search in metadata field
      expect(filterFunction(mockTrace, undefined, "environment")).toBe(true);
      expect(filterFunction(mockSpan, undefined, "gpt-4")).toBe(true);

      // Search should return false for non-existent content
      expect(filterFunction(mockTrace, undefined, "nonexistent")).toBe(false);
    });

    it("should apply single filter correctly", () => {
      const nameFilter: Filters = [
        {
          id: "filter-1",
          field: "name",
          operator: "contains",
          value: "Test",
          type: COLUMN_TYPE.string,
        },
      ];

      expect(filterFunction(mockTrace, nameFilter)).toBe(true);
      expect(filterFunction(mockSpan, nameFilter)).toBe(true);

      const negativeFilter: Filters = [
        {
          id: "filter-2",
          field: "name",
          operator: "contains",
          value: "Nonexistent",
          type: COLUMN_TYPE.string,
        },
      ];

      expect(filterFunction(mockTrace, negativeFilter)).toBe(false);
      expect(filterFunction(mockSpan, negativeFilter)).toBe(false);
    });

    it("should apply multiple filters with AND logic", () => {
      const multipleFilters: Filters = [
        {
          id: "filter-1",
          field: "name",
          operator: "contains",
          value: "Test",
          type: COLUMN_TYPE.string,
        },
        {
          id: "filter-2",
          field: "duration",
          operator: ">",
          value: 1000000,
          type: COLUMN_TYPE.duration,
        },
      ];

      expect(filterFunction(mockTrace, multipleFilters)).toBe(true);
      expect(filterFunction(mockSpan, multipleFilters)).toBe(true);

      const conflictingFilters: Filters = [
        {
          id: "filter-3",
          field: "name",
          operator: "contains",
          value: "Test",
          type: COLUMN_TYPE.string,
        },
        {
          id: "filter-4",
          field: "duration",
          operator: ">",
          value: 9000000,
          type: COLUMN_TYPE.duration,
        },
      ];

      expect(filterFunction(mockTrace, conflictingFilters)).toBe(false);
      expect(filterFunction(mockSpan, conflictingFilters)).toBe(false);
    });

    it("should combine search and filters with AND logic", () => {
      const filters: Filters = [
        {
          id: "filter-1",
          field: "name",
          operator: "contains",
          value: "Test",
          type: COLUMN_TYPE.string,
        },
      ];

      // Both search and filter should match
      expect(filterFunction(mockTrace, filters, "Trace")).toBe(true);
      expect(filterFunction(mockSpan, filters, "Span")).toBe(true);

      // Search doesn't match, filter does
      expect(filterFunction(mockTrace, filters, "nonexistent")).toBe(false);
      expect(filterFunction(mockSpan, filters, "nonexistent")).toBe(false);
    });

    it("should handle empty filters array", () => {
      expect(filterFunction(mockTrace, [])).toBe(true);
      expect(filterFunction(mockSpan, [])).toBe(true);
    });

    it("should handle different column types in filters", () => {
      const numberFilter: Filters = [
        {
          id: "filter-1",
          field: "duration",
          operator: ">",
          value: 1000000,
          type: COLUMN_TYPE.number,
        },
      ];

      expect(filterFunction(mockTrace, numberFilter)).toBe(true);
      expect(filterFunction(mockSpan, numberFilter)).toBe(true);

      const listFilter: Filters = [
        {
          id: "filter-2",
          field: "tags",
          operator: "contains",
          value: "test",
          type: COLUMN_TYPE.list,
        },
      ];

      expect(filterFunction(mockTrace, listFilter)).toBe(true);
      expect(filterFunction(mockSpan, listFilter)).toBe(false);
    });

    it("should handle numberDictionary type filters", () => {
      const feedbackScoreFilter: Filters = [
        {
          id: "filter-1",
          field: "feedback_scores",
          operator: ">",
          value: 0.5,
          type: COLUMN_TYPE.numberDictionary,
          key: "relevance",
        },
      ];

      expect(filterFunction(mockTrace, feedbackScoreFilter)).toBe(true);
      expect(filterFunction(mockSpan, feedbackScoreFilter)).toBe(false);
    });

    it("should handle is_empty and is_not_empty operators", () => {
      const isEmptyFilter: Filters = [
        {
          id: "filter-1",
          field: "error_info",
          operator: "is_empty",
          value: "",
          type: COLUMN_TYPE.errors,
        },
      ];

      expect(filterFunction(mockTrace, isEmptyFilter)).toBe(true);
      expect(filterFunction(mockSpan, isEmptyFilter)).toBe(true);

      const isNotEmptyFilter: Filters = [
        {
          id: "filter-2",
          field: "name",
          operator: "is_not_empty",
          value: "",
          type: COLUMN_TYPE.string,
        },
      ];

      expect(filterFunction(mockTrace, isNotEmptyFilter)).toBe(true);
      expect(filterFunction(mockSpan, isNotEmptyFilter)).toBe(true);
    });

    // Comprehensive tests for all TREE_FILTER_COLUMNS with all operators
    describe("Comprehensive filter tests for all TREE_FILTER_COLUMNS", () => {
      // Test type (category) column
      describe("type column (category)", () => {
        it("should filter by type with = operator", () => {
          const filter: Filters = [
            {
              id: "filter-1",
              field: "type",
              operator: "=",
              value: "llm",
              type: COLUMN_TYPE.category,
            },
          ];

          expect(filterFunction(mockSpan, filter)).toBe(true);
          expect(filterFunction(mockTrace, filter)).toBe(false);
        });

        it("should handle is_empty and is_not_empty for type", () => {
          const isEmptyFilter: Filters = [
            {
              id: "filter-1",
              field: "type",
              operator: "is_empty",
              value: "",
              type: COLUMN_TYPE.category,
            },
          ];

          const isNotEmptyFilter: Filters = [
            {
              id: "filter-2",
              field: "type",
              operator: "is_not_empty",
              value: "",
              type: COLUMN_TYPE.category,
            },
          ];

          expect(filterFunction(mockSpan, isEmptyFilter)).toBe(false);
          expect(filterFunction(mockSpan, isNotEmptyFilter)).toBe(true);
        });
      });

      // Test name (string) column
      describe("name column (string)", () => {
        it("should filter by name with = operator", () => {
          const filter: Filters = [
            {
              id: "filter-1",
              field: "name",
              operator: "=",
              value: "test trace",
              type: COLUMN_TYPE.string,
            },
          ];

          expect(filterFunction(mockTrace, filter)).toBe(true);
          expect(filterFunction(mockSpan, filter)).toBe(false);
        });

        it("should filter by name with contains operator", () => {
          const filter: Filters = [
            {
              id: "filter-1",
              field: "name",
              operator: "contains",
              value: "test",
              type: COLUMN_TYPE.string,
            },
          ];

          expect(filterFunction(mockTrace, filter)).toBe(true);
          expect(filterFunction(mockSpan, filter)).toBe(true);
        });

        it("should filter by name with not_contains operator", () => {
          const filter: Filters = [
            {
              id: "filter-1",
              field: "name",
              operator: "not_contains",
              value: "unknown",
              type: COLUMN_TYPE.string,
            },
          ];

          expect(filterFunction(mockTrace, filter)).toBe(true);
          expect(filterFunction(mockSpan, filter)).toBe(true);
        });

        it("should filter by name with starts_with operator", () => {
          const filter: Filters = [
            {
              id: "filter-1",
              field: "name",
              operator: "starts_with",
              value: "test",
              type: COLUMN_TYPE.string,
            },
          ];

          expect(filterFunction(mockTrace, filter)).toBe(true);
          expect(filterFunction(mockSpan, filter)).toBe(true);
        });

        it("should filter by name with ends_with operator", () => {
          const filter: Filters = [
            {
              id: "filter-1",
              field: "name",
              operator: "ends_with",
              value: "trace",
              type: COLUMN_TYPE.string,
            },
          ];

          expect(filterFunction(mockTrace, filter)).toBe(true);
          expect(filterFunction(mockSpan, filter)).toBe(false);
        });

        it("should handle is_empty and is_not_empty for name", () => {
          const isEmptyFilter: Filters = [
            {
              id: "filter-1",
              field: "name",
              operator: "is_empty",
              value: "",
              type: COLUMN_TYPE.string,
            },
          ];

          const isNotEmptyFilter: Filters = [
            {
              id: "filter-2",
              field: "name",
              operator: "is_not_empty",
              value: "",
              type: COLUMN_TYPE.string,
            },
          ];

          expect(filterFunction(mockTrace, isEmptyFilter)).toBe(false);
          expect(filterFunction(mockTrace, isNotEmptyFilter)).toBe(true);
        });
      });

      // Test input (string) column
      describe("input column (string)", () => {
        it("should filter by input with all string operators", () => {
          const filters = [
            {
              operator: "=",
              value: '{"message":"test input"}',
              expected: true,
            },
            { operator: "contains", value: "test input", expected: true },
            { operator: "not_contains", value: "unknown", expected: true },
            { operator: "starts_with", value: '{"message', expected: true },
            { operator: "ends_with", value: 'input"}', expected: true },
          ] as const;

          filters.forEach(({ operator, value, expected }) => {
            const filter: Filters = [
              {
                id: "filter-1",
                field: "input",
                operator,
                value,
                type: COLUMN_TYPE.string,
              },
            ];

            expect(filterFunction(mockTrace, filter)).toBe(expected);
          });
        });

        it("should handle is_empty and is_not_empty for input", () => {
          const isEmptyFilter: Filters = [
            {
              id: "filter-1",
              field: "input",
              operator: "is_empty",
              value: "",
              type: COLUMN_TYPE.string,
            },
          ];

          const isNotEmptyFilter: Filters = [
            {
              id: "filter-2",
              field: "input",
              operator: "is_not_empty",
              value: "",
              type: COLUMN_TYPE.string,
            },
          ];

          expect(filterFunction(mockTrace, isEmptyFilter)).toBe(false);
          expect(filterFunction(mockTrace, isNotEmptyFilter)).toBe(true);
        });
      });

      // Test output (string) column
      describe("output column (string)", () => {
        it("should filter by output with all string operators", () => {
          const filters = [
            {
              operator: "=",
              value: '{"result":"test output"}',
              expected: true,
            },
            { operator: "contains", value: "test output", expected: true },
            { operator: "not_contains", value: "unknown", expected: true },
            { operator: "starts_with", value: '{"result', expected: true },
            { operator: "ends_with", value: 'output"}', expected: true },
          ] as const;

          filters.forEach(({ operator, value, expected }) => {
            const filter: Filters = [
              {
                id: "filter-1",
                field: "output",
                operator,
                value,
                type: COLUMN_TYPE.string,
              },
            ];

            expect(filterFunction(mockTrace, filter)).toBe(expected);
          });
        });
      });

      // Test duration (duration) column
      describe("duration column (duration)", () => {
        it("should filter by duration with all number operators", () => {
          const filters = [
            { operator: "=", value: 3600000, expected: true },
            { operator: ">", value: 1000000, expected: true },
            { operator: ">=", value: 3600000, expected: true },
            { operator: "<", value: 5000000, expected: true },
            { operator: "<=", value: 3600000, expected: true },
          ] as const;

          filters.forEach(({ operator, value, expected }) => {
            const filter: Filters = [
              {
                id: "filter-1",
                field: "duration",
                operator,
                value,
                type: COLUMN_TYPE.duration,
              },
            ];

            expect(filterFunction(mockTrace, filter)).toBe(expected);
          });
        });

        it("should handle is_empty and is_not_empty for duration", () => {
          const isEmptyFilter: Filters = [
            {
              id: "filter-1",
              field: "duration",
              operator: "is_empty",
              value: "",
              type: COLUMN_TYPE.duration,
            },
          ];

          const isNotEmptyFilter: Filters = [
            {
              id: "filter-2",
              field: "duration",
              operator: "is_not_empty",
              value: "",
              type: COLUMN_TYPE.duration,
            },
          ];

          expect(filterFunction(mockTrace, isEmptyFilter)).toBe(false);
          expect(filterFunction(mockTrace, isNotEmptyFilter)).toBe(true);
        });
      });

      // Test metadata (dictionary) column
      describe("metadata column (dictionary)", () => {
        it("should filter by metadata with all dictionary operators", () => {
          const filters = [
            { operator: "=", value: '{"environment":"test"}', expected: true },
            { operator: "contains", value: "environment", expected: true },
            { operator: ">", value: 1, expected: false }, // metadata is not a number
            { operator: "<", value: 1, expected: false }, // metadata is not a number
          ] as const;

          filters.forEach(({ operator, value, expected }) => {
            const filter: Filters = [
              {
                id: "filter-1",
                field: "metadata",
                operator,
                value,
                type: COLUMN_TYPE.dictionary,
              },
            ];

            expect(filterFunction(mockTrace, filter)).toBe(expected);
          });
        });

        it("should handle nested metadata with key", () => {
          const filter: Filters = [
            {
              id: "filter-1",
              field: "metadata",
              operator: "=",
              value: "test",
              type: COLUMN_TYPE.dictionary,
              key: "environment",
            },
          ];

          expect(filterFunction(mockTrace, filter)).toBe(true);
        });
      });

      // Test tags (list) column
      describe("tags column (list)", () => {
        it("should filter by tags with contains operator", () => {
          const filter: Filters = [
            {
              id: "filter-1",
              field: "tags",
              operator: "contains",
              value: "test",
              type: COLUMN_TYPE.list,
            },
          ];

          expect(filterFunction(mockTrace, filter)).toBe(true);
          expect(filterFunction(mockSpan, filter)).toBe(false);
        });

        it("should handle is_empty and is_not_empty for tags", () => {
          const isEmptyFilter: Filters = [
            {
              id: "filter-1",
              field: "tags",
              operator: "is_empty",
              value: "",
              type: COLUMN_TYPE.list,
            },
          ];

          const isNotEmptyFilter: Filters = [
            {
              id: "filter-2",
              field: "tags",
              operator: "is_not_empty",
              value: "",
              type: COLUMN_TYPE.list,
            },
          ];

          expect(filterFunction(mockTrace, isEmptyFilter)).toBe(false);
          expect(filterFunction(mockTrace, isNotEmptyFilter)).toBe(true);
        });
      });

      // Test usage.total_tokens (number) column
      describe("usage.total_tokens column (number)", () => {
        it("should filter by total_tokens with all number operators", () => {
          const filters = [
            { operator: "=", value: 100, expected: true },
            { operator: ">", value: 50, expected: true },
            { operator: ">=", value: 100, expected: true },
            { operator: "<", value: 200, expected: true },
            { operator: "<=", value: 100, expected: true },
          ] as const;

          filters.forEach(({ operator, value, expected }) => {
            const filter: Filters = [
              {
                id: "filter-1",
                field: "usage.total_tokens",
                operator,
                value,
                type: COLUMN_TYPE.number,
              },
            ];

            expect(filterFunction(mockTrace, filter)).toBe(expected);
          });
        });
      });

      // Test usage.prompt_tokens (number) column
      describe("usage.prompt_tokens column (number)", () => {
        it("should filter by prompt_tokens with all number operators", () => {
          const filters = [
            { operator: "=", value: 50, expected: true },
            { operator: ">", value: 25, expected: true },
            { operator: ">=", value: 50, expected: true },
            { operator: "<", value: 75, expected: true },
            { operator: "<=", value: 50, expected: true },
          ] as const;

          filters.forEach(({ operator, value, expected }) => {
            const filter: Filters = [
              {
                id: "filter-1",
                field: "usage.prompt_tokens",
                operator,
                value,
                type: COLUMN_TYPE.number,
              },
            ];

            expect(filterFunction(mockTrace, filter)).toBe(expected);
          });
        });
      });

      // Test usage.completion_tokens (number) column
      describe("usage.completion_tokens column (number)", () => {
        it("should filter by completion_tokens with all number operators", () => {
          const filters = [
            { operator: "=", value: 50, expected: true },
            { operator: ">", value: 25, expected: true },
            { operator: ">=", value: 50, expected: true },
            { operator: "<", value: 75, expected: true },
            { operator: "<=", value: 50, expected: true },
          ] as const;

          filters.forEach(({ operator, value, expected }) => {
            const filter: Filters = [
              {
                id: "filter-1",
                field: "usage.completion_tokens",
                operator,
                value,
                type: COLUMN_TYPE.number,
              },
            ];

            expect(filterFunction(mockTrace, filter)).toBe(expected);
          });
        });
      });

      // Test total_estimated_cost (cost) column
      describe("total_estimated_cost column (cost)", () => {
        it("should filter by total_estimated_cost with all number operators", () => {
          const filters = [
            { operator: "=", value: 0.001, expected: true },
            { operator: ">", value: 0.0005, expected: true },
            { operator: ">=", value: 0.001, expected: true },
            { operator: "<", value: 0.01, expected: true },
            { operator: "<=", value: 0.001, expected: true },
          ] as const;

          filters.forEach(({ operator, value, expected }) => {
            const filter: Filters = [
              {
                id: "filter-1",
                field: "total_estimated_cost",
                operator,
                value,
                type: COLUMN_TYPE.cost,
              },
            ];

            expect(filterFunction(mockTrace, filter)).toBe(expected);
          });
        });
      });

      // Test error_info (errors) column
      describe("error_info column (errors)", () => {
        it("should filter by error_info with is_empty operator", () => {
          const isEmptyFilter: Filters = [
            {
              id: "filter-1",
              field: "error_info",
              operator: "is_empty",
              value: "",
              type: COLUMN_TYPE.errors,
            },
          ];

          expect(filterFunction(mockTrace, isEmptyFilter)).toBe(true);
          expect(filterFunction(mockSpan, isEmptyFilter)).toBe(true);
          expect(filterFunction(mockSpanWithError, isEmptyFilter)).toBe(false);
        });

        it("should filter by error_info with is_not_empty operator", () => {
          const isNotEmptyFilter: Filters = [
            {
              id: "filter-1",
              field: "error_info",
              operator: "is_not_empty",
              value: "",
              type: COLUMN_TYPE.errors,
            },
          ];

          expect(filterFunction(mockTrace, isNotEmptyFilter)).toBe(false);
          expect(filterFunction(mockSpan, isNotEmptyFilter)).toBe(false);
          expect(filterFunction(mockSpanWithError, isNotEmptyFilter)).toBe(
            true,
          );
        });
      });

      // Test feedback_scores (numberDictionary) column
      describe("feedback_scores column (numberDictionary)", () => {
        it("should filter by feedback_scores with all number operators", () => {
          const filters = [
            { operator: "=", value: 0.8, expected: true },
            { operator: ">", value: 0.5, expected: true },
            { operator: ">=", value: 0.8, expected: true },
            { operator: "<", value: 0.9, expected: true },
            { operator: "<=", value: 0.8, expected: true },
          ] as const;

          filters.forEach(({ operator, value, expected }) => {
            const filter: Filters = [
              {
                id: "filter-1",
                field: "feedback_scores",
                operator,
                value,
                type: COLUMN_TYPE.numberDictionary,
                key: "relevance",
              },
            ];

            expect(filterFunction(mockTrace, filter)).toBe(expected);
          });
        });

        it("should handle is_empty and is_not_empty for feedback_scores", () => {
          const isEmptyFilter: Filters = [
            {
              id: "filter-1",
              field: "feedback_scores",
              operator: "is_empty",
              value: "",
              type: COLUMN_TYPE.numberDictionary,
              key: "nonexistent",
            },
          ];

          const isNotEmptyFilter: Filters = [
            {
              id: "filter-2",
              field: "feedback_scores",
              operator: "is_not_empty",
              value: "",
              type: COLUMN_TYPE.numberDictionary,
              key: "relevance",
            },
          ];

          expect(filterFunction(mockTrace, isEmptyFilter)).toBe(true);
          expect(filterFunction(mockTrace, isNotEmptyFilter)).toBe(true);
        });
      });

      // Test custom ID transformation
      describe("custom ID transformation", () => {
        it("should transform input. prefix filters to input field with dictionary type", () => {
          const filter: Filters = [
            {
              id: "filter-1",
              field: COLUMN_CUSTOM_ID,
              operator: "contains",
              value: "test prompt",
              type: COLUMN_TYPE.dictionary,
              key: "input.prompt",
            },
          ];

          // Should transform to field: "input", key: "prompt"
          expect(filterFunction(mockSpan, filter)).toBe(true);
        });

        it("should transform output. prefix filters to output field with dictionary type", () => {
          const filter: Filters = [
            {
              id: "filter-1",
              field: COLUMN_CUSTOM_ID,
              operator: "contains",
              value: "test response",
              type: COLUMN_TYPE.dictionary,
              key: "output.response",
            },
          ];

          // Should transform to field: "output", key: "response"
          expect(filterFunction(mockSpan, filter)).toBe(true);
        });

        it("should handle nested keys with input. prefix", () => {
          const filter: Filters = [
            {
              id: "filter-1",
              field: COLUMN_CUSTOM_ID,
              operator: "=",
              value: "test input",
              type: COLUMN_TYPE.dictionary,
              key: "input.message",
            },
          ];

          // Should transform to field: "input", key: "message"
          expect(filterFunction(mockTrace, filter)).toBe(true);
        });

        it("should handle nested keys with output. prefix", () => {
          const filter: Filters = [
            {
              id: "filter-1",
              field: COLUMN_CUSTOM_ID,
              operator: "=",
              value: "test output",
              type: COLUMN_TYPE.dictionary,
              key: "output.result",
            },
          ];

          // Should transform to field: "output", key: "result"
          expect(filterFunction(mockTrace, filter)).toBe(true);
        });

        it("should not transform custom ID filters without input/output prefix", () => {
          const filter: Filters = [
            {
              id: "filter-1",
              field: COLUMN_CUSTOM_ID,
              operator: "contains",
              value: "test",
              type: COLUMN_TYPE.dictionary,
              key: "metadata.environment",
            },
          ];

          // Should not be transformed since it doesn't start with input. or output.
          // This filter should fail as COLUMN_CUSTOM_ID field doesn't exist on the data
          expect(filterFunction(mockTrace, filter)).toBe(false);
        });

        it("should handle deep nested paths with input. prefix", () => {
          // Create a mock with deeply nested input
          const mockWithNestedInput: Trace = {
            ...mockTrace,
            input: {
              messages: [
                {
                  role: "user",
                  content: "hello world",
                },
              ],
            },
          };

          const filter: Filters = [
            {
              id: "filter-1",
              field: COLUMN_CUSTOM_ID,
              operator: "contains",
              value: "hello",
              type: COLUMN_TYPE.dictionary,
              key: "input.messages[0].content",
            },
          ];

          // Should transform to field: "input", key: "messages[0].content"
          expect(filterFunction(mockWithNestedInput, filter)).toBe(true);
        });

        it("should handle various operators with transformed filters", () => {
          // Let's test each operator individually to understand the behavior
          const equalFilter: Filters = [
            {
              id: "filter-1",
              field: COLUMN_CUSTOM_ID,
              operator: "=",
              value: "test prompt",
              type: COLUMN_TYPE.dictionary,
              key: "input.prompt",
            },
          ];

          const containsFilter: Filters = [
            {
              id: "filter-1",
              field: COLUMN_CUSTOM_ID,
              operator: "contains",
              value: "test",
              type: COLUMN_TYPE.dictionary,
              key: "input.prompt",
            },
          ];

          const startsWithFilter: Filters = [
            {
              id: "filter-1",
              field: COLUMN_CUSTOM_ID,
              operator: "starts_with",
              value: "test",
              type: COLUMN_TYPE.dictionary,
              key: "input.prompt",
            },
          ];

          expect(filterFunction(mockSpan, equalFilter)).toBe(true);
          expect(filterFunction(mockSpan, containsFilter)).toBe(true);
          // Note: Dictionary filters apply starts_with to the JSON string, not the extracted value
          expect(filterFunction(mockSpan, startsWithFilter)).toBe(false);
        });

        it("should handle empty and missing keys correctly", () => {
          const emptyKeyFilter: Filters = [
            {
              id: "filter-1",
              field: COLUMN_CUSTOM_ID,
              operator: "is_empty",
              value: "",
              type: COLUMN_TYPE.dictionary,
              key: "input.nonexistent",
            },
          ];

          const notEmptyKeyFilter: Filters = [
            {
              id: "filter-1",
              field: COLUMN_CUSTOM_ID,
              operator: "is_not_empty",
              value: "",
              type: COLUMN_TYPE.dictionary,
              key: "input.prompt",
            },
          ];

          expect(filterFunction(mockSpan, emptyKeyFilter)).toBe(true); // nonexistent key is empty
          expect(filterFunction(mockSpan, notEmptyKeyFilter)).toBe(true); // prompt exists and is not empty
        });
      });
    });
  });
});
