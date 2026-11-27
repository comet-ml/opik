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
  const JEV_UNAVAILABLE_IN = (region: "eu" | "us") =>
    JEV_MODELS.map((model) => ({ model, region }));

  it.each(JEV_MODELS)("names the region the server lists for %s", (model) => {
    expect(
      getDecisionModelUnavailableReason(model, JEV_UNAVAILABLE_IN("eu")),
    ).toBe("Not available in OpenRouter's EU region");
    expect(
      getDecisionModelUnavailableReason(model, JEV_UNAVAILABLE_IN("us")),
    ).toBe("Not available in OpenRouter's US region");
  });

  it.each([[], undefined])(
    "keeps Jev available when the server lists nothing (%j)",
    (unavailable) => {
      expect(
        getDecisionModelUnavailableReason(JEV_MODELS[0], unavailable),
      ).toBeUndefined();
    },
  );

  it("keeps GPT 6 Luna available while Jev is listed", () => {
    expect(
      getDecisionModelUnavailableReason(
        PROVIDER_MODEL_TYPE.GPT_6_LUNA,
        JEV_UNAVAILABLE_IN("eu"),
      ),
    ).toBeUndefined();
  });
});
