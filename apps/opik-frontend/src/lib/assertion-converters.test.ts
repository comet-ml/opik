import { describe, expect, it } from "vitest";

import { packAssertions } from "@/lib/assertion-converters";

describe("packAssertions", () => {
  it("writes the model block with the field names the backend judge reads", () => {
    const { config } = packAssertions(["The answer names Paris"]);

    expect(Object.keys(config.model as Record<string, unknown>).sort()).toEqual(
      ["custom_parameters", "name", "seed", "temperature"],
    );
  });
});
