import { z } from "zod";
import {
  getSchemaPrefix,
  zodTypeToBackendType,
  extractFieldMetadata,
  serializeFields,
  deserializeToShape,
  matchesBlueprint,
} from "@/typeHelpers";
import { Blueprint } from "@/agent-config/Blueprint";
import type * as OpikApi from "@/rest_api/api";

function makeBlueprintWithRawValues(
  rawValues: { key: string; value?: string; type: string; description?: string }[]
): Blueprint {
  const apiValues = rawValues.map((v) => ({
    key: v.key,
    value: v.value,
    type: v.type as OpikApi.AgentConfigValuePublicType,
    description: v.description,
  }));

  return new Blueprint({
    id: "bp-1",
    name: "v1",
    type: "blueprint" as OpikApi.AgentBlueprintPublicType,
    values: apiValues,
  });
}

describe("getSchemaPrefix", () => {
  it("returns the schema description", () => {
    const s = z.object({ x: z.string() }).describe("MyConfig");
    expect(getSchemaPrefix(s)).toBe("MyConfig");
  });

  it("throws if .describe() is not set", () => {
    const s = z.object({ x: z.string() });
    expect(() => getSchemaPrefix(s)).toThrow(TypeError);
  });
});

describe("zodTypeToBackendType", () => {
  it("maps ZodString to string", () => {
    expect(zodTypeToBackendType(z.string())).toBe("string");
  });

  it("maps ZodBoolean to boolean", () => {
    expect(zodTypeToBackendType(z.boolean())).toBe("boolean");
  });

  it("maps ZodNumber (plain) to float", () => {
    expect(zodTypeToBackendType(z.number())).toBe("float");
  });

  it("maps ZodNumber.int() to integer", () => {
    expect(zodTypeToBackendType(z.number().int())).toBe("integer");
  });

  it("maps ZodArray to string", () => {
    expect(zodTypeToBackendType(z.array(z.string()))).toBe("string");
  });

  it("maps ZodRecord to string", () => {
    expect(zodTypeToBackendType(z.record(z.string()))).toBe("string");
  });

  it("maps ZodObject to string", () => {
    expect(zodTypeToBackendType(z.object({ a: z.string() }))).toBe("string");
  });

  it("unwraps ZodOptional before mapping", () => {
    expect(zodTypeToBackendType(z.string().optional())).toBe("string");
    expect(zodTypeToBackendType(z.number().optional())).toBe("float");
  });

  it("unwraps ZodNullable before mapping", () => {
    expect(zodTypeToBackendType(z.string().nullable())).toBe("string");
  });

  it("throws for unsupported types", () => {
    expect(() => zodTypeToBackendType(z.date())).toThrow(TypeError);
  });
});

describe("extractFieldMetadata", () => {
  it("extracts prefixed keys and types", () => {
    const schema = z
      .object({
        temperature: z.number().describe("Sampling temperature"),
        model: z.string(),
        count: z.number().int(),
      })
      .describe("Cfg");

    const meta = extractFieldMetadata(schema, "Cfg");

    expect(meta.get("temperature")).toMatchObject({
      prefixedKey: "Cfg.temperature",
      backendType: "float",
      description: "Sampling temperature",
      isOptional: false,
    });
    expect(meta.get("model")).toMatchObject({
      prefixedKey: "Cfg.model",
      backendType: "string",
      isOptional: false,
    });
    expect(meta.get("count")).toMatchObject({
      prefixedKey: "Cfg.count",
      backendType: "integer",
    });
  });

  it("marks optional fields", () => {
    const schema = z
      .object({ hint: z.string().optional() })
      .describe("Cfg");

    const meta = extractFieldMetadata(schema, "Cfg");
    expect(meta.get("hint")?.isOptional).toBe(true);
  });
});

describe("serializeFields", () => {
  const schema = z
    .object({
      temperature: z.number(),
      model: z.string(),
      enabled: z.boolean(),
      hint: z.string().optional(),
    })
    .describe("Cfg");

  it("produces AgentConfigValueWrite array", () => {
    const result = serializeFields(schema, { temperature: 0.5, model: "gpt-4", enabled: true }, "Cfg");
    expect(result).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ key: "Cfg.temperature", value: "0.5", type: "float" }),
        expect.objectContaining({ key: "Cfg.model", value: "gpt-4", type: "string" }),
        expect.objectContaining({ key: "Cfg.enabled", value: "true", type: "boolean" }),
      ])
    );
  });

  it("omits value for undefined optional fields", () => {
    const result = serializeFields(schema, { temperature: 0.5, model: "m", enabled: false, hint: undefined }, "Cfg");
    expect(result.find((v) => v.key === "Cfg.hint")).toEqual(
      expect.objectContaining({ key: "Cfg.hint", value: undefined, type: "string" })
    );
  });
});

describe("deserializeToShape", () => {
  const schema = z
    .object({
      temperature: z.number(),
      model: z.string(),
    })
    .describe("Cfg");

  it("deserializes blueprint values to schema shape", () => {
    const bp = {
      "Cfg.temperature": { value: "0.7", type: "float" },
      "Cfg.model": { value: "gpt-3.5", type: "string" },
    };
    const result = deserializeToShape(schema, bp, "Cfg", { temperature: 0, model: "fallback" });
    expect(result).toEqual({ temperature: 0.7, model: "gpt-3.5" });
  });

  it("falls back to fallback value for missing keys", () => {
    const bp = { "Cfg.temperature": { value: "0.7", type: "float" } };
    const result = deserializeToShape(schema, bp, "Cfg", { temperature: 0, model: "fallback" });
    expect(result.model).toBe("fallback");
  });

  describe("array/object round-trip", () => {
    it("round-trips a z.array field through serialize → deserialize", () => {
      const s = z.object({ tags: z.array(z.string()) }).describe("Cfg");
      const values = { tags: ["a", "b", "c"] };
      const serialized = serializeFields(s, values, "Cfg");
      const bp = Object.fromEntries(serialized.map((e) => [e.key, { value: e.value, type: e.type }]));
      const result = deserializeToShape(s, bp, "Cfg", { tags: [] });
      expect(result.tags).toEqual(["a", "b", "c"]);
    });

    it("round-trips a nested array of objects", () => {
      const s = z.object({ items: z.array(z.object({ id: z.number() })) }).describe("Cfg");
      const values = { items: [{ id: 1 }, { id: 2 }] };
      const serialized = serializeFields(s, values, "Cfg");
      const bp = Object.fromEntries(serialized.map((e) => [e.key, { value: e.value, type: e.type }]));
      const result = deserializeToShape(s, bp, "Cfg", { items: [] });
      expect(result.items).toEqual([{ id: 1 }, { id: 2 }]);
    });

    it("round-trips a z.record field", () => {
      const s = z.object({ meta: z.record(z.string()) }).describe("Cfg");
      const values = { meta: { foo: "bar", baz: "qux" } };
      const serialized = serializeFields(s, values, "Cfg");
      const bp = Object.fromEntries(serialized.map((e) => [e.key, { value: e.value, type: e.type }]));
      const result = deserializeToShape(s, bp, "Cfg", { meta: {} });
      expect(result.meta).toEqual({ foo: "bar", baz: "qux" });
    });
  });
});

describe("matchesBlueprint", () => {
  const schema = z
    .object({
      temperature: z.number(),
      model: z.string(),
    })
    .describe("Cfg");

  it("returns true when all values match", () => {
    const bp = makeBlueprintWithRawValues([
      { key: "Cfg.temperature", value: "0.5", type: "float" },
      { key: "Cfg.model", value: "gpt-4", type: "string" },
    ]);
    expect(matchesBlueprint(schema, { temperature: 0.5, model: "gpt-4" }, bp, "Cfg")).toBe(true);
  });

  it("returns false when a value differs", () => {
    const bp = makeBlueprintWithRawValues([
      { key: "Cfg.temperature", value: "0.5", type: "float" },
      { key: "Cfg.model", value: "gpt-3", type: "string" },
    ]);
    expect(matchesBlueprint(schema, { temperature: 0.5, model: "gpt-4" }, bp, "Cfg")).toBe(false);
  });

  it("returns false when blueprint is missing a key", () => {
    const bp = makeBlueprintWithRawValues([
      { key: "Cfg.temperature", value: "0.5", type: "float" },
    ]);
    expect(matchesBlueprint(schema, { temperature: 0.5, model: "gpt-4" }, bp, "Cfg")).toBe(false);
  });

  describe("null/undefined optional fields", () => {
    const schemaWithOptional = z
      .object({ temperature: z.number(), hint: z.string().optional() })
      .describe("Cfg");

    it("returns true when local null matches absent blueprint entry", () => {
      const bp = makeBlueprintWithRawValues([
        { key: "Cfg.temperature", value: "0.5", type: "float" },
      ]);
      expect(matchesBlueprint(schemaWithOptional, { temperature: 0.5, hint: undefined }, bp, "Cfg")).toBe(true);
    });

    it("returns false when local null differs from a stored blueprint value", () => {
      const bp = makeBlueprintWithRawValues([
        { key: "Cfg.temperature", value: "0.5", type: "float" },
        { key: "Cfg.hint", value: "some-hint", type: "string" },
      ]);
      expect(matchesBlueprint(schemaWithOptional, { temperature: 0.5, hint: undefined }, bp, "Cfg")).toBe(false);
    });
  });

  describe("field description comparison", () => {
    it("returns true when values and descriptions both match", () => {
      const schema = z
        .object({ temperature: z.number().describe("Sampling temperature") })
        .describe("Cfg");
      const bp = makeBlueprintWithRawValues([
        { key: "Cfg.temperature", value: "0.5", type: "float", description: "Sampling temperature" },
      ]);
      expect(matchesBlueprint(schema, { temperature: 0.5 }, bp, "Cfg")).toBe(true);
    });

    it("returns false when value is same but description changed", () => {
      const schema = z
        .object({ temperature: z.number().describe("New description") })
        .describe("Cfg");
      const bp = makeBlueprintWithRawValues([
        { key: "Cfg.temperature", value: "0.5", type: "float", description: "Old description" },
      ]);
      expect(matchesBlueprint(schema, { temperature: 0.5 }, bp, "Cfg")).toBe(false);
    });

    it("returns false when description added locally but blueprint has none", () => {
      const schema = z
        .object({ temperature: z.number().describe("Added description") })
        .describe("Cfg");
      const bp = makeBlueprintWithRawValues([
        { key: "Cfg.temperature", value: "0.5", type: "float" },
      ]);
      expect(matchesBlueprint(schema, { temperature: 0.5 }, bp, "Cfg")).toBe(false);
    });

    it("returns false when blueprint has description but local field has none", () => {
      const schema = z
        .object({ temperature: z.number() })
        .describe("Cfg");
      const bp = makeBlueprintWithRawValues([
        { key: "Cfg.temperature", value: "0.5", type: "float", description: "Old description" },
      ]);
      expect(matchesBlueprint(schema, { temperature: 0.5 }, bp, "Cfg")).toBe(false);
    });

    it("returns true when both local and blueprint have no description", () => {
      const schema = z
        .object({ temperature: z.number() })
        .describe("Cfg");
      const bp = makeBlueprintWithRawValues([
        { key: "Cfg.temperature", value: "0.5", type: "float" },
      ]);
      expect(matchesBlueprint(schema, { temperature: 0.5 }, bp, "Cfg")).toBe(true);
    });
  });
});
