import { changedFields } from "./rows";
import type { ModelPriceRow } from "./types";

const alias = (model: string, provider: string): ModelPriceRow => ({
  model: "my-model",
  provider: "openai",
  kind: "alias",
  alias_of: { model, provider },
});

describe("changedFields", () => {
  it("sees an alias target change even when a name contains a |", () => {
    const saved = alias("a|b", "c");
    const edited = alias("a", "b|c");

    expect(changedFields(saved, edited)).toEqual({
      alias_of: { model: "a", provider: "b|c" },
    });
  });

  it("ignores surrounding spaces in the alias target", () => {
    expect(
      changedFields(alias("gpt-4o", "openai"), alias(" gpt-4o ", "openai ")),
    ).toEqual({});
  });
});
