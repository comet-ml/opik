import { describe, it, expect, beforeEach } from "vitest";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Prompt } from "../../../src/opik/prompt/Prompt";
import { PromptValidationError } from "../../../src/opik/prompt/errors";
import { PromptType } from "../../../src/opik/prompt/types";
import {
  createMockOpikClient,
  basicPromptData,
  mustacheTemplates,
  jinja2Templates,
} from "./fixtures";
import type { OpikClient } from "../../../src/opik/client/Client";

describe("Prompt - format()", () => {
  let mockOpikClient: OpikClient;

  beforeEach(() => {
    mockOpikClient = createMockOpikClient();
  });

  describe("MUSTACHE templates", () => {
    it("should substitute basic variables", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: mustacheTemplates.basic,
        },
        mockOpikClient
      );

      expect(prompt.format({ name: "World" })).toBe("Hello World!");
    });

    it("should substitute multiple variables", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: mustacheTemplates.multipleVars,
        },
        mockOpikClient
      );

      expect(prompt.format({ name: "Alice", score: 95 })).toBe(
        "Hello Alice, your score is 95!"
      );
    });

    it("should handle variables with spaces in delimiters", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: mustacheTemplates.withSpaces,
        },
        mockOpikClient
      );

      expect(prompt.format({ greeting: "Hi", name: "Bob" })).toBe("Hi, Bob!");
    });

    it("should handle nested object properties", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: mustacheTemplates.nested,
        },
        mockOpikClient
      );

      expect(prompt.format({ user: { name: "Alice", age: 30 } })).toBe(
        "User: Alice, Age: 30"
      );
    });

    it("should handle sections with arrays", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: mustacheTemplates.sections,
        },
        mockOpikClient
      );

      const result = prompt.format({
        users: [{ name: "Alice" }, { name: "Bob" }],
      });
      expect(result).toContain("Alice");
      expect(result).toContain("Bob");
    });

    it("should handle conditional sections", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: mustacheTemplates.conditionals,
        },
        mockOpikClient
      );

      expect(prompt.format({ show: true })).toBe("Visible");
      expect(prompt.format({ show: false })).toBe("Hidden");
    });

    it("should handle number values", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: "Value: {{number}}",
        },
        mockOpikClient
      );

      expect(prompt.format({ number: 42 })).toBe("Value: 42");
      expect(prompt.format({ number: 3.14 })).toBe("Value: 3.14");
    });

    it("should handle boolean values", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: "Active: {{active}}",
        },
        mockOpikClient
      );

      expect(prompt.format({ active: true })).toBe("Active: true");
      expect(prompt.format({ active: false })).toBe("Active: false");
    });

    it("should throw error for missing required variables", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: mustacheTemplates.multipleVars,
        },
        mockOpikClient
      );

      expect(() => prompt.format({ name: "Alice" })).toThrow(
        PromptValidationError
      );
    });

    it("should handle empty string values", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: "Hello {{name}}!",
        },
        mockOpikClient
      );

      expect(prompt.format({ name: "" })).toBe("Hello !");
    });
  });

  describe("JINJA2 templates", () => {
    it("should substitute basic variables", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: jinja2Templates.basic,
          type: PromptType.JINJA2,
        },
        mockOpikClient
      );

      expect(prompt.format({ name: "World" })).toBe("Hello World!");
    });

    it("should substitute multiple variables", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: jinja2Templates.multipleVars,
          type: PromptType.JINJA2,
        },
        mockOpikClient
      );

      expect(prompt.format({ name: "Alice", score: 95 })).toBe(
        "Hello Alice, score: 95"
      );
    });

    it("should handle if-else statements", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: jinja2Templates.ifStatement,
          type: PromptType.JINJA2,
        },
        mockOpikClient
      );

      expect(prompt.format({ admin: true })).toBe("Admin");
      expect(prompt.format({ admin: false })).toBe("User");
    });

    it("should handle for loops", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: jinja2Templates.forLoop,
          type: PromptType.JINJA2,
        },
        mockOpikClient
      );

      expect(prompt.format({ items: ["a", "b", "c"] })).toBe("abc");
    });

    it("should handle filters", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: jinja2Templates.filters,
          type: PromptType.JINJA2,
        },
        mockOpikClient
      );

      expect(prompt.format({ name: "alice" })).toBe("ALICE");
    });

    it("should handle nested objects", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: "{{ user.name }} is {{ user.age }} years old",
          type: PromptType.JINJA2,
        },
        mockOpikClient
      );

      expect(prompt.format({ user: { name: "Alice", age: 30 } })).toBe(
        "Alice is 30 years old"
      );
    });

    it("should handle arrays", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: "{{ items[0] }}, {{ items[1] }}",
          type: PromptType.JINJA2,
        },
        mockOpikClient
      );

      expect(prompt.format({ items: ["first", "second"] })).toBe(
        "first, second"
      );
    });
  });

  describe("error handling", () => {
    it("should throw PromptValidationError for invalid Mustache syntax", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: "Hello {{name}",
        },
        mockOpikClient
      );

      expect(() => prompt.format({ name: "World" })).toThrow(
        PromptValidationError
      );
    });

    it("should throw PromptValidationError for invalid Jinja2 syntax", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: "Hello {% if name",
          type: PromptType.JINJA2,
        },
        mockOpikClient
      );

      expect(() => prompt.format({ name: "World" })).toThrow(
        PromptValidationError
      );
    });

    it("should provide helpful error message for missing variables", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: "Hello {{name}}, your score is {{score}}",
        },
        mockOpikClient
      );

      expect(() => prompt.format({ name: "Alice" })).toThrow(/missing.*score/i);
    });
  });

  describe("edge cases", () => {
    it("should handle empty template", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: "",
        },
        mockOpikClient
      );

      expect(prompt.format({})).toBe("");
    });

    it("should handle template with no variables", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: "Static text with no variables",
        },
        mockOpikClient
      );

      expect(prompt.format({})).toBe("Static text with no variables");
    });

    it("should handle special characters in values", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: "Message: {{message}}",
        },
        mockOpikClient
      );

      expect(prompt.format({ message: "Hello\nWorld\t!" })).toContain("\n");
      // Note: Mustache HTML-escapes quotes by default
      const result = prompt.format({ message: "Test 'quotes' and \"double\"" });
      expect(result).toContain("Test");
      expect(result).toContain("quotes");
      expect(result).toContain("double");
    });

    it("should handle unicode characters", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: "{{greeting}} {{name}}!",
        },
        mockOpikClient
      );

      expect(prompt.format({ greeting: "你好", name: "世界" })).toBe(
        "你好 世界!"
      );
      expect(prompt.format({ greeting: "Привет", name: "мир" })).toBe(
        "Привет мир!"
      );
    });

    it("should handle null and undefined gracefully", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: "Value: {{value}}",
        },
        mockOpikClient
      );

      expect(prompt.format({ value: null })).toBe("Value: ");
      expect(prompt.format({ value: undefined })).toBe("Value: ");
    });
  });

  describe("JINJA2 restricted rendering", () => {
    const makeJinja2Prompt = (template: string) =>
      new Prompt(
        {
          ...basicPromptData,
          prompt: template,
          type: PromptType.JINJA2,
        },
        mockOpikClient
      );

    describe("renders ordinary prompts (own-property access)", () => {
      it("should substitute and apply filters", () => {
        const prompt = makeJinja2Prompt(
          "Hello {{ name | upper }} from {{ city }}"
        );

        expect(prompt.format({ name: "alice", city: "London" })).toBe(
          "Hello ALICE from London"
        );
      });

      it("should render dotted access and loops", () => {
        const prompt = makeJinja2Prompt(
          "{{ user.name }}: {% for item in items %}{{ item }}{% endfor %}"
        );

        expect(
          prompt.format({ user: { name: "Alice" }, items: ["a", "b"] })
        ).toBe("Alice: ab");
      });

      it("should render underscore-prefixed data fields", () => {
        const prompt = makeJinja2Prompt("{{ hit._id }}: {{ hit._source.text }}");

        expect(
          prompt.format({ hit: { _id: "507f", _source: { text: "hello" } } })
        ).toBe("507f: hello");
      });

      it("should render dynamically computed key access", () => {
        const prompt = makeJinja2Prompt(
          "{% for k in keys %}{{ row[k] }}{% endfor %}"
        );

        expect(prompt.format({ keys: ["a", "b"], row: { a: "1", b: "2" } })).toBe(
          "12"
        );
      });

      it("should render computed indexing with an expression", () => {
        const prompt = makeJinja2Prompt("{{ msgs[msgs.length - 1] }}");

        expect(prompt.format({ msgs: ["x", "y", "z"] })).toBe("z");
      });

      it("should render variables named like host globals", () => {
        const prompt = makeJinja2Prompt("{{ process }}-{{ module }}-{{ exports }}");

        expect(prompt.format({ process: "p", module: "m", exports: "e" })).toBe(
          "p-m-e"
        );
      });

      it("should render data fields named like object members", () => {
        const prompt = makeJinja2Prompt("{{ o.constructor }}/{{ o.call }}");

        expect(prompt.format({ o: { constructor: "own", call: "ring" } })).toBe(
          "own/ring"
        );
      });

      it("should render ordinary methods and members on primitives", () => {
        const prompt = makeJinja2Prompt(
          "{{ name.toUpperCase() }} ({{ name.length }})"
        );

        expect(prompt.format({ name: "abc" })).toBe("ABC (3)");
      });

      it("should render inherited array methods and length", () => {
        const prompt = makeJinja2Prompt("{{ items.length }}: {{ items.join('-') }}");

        expect(prompt.format({ items: [1, 2, 3] })).toBe("3: 1-2-3");
      });

      it("should render inherited Date methods", () => {
        const prompt = makeJinja2Prompt("{{ d.getTime() }}");

        expect(prompt.format({ d: new Date(0) })).toBe("0");
      });

      it("should render class getters", () => {
        class Box {
          constructor(private readonly value: number) {}
          get doubled(): number {
            return this.value * 2;
          }
        }
        const prompt = makeJinja2Prompt("{{ box.doubled }}");

        expect(prompt.format({ box: new Box(21) })).toBe("42");
      });

      it("should render a bounded range", () => {
        const prompt = makeJinja2Prompt("{% for i in range(3) %}{{ i }}{% endfor %}");

        expect(prompt.format({})).toBe("012");
      });

      it("should allow a small range with large opposite-signed bounds", () => {
        // `to - from` overflows to Infinity here, but the range emits far fewer
        // than MAX_RANGE values, so it must still be allowed.
        const prompt = makeJinja2Prompt("{{ range(lo, hi, step) | length }}");

        const count = Number(
          prompt.format({ lo: -1e308, hi: 1e308, step: 1e307 })
        );
        expect(count).toBeGreaterThan(0);
        expect(count).toBeLessThan(100000);
      });
    });

    describe("neutralizes template escapes", () => {
      it("should reject an oversized range", () => {
        const prompt = makeJinja2Prompt(
          "{% for i in range(1000000000) %}x{% endfor %}"
        );

        expect(() => prompt.format({})).toThrow(PromptValidationError);
      });

      it("should reject a zero range step", () => {
        const prompt = makeJinja2Prompt("{{ range(start, stop, step) | length }}");

        expect(() =>
          prompt.format({ start: 0, stop: 3, step: 0 })
        ).toThrow(PromptValidationError);
      });

      it("should reject a null or NaN range step", () => {
        const prompt = makeJinja2Prompt("{{ range(start, stop, step) | length }}");

        expect(() =>
          prompt.format({ start: 0, stop: 3, step: null })
        ).toThrow(PromptValidationError);
        expect(() =>
          prompt.format({ start: 0, stop: 3, step: NaN })
        ).toThrow(PromptValidationError);
      });

      it("should not execute a constructor call chain", () => {
        const prompt = makeJinja2Prompt(
          "{{ range.constructor('return 7')() }}"
        );

        expect(() => prompt.format({})).toThrow(PromptValidationError);
      });

      it("should not execute a literal constructor call chain", () => {
        const prompt = makeJinja2Prompt(
          "{{ ''.constructor.constructor('return 7')() }}"
        );

        expect(() => prompt.format({})).toThrow(PromptValidationError);
      });

      it("should resolve inherited properties to empty", () => {
        const prompt = makeJinja2Prompt(
          "[{{ range.constructor }}][{{ user.constructor }}][{{ user.__proto__ }}]"
        );

        expect(prompt.format({ user: { name: "Alice" } })).toBe("[][][]");
      });

      it("should not reach the prototype chain through an allowed method", () => {
        const prompt = makeJinja2Prompt("[{{ items.map.constructor }}]");

        expect(prompt.format({ items: [1, 2] })).toBe("[]");
      });

      it("should not let attribute filters read inherited properties", () => {
        const prompt = makeJinja2Prompt(
          "{{ users | selectattr('constructor') | list | length }}"
        );

        expect(prompt.format({ users: [{ a: 1 }] })).toBe("0");
      });

      it("should resolve prototype members on primitives to empty", () => {
        const prompt = makeJinja2Prompt(
          "[{{ ''.__proto__ }}][{{ ''.constructor }}][{{ name.constructor }}]"
        );

        expect(prompt.format({ name: "abc" })).toBe("[][][]");
      });

      it("should not let attribute filters read inherited members of primitives", () => {
        const prompt = makeJinja2Prompt(
          "{{ names | selectattr('constructor') | list | length }}"
        );

        expect(prompt.format({ names: ["a", "b"] })).toBe("0");
      });

      it("should keep all items when rejectattr targets an inherited member", () => {
        const prompt = makeJinja2Prompt(
          "{{ names | rejectattr('constructor') | list | length }}"
        );

        expect(prompt.format({ names: ["a", "b"] })).toBe("2");
      });

      it("should not resolve bare prototype names from the variable context", () => {
        const prompt = makeJinja2Prompt(
          "[{{ constructor }}][{{ valueOf }}][{{ __proto__ }}][{{ greeting }}]"
        );

        expect(prompt.format({ greeting: "hi" })).toBe("[][][][hi]");
      });

      it("should refuse to include a real file a default loader would find", () => {
        // A default/null loader resolves to FileSystemLoader('views'), which
        // searches <cwd>/views, so place the file there: the test fails if the
        // empty-loader env is reverted to a default one, and passes with [].
        const dir = mkdtempSync(join(tmpdir(), "opik-prompt-"));
        mkdirSync(join(dir, "views"));
        writeFileSync(join(dir, "views", "partial.txt"), "INCLUDED_FILE_CONTENT");
        const originalCwd = process.cwd();
        process.chdir(dir);
        try {
          const prompt = makeJinja2Prompt("[{% include 'partial.txt' %}]");

          expect(() => prompt.format({})).toThrow(PromptValidationError);
        } finally {
          process.chdir(originalCwd);
          rmSync(dir, { recursive: true, force: true });
        }
      });
    });
  });
});
