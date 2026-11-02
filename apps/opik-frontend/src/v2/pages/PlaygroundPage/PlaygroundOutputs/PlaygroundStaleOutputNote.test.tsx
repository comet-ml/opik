import { describe, expect, it } from "vitest";

import { PlaygroundRunInputChange } from "@/types/playground";
import { describeStaleChanges } from "./PlaygroundStaleOutputNote";

describe("describeStaleChanges", () => {
  it.each<[PlaygroundRunInputChange[] | undefined, string]>([
    [["prompt"], "Prompt changed"],
    [["model"], "Model changed"],
    [["parameters"], "Parameters changed"],
    [["parameters", "prompt"], "Prompt and parameters changed"],
    [["parameters", "model", "prompt"], "Prompt, model and parameters changed"],
    [undefined, "Prompt changed"],
    [[], "Prompt changed"],
    [["unknown" as PlaygroundRunInputChange], "Prompt changed"],
    [["unknown" as PlaygroundRunInputChange, "model"], "Model changed"],
  ])("should describe %j as %s", (changes, expected) => {
    expect(describeStaleChanges(changes)).toBe(expected);
  });
});
