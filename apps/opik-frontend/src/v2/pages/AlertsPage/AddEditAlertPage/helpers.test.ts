import {
  alertTriggersToFormTriggers,
  formTriggersToAlertTriggers,
} from "./helpers";
import {
  ALERT_EVENT_TYPE,
  ALERT_TRIGGER_CONFIG_TYPE,
  AlertTriggerConfig,
} from "@/types/alerts";
import { GuardrailTypes } from "@/types/guardrails";

const fbScore = (
  configValue: Record<string, string>,
  groupIndex?: number | null,
): AlertTriggerConfig => ({
  type: ALERT_TRIGGER_CONFIG_TYPE["threshold:feedback_score"],
  config_value: configValue,
  ...(groupIndex === undefined ? {} : { group_index: groupIndex }),
});

describe("alertTriggersToFormTriggers", () => {
  it("buckets configs with the same group_index into one AND group", () => {
    const [trigger] = alertTriggersToFormTriggers([
      {
        event_type: ALERT_EVENT_TYPE.trace_feedback_score,
        trigger_configs: [
          fbScore(
            {
              name: "hallucination",
              operator: ">",
              threshold: "0.7",
              window: "3600",
            },
            0,
          ),
          fbScore(
            {
              name: "answer_relevance",
              operator: "<",
              threshold: "0.5",
              window: "3600",
            },
            0,
          ),
        ],
      },
    ]);

    expect(trigger.groups).toHaveLength(1);
    expect(trigger.groups?.[0].conditions).toHaveLength(2);
  });

  it("splits configs with different group_index into separate OR groups", () => {
    const [trigger] = alertTriggersToFormTriggers([
      {
        event_type: ALERT_EVENT_TYPE.trace_feedback_score,
        trigger_configs: [
          fbScore(
            { name: "a", operator: ">", threshold: "0.7", window: "3600" },
            0,
          ),
          fbScore(
            { name: "b", operator: ">", threshold: "0.8", window: "3600" },
            1,
          ),
        ],
      },
    ]);

    expect(trigger.groups).toHaveLength(2);
    expect(trigger.groups?.[0].conditions[0].name).toBe("a");
    expect(trigger.groups?.[1].conditions[0].name).toBe("b");
  });

  it("treats legacy configs without group_index as separate OR groups (own group of one)", () => {
    const [trigger] = alertTriggersToFormTriggers([
      {
        event_type: ALERT_EVENT_TYPE.trace_feedback_score,
        trigger_configs: [
          fbScore({
            name: "a",
            operator: ">",
            threshold: "0.7",
            window: "3600",
          }),
          fbScore({
            name: "b",
            operator: ">",
            threshold: "0.8",
            window: "3600",
          }),
        ],
      },
    ]);

    expect(trigger.groups).toHaveLength(2);
    expect(trigger.groups?.[0].conditions).toHaveLength(1);
    expect(trigger.groups?.[1].conditions).toHaveLength(1);
  });

  it("loads partial configs without dropping them so users can edit incomplete alerts", () => {
    const [trigger] = alertTriggersToFormTriggers([
      {
        event_type: ALERT_EVENT_TYPE.trace_feedback_score,
        trigger_configs: [
          // missing 'name' — must still load (no filter), so the editor shows the empty field
          fbScore({ operator: ">", threshold: "0.7", window: "3600" }, 0),
        ],
      },
    ]);

    expect(trigger.groups).toHaveLength(1);
    expect(trigger.groups?.[0].conditions[0].name).toBe("");
    expect(trigger.groups?.[0].conditions[0].threshold).toBe("0.7");
  });

  it("normalizes unknown operator strings to '>'", () => {
    const [trigger] = alertTriggersToFormTriggers([
      {
        event_type: ALERT_EVENT_TYPE.trace_feedback_score,
        trigger_configs: [
          fbScore(
            {
              name: "a",
              operator: "garbage",
              threshold: "0.7",
              window: "3600",
            },
            0,
          ),
        ],
      },
    ]);

    expect(trigger.groups?.[0].conditions[0].operator).toBe(">");
  });
});

describe("window_in_seconds alias", () => {
  it("loads simple threshold window from window_in_seconds when canonical window is absent", () => {
    const [trigger] = alertTriggersToFormTriggers([
      {
        event_type: ALERT_EVENT_TYPE.trace_errors,
        trigger_configs: [
          {
            type: ALERT_TRIGGER_CONFIG_TYPE["threshold:errors"],
            config_value: { threshold: "2", window_in_seconds: "300" },
          },
        ],
      },
    ]);

    expect(trigger.window).toBe("300");
    expect(trigger.threshold).toBe("2");
  });

  it("prefers canonical window over window_in_seconds on simple thresholds", () => {
    const [trigger] = alertTriggersToFormTriggers([
      {
        event_type: ALERT_EVENT_TYPE.trace_cost,
        trigger_configs: [
          {
            type: ALERT_TRIGGER_CONFIG_TYPE["threshold:cost"],
            config_value: {
              threshold: "2",
              window: "300",
              window_in_seconds: "999",
            },
          },
        ],
      },
    ]);

    expect(trigger.window).toBe("300");
  });

  it("falls back to window_in_seconds when canonical window is blank", () => {
    const [trigger] = alertTriggersToFormTriggers([
      {
        event_type: ALERT_EVENT_TYPE.trace_latency,
        trigger_configs: [
          {
            type: ALERT_TRIGGER_CONFIG_TYPE["threshold:latency"],
            config_value: {
              threshold: "2",
              window: "   ",
              window_in_seconds: "300",
            },
          },
        ],
      },
    ]);

    expect(trigger.window).toBe("300");
  });

  it("loads grouped feedback-score window from window_in_seconds", () => {
    const [trigger] = alertTriggersToFormTriggers([
      {
        event_type: ALERT_EVENT_TYPE.trace_feedback_score,
        trigger_configs: [
          fbScore(
            {
              name: "a",
              operator: ">",
              threshold: "0.7",
              window_in_seconds: "3600",
            },
            0,
          ),
        ],
      },
    ]);

    expect(trigger.groups?.[0].conditions[0].window).toBe("3600");
  });

  it("serializes the resolved window as canonical window so alias-only alerts stay editable", () => {
    const form = alertTriggersToFormTriggers([
      {
        event_type: ALERT_EVENT_TYPE.trace_errors,
        trigger_configs: [
          {
            type: ALERT_TRIGGER_CONFIG_TYPE["threshold:errors"],
            config_value: { threshold: "2", window_in_seconds: "300" },
          },
        ],
      },
    ]);
    const back = formTriggersToAlertTriggers(form);

    expect(back[0].trigger_configs).toHaveLength(1);
    expect(back[0].trigger_configs?.[0].config_value).toEqual({
      threshold: "2",
      window: "300",
    });
  });

  it("round-trips grouped alias-only configs without dropping trigger_configs", () => {
    const form = alertTriggersToFormTriggers([
      {
        event_type: ALERT_EVENT_TYPE.trace_thread_feedback_score,
        trigger_configs: [
          fbScore(
            {
              name: "a",
              operator: ">",
              threshold: "0.7",
              window_in_seconds: "3600",
            },
            0,
          ),
        ],
      },
    ]);
    const back = formTriggersToAlertTriggers(form);

    expect(back[0].trigger_configs).toHaveLength(1);
    expect(back[0].trigger_configs?.[0].config_value.window).toBe("3600");
    expect(
      back[0].trigger_configs?.[0].config_value.window_in_seconds,
    ).toBeUndefined();
  });
});

describe("formTriggersToAlertTriggers", () => {
  it("flattens groups and stamps group_index per group", () => {
    const [trigger] = formTriggersToAlertTriggers([
      {
        eventType: ALERT_EVENT_TYPE.trace_feedback_score,
        groups: [
          {
            conditions: [
              { name: "a", operator: ">", threshold: "0.7", window: "3600" },
              { name: "b", operator: "<", threshold: "0.5", window: "3600" },
            ],
          },
          {
            conditions: [
              { name: "c", operator: ">", threshold: "0.8", window: "3600" },
            ],
          },
        ],
      },
    ]);

    expect(trigger.trigger_configs).toHaveLength(3);
    expect(trigger.trigger_configs?.[0].group_index).toBe(0);
    expect(trigger.trigger_configs?.[1].group_index).toBe(0);
    expect(trigger.trigger_configs?.[2].group_index).toBe(1);
  });

  it("drops conditions that are missing threshold or window (can't persist a partial threshold config)", () => {
    const [trigger] = formTriggersToAlertTriggers([
      {
        eventType: ALERT_EVENT_TYPE.trace_feedback_score,
        groups: [
          {
            conditions: [
              { name: "a", operator: ">", threshold: "0.7", window: "3600" },
              { name: "b", operator: ">", threshold: "", window: "3600" }, // dropped
            ],
          },
        ],
      },
    ]);

    expect(trigger.trigger_configs).toHaveLength(1);
    expect(trigger.trigger_configs?.[0].config_value.name).toBe("a");
  });

  it("round-trips grouped configs back to the same shape", () => {
    const incoming: AlertTriggerConfig[] = [
      fbScore(
        { name: "a", operator: ">", threshold: "0.7", window: "3600" },
        0,
      ),
      fbScore(
        { name: "b", operator: "<", threshold: "0.5", window: "3600" },
        0,
      ),
      fbScore(
        { name: "c", operator: ">", threshold: "0.8", window: "3600" },
        1,
      ),
    ];
    const form = alertTriggersToFormTriggers([
      {
        event_type: ALERT_EVENT_TYPE.trace_feedback_score,
        trigger_configs: incoming,
      },
    ]);
    const back = formTriggersToAlertTriggers(form);

    expect(back[0].trigger_configs).toHaveLength(3);
    expect(back[0].trigger_configs?.map((c) => c.group_index)).toEqual([
      0, 0, 1,
    ]);
    expect(back[0].trigger_configs?.map((c) => c.config_value.name)).toEqual([
      "a",
      "b",
      "c",
    ]);
  });

  it("compresses non-contiguous incoming group_index to a contiguous 0,1,... on save", () => {
    const incoming: AlertTriggerConfig[] = [
      fbScore(
        { name: "a", operator: ">", threshold: "0.7", window: "3600" },
        5,
      ),
      fbScore(
        { name: "b", operator: ">", threshold: "0.8", window: "3600" },
        9,
      ),
    ];
    const form = alertTriggersToFormTriggers([
      {
        event_type: ALERT_EVENT_TYPE.trace_feedback_score,
        trigger_configs: incoming,
      },
    ]);
    const back = formTriggersToAlertTriggers(form);

    expect(back[0].trigger_configs?.map((c) => c.group_index)).toEqual([0, 1]);
  });
});

describe("guardrail type filtering", () => {
  it("builds a filter:guardrail_type config from selected guardrail types", () => {
    const [trigger] = formTriggersToAlertTriggers([
      {
        eventType: ALERT_EVENT_TYPE.trace_guardrails_triggered,
        guardrailTypes: [GuardrailTypes.PII, GuardrailTypes.TOPIC],
      },
    ]);

    expect(trigger.trigger_configs).toHaveLength(1);
    expect(trigger.trigger_configs?.[0].type).toBe(
      ALERT_TRIGGER_CONFIG_TYPE["filter:guardrail_type"],
    );
    expect(trigger.trigger_configs?.[0].config_value.guardrail_types).toBe(
      "PII,TOPIC",
    );
  });

  it("omits the config when no guardrail types are selected (fires for any type)", () => {
    const [trigger] = formTriggersToAlertTriggers([
      { eventType: ALERT_EVENT_TYPE.trace_guardrails_triggered },
    ]);

    expect(trigger.trigger_configs).toHaveLength(0);
  });

  it("round-trips guardrail types back to the same selection", () => {
    const incoming: AlertTriggerConfig[] = [
      {
        type: ALERT_TRIGGER_CONFIG_TYPE["filter:guardrail_type"],
        config_value: { guardrail_types: "PII" },
      },
    ];
    const form = alertTriggersToFormTriggers([
      {
        event_type: ALERT_EVENT_TYPE.trace_guardrails_triggered,
        trigger_configs: incoming,
      },
    ]);

    expect(form[0].guardrailTypes).toEqual([GuardrailTypes.PII]);

    const back = formTriggersToAlertTriggers(form);
    expect(back[0].trigger_configs?.[0].config_value.guardrail_types).toBe(
      "PII",
    );
  });
});
