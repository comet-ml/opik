import { describe, expect, it } from "vitest";

import {
  getDecisionModelUnavailableReason,
  getOpenRouterRegionName,
} from "@/lib/openRouterRegion";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";

const JEV_MODELS = ["~typesafe/jev-latest", "typesafe/jev-1.13"];

describe("getOpenRouterRegionName", () => {
  it.each([
    ["global", "Global"],
    ["eu", "European Union"],
    ["us", "United States"],
  ] as const)("names the %s region", (region, name) => {
    expect(getOpenRouterRegionName(region)).toBe(name);
  });

  it.each(["unknown", undefined] as const)("names nothing for %s", (region) => {
    expect(getOpenRouterRegionName(region)).toBeUndefined();
  });
});

describe("getDecisionModelUnavailableReason", () => {
  it.each(JEV_MODELS)("refuses %s in the EU and US regions", (model) => {
    expect(getDecisionModelUnavailableReason(model, "eu")).toBe(
      "Not available in OpenRouter's EU region",
    );
    expect(getDecisionModelUnavailableReason(model, "us")).toBe(
      "Not available in OpenRouter's US region",
    );
  });

  it.each(["global", "unknown", undefined] as const)(
    "keeps Jev available for %s",
    (region) => {
      expect(
        getDecisionModelUnavailableReason(JEV_MODELS[0], region),
      ).toBeUndefined();
    },
  );

  it("keeps GPT 6 Luna available in a regional OpenRouter setup", () => {
    expect(
      getDecisionModelUnavailableReason(PROVIDER_MODEL_TYPE.GPT_6_LUNA, "eu"),
    ).toBeUndefined();
  });
});
