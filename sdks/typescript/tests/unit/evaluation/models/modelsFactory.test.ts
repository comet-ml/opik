import { describe, it, expect, beforeEach, afterEach } from "vitest";
import {
  createModel,
  VercelAIChatModel,
  OpikBaseModel,
} from "@/evaluation/models";

describe("modelsFactory", () => {
  let originalApiKey: string | undefined;

  beforeEach(() => {
    originalApiKey = process.env.OPENAI_API_KEY;
    process.env.OPENAI_API_KEY = "test-key";
  });

  afterEach(() => {
    if (originalApiKey) {
      process.env.OPENAI_API_KEY = originalApiKey;
    } else {
      delete process.env.OPENAI_API_KEY;
    }
  });

  describe("createModel", () => {
    it("should create model with model ID", () => {
      const model = createModel("gpt-4o");

      expect(model).toBeInstanceOf(OpikBaseModel);
      expect(model).toBeInstanceOf(VercelAIChatModel);
      expect(model.modelName).toBe("gpt-4o");
    });

    it("should create model with custom name", () => {
      const model = createModel("gpt-3.5-turbo");

      expect(model).toBeInstanceOf(VercelAIChatModel);
      expect(model.modelName).toBe("gpt-3.5-turbo");
    });

    it("should create model with options", () => {
      const model = createModel("gpt-4o", { apiKey: "custom-key" });

      expect(model).toBeInstanceOf(VercelAIChatModel);
      expect(model.modelName).toBe("gpt-4o");
    });

    it("should return OpikBaseModel interface", () => {
      const model = createModel("gpt-4o");

      expect(typeof model.generateString).toBe("function");
      expect(typeof model.generateProviderResponse).toBe("function");
      expect(model.modelName).toBeDefined();
    });
  });
});
