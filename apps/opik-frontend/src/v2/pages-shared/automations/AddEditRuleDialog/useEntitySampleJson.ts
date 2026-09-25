import { useMemo } from "react";

import useTracesOrSpansList, {
  TRACE_DATA_TYPE,
} from "@/hooks/useTracesOrSpansList";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import { JsonObject, JsonValue } from "@/types/shared";
import { THREAD_CONTEXT_VARIABLE_NAME } from "@/v2/pages-shared/automations/AddEditRuleDialog/schema";

type UseEntitySampleJsonArgs = {
  projectId: string;
  scope: EVALUATORS_RULE_SCOPE;
  datasetColumnNames?: string[];
};

const EMPTY_SAMPLE: JsonObject = { input: "…", output: "…", metadata: "…" };

/**
 * The object the prompt editor's `{{` picker browses. A recent trace or span
 * from the project gives real field paths; with nothing logged yet the three
 * root keys are still offered. Thread prompts only ever take `{{context}}`.
 */
export const useEntitySampleJson = ({
  projectId,
  scope,
  datasetColumnNames,
}: UseEntitySampleJsonArgs): JsonObject => {
  const isThreadScope = scope === EVALUATORS_RULE_SCOPE.thread;
  const fromDataset = Boolean(datasetColumnNames?.length);

  const { data } = useTracesOrSpansList(
    {
      projectId,
      type:
        scope === EVALUATORS_RULE_SCOPE.span
          ? TRACE_DATA_TYPE.spans
          : TRACE_DATA_TYPE.traces,
      page: 1,
      size: 1,
      truncate: false,
      stripAttachments: true,
    },
    { enabled: Boolean(projectId) && !isThreadScope && !fromDataset },
  );

  return useMemo(() => {
    if (isThreadScope) {
      return {
        [THREAD_CONTEXT_VARIABLE_NAME]:
          "The whole conversation, as user and assistant turns",
      };
    }
    if (fromDataset) {
      return Object.fromEntries(
        (datasetColumnNames ?? []).map((column) => [column, "…"]),
      );
    }
    const entity = data?.content?.[0];
    if (!entity) {
      return EMPTY_SAMPLE;
    }
    return {
      input: (entity.input ?? "…") as JsonValue,
      output: (entity.output ?? "…") as JsonValue,
      metadata: (entity.metadata ?? "…") as JsonValue,
    };
  }, [isThreadScope, fromDataset, datasetColumnNames, data]);
};
