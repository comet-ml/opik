import {
  describeTrigger,
  initialOverrideForm,
  overrideFieldsFor,
  overrideFormErrors,
  toOverrides,
} from "./helpers";

describe("workflow override form", () => {
  it("never pre-selects the judge score, even when the recipe or a recommendation names one", () => {
    const fields = overrideFieldsFor("collect");
    const form = initialOverrideForm(
      fields,
      { score_name: "resolution", threshold: 0.5 },
      { score_name: "resolution" },
    );
    expect(form.score_name).toBe("");
    expect(form.threshold).toBe("0.5");
  });

  it("refuses the enable until the required overrides are given", () => {
    expect(overrideFormErrors("collect", { score_name: "" })).toEqual({
      score_name: "Required",
    });
    expect(overrideFormErrors("gate", { prompt_name: "p", model: "" })).toEqual(
      { model: "Required" },
    );
    expect(
      overrideFormErrors("align", { score_name: "quality", per_day: "ten" }),
    ).toEqual({ per_day: "Must be a number" });
  });

  it("sends only what was filled in, numbers as numbers", () => {
    expect(
      toOverrides({
        score_name: "quality",
        threshold: " 0.3 ",
        notify_url: "",
      }),
    ).toEqual({ score_name: "quality", threshold: 0.3 });
  });

  it("shows an unknown recipe only the overrides the backend gave a default for", () => {
    expect(
      overrideFieldsFor("later", { metric: "f1", threshold: null }),
    ).toEqual(["metric"]);
  });
});

describe("describeTrigger", () => {
  it("spells out score conditions with or between groups", () => {
    expect(
      describeTrigger({
        event: "feedback_score:created",
        per_day: 10,
        conditions: {
          groups: [
            {
              conditions: [
                { score_name: "resolution", operator: "<", value: 0.5 },
              ],
            },
            {
              conditions: [{ score_name: "tone", operator: ">=", value: 0.9 }],
            },
          ],
        },
      }),
    ).toBe("resolution < 0.5 or tone >= 0.9 · 10/day");
  });

  it("names the counted trigger's object and cadence", () => {
    expect(
      describeTrigger({
        event: "test_suite:grew",
        test_suite_name: "Regression",
        n: 50,
      }),
    ).toBe("Regression · every 50");
  });
});
