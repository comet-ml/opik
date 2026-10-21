import {
  buildAlertName,
  ensureUniqueAlertName,
  MAX_ALERT_NAME_LENGTH,
} from "./alertNameHelpers";
import { ALERT_EVENT_TYPE } from "@/types/alerts";
import { GuardrailTypes } from "@/types/guardrails";
import { TriggerFormType } from "./schema";

const trigger = (
  t: Partial<TriggerFormType> & { eventType: ALERT_EVENT_TYPE },
) => t as TriggerFormType;

describe("buildAlertName", () => {
  it("returns an empty name when there are no triggers", () => {
    expect(buildAlertName([])).toBe("");
  });

  it("uses the bare label while a threshold trigger is still blank", () => {
    expect(
      buildAlertName([trigger({ eventType: ALERT_EVENT_TYPE.trace_errors })]),
    ).toBe("Trace errors");
  });

  it("includes threshold and window once they are filled in", () => {
    expect(
      buildAlertName([
        trigger({
          eventType: ALERT_EVENT_TYPE.trace_errors,
          threshold: "5",
          window: "300",
        }),
      ]),
    ).toBe("Trace errors > 5 in 5 mins");
  });

  it("omits the window while only the threshold is set", () => {
    expect(
      buildAlertName([
        trigger({ eventType: ALERT_EVENT_TYPE.trace_cost, threshold: "100" }),
      ]),
    ).toBe("Cost > 100");
  });

  it("names a feedback score trigger after its first condition", () => {
    expect(
      buildAlertName([
        trigger({
          eventType: ALERT_EVENT_TYPE.trace_feedback_score,
          groups: [
            {
              conditions: [
                {
                  name: "hallucination",
                  operator: ">",
                  threshold: "0.7",
                  window: "3600",
                },
              ],
            },
          ],
        }),
      ]),
    ).toBe("Trace feedback score: hallucination > 0.7");
  });

  it("counts additional feedback score conditions", () => {
    expect(
      buildAlertName([
        trigger({
          eventType: ALERT_EVENT_TYPE.trace_feedback_score,
          groups: [
            {
              conditions: [
                { name: "a", operator: ">", threshold: "0.7", window: "3600" },
                { name: "b", operator: "<", threshold: "0.2", window: "3600" },
              ],
            },
          ],
        }),
      ]),
    ).toBe("Trace feedback score: a > 0.7 +1");
  });

  it("lists selected guardrail types", () => {
    expect(
      buildAlertName([
        trigger({
          eventType: ALERT_EVENT_TYPE.trace_guardrails_triggered,
          guardrailTypes: [GuardrailTypes.TOPIC, GuardrailTypes.PII],
        }),
      ]),
    ).toBe("Guardrail triggered (Topic, PII)");
  });

  it("falls back to the plain label when no guardrail type is selected", () => {
    expect(
      buildAlertName([
        trigger({
          eventType: ALERT_EVENT_TYPE.trace_guardrails_triggered,
          guardrailTypes: [],
        }),
      ]),
    ).toBe("Guardrail triggered");
  });

  it("summarises the remaining triggers", () => {
    expect(
      buildAlertName([
        trigger({
          eventType: ALERT_EVENT_TYPE.trace_errors,
          threshold: "5",
          window: "300",
        }),
        trigger({ eventType: ALERT_EVENT_TYPE.prompt_created }),
        trigger({ eventType: ALERT_EVENT_TYPE.prompt_deleted }),
      ]),
    ).toBe("Trace errors > 5 in 5 mins +2 more");
  });

  it("keeps the name within the cap when a score name is very long", () => {
    const name = buildAlertName([
      trigger({
        eventType: ALERT_EVENT_TYPE.trace_feedback_score,
        groups: [
          {
            conditions: [
              {
                name: "a".repeat(200),
                operator: ">",
                threshold: "0.7",
                window: "3600",
              },
            ],
          },
        ],
      }),
      trigger({ eventType: ALERT_EVENT_TYPE.prompt_created }),
    ]);

    expect(name.length).toBeLessThanOrEqual(MAX_ALERT_NAME_LENGTH);
    expect(name.endsWith("+1 more")).toBe(true);
  });
});

describe("ensureUniqueAlertName", () => {
  it("leaves an unused name alone", () => {
    expect(ensureUniqueAlertName("Trace errors", ["Cost > 100"])).toBe(
      "Trace errors",
    );
  });

  it("appends a counter when the name is taken", () => {
    expect(ensureUniqueAlertName("Trace errors", ["Trace errors"])).toBe(
      "Trace errors (2)",
    );
  });

  it("skips counters that are also taken", () => {
    expect(
      ensureUniqueAlertName("Trace errors", [
        "Trace errors",
        "Trace errors (2)",
        "Trace errors (3)",
      ]),
    ).toBe("Trace errors (4)");
  });

  it("matches existing names case-insensitively and ignoring surrounding space", () => {
    expect(ensureUniqueAlertName("Trace errors", ["  TRACE ERRORS  "])).toBe(
      "Trace errors (2)",
    );
  });

  it("keeps the suffixed name within the cap", () => {
    const base = "b".repeat(MAX_ALERT_NAME_LENGTH);
    const result = ensureUniqueAlertName(base, [base]);

    expect(result.length).toBeLessThanOrEqual(MAX_ALERT_NAME_LENGTH);
    expect(result.endsWith(" (2)")).toBe(true);
  });

  it("passes an empty name straight through", () => {
    expect(ensureUniqueAlertName("", ["Trace errors"])).toBe("");
  });
});
