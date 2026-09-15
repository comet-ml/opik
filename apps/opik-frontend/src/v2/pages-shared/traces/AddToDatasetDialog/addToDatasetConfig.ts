import { Database, ListChecks, LucideIcon } from "lucide-react";

import { DATASET_TYPE } from "@/types/datasets";
import { EXPLAINER_ID } from "@/v2/constants/explainers";

export type AddToDatasetTypeConfig = {
  entityName: string;
  icon: LucideIcon;
  noSelectionExplainerId: EXPLAINER_ID;
  successExplainerId: EXPLAINER_ID;
  emptyStateDescription: string;
};

export const ADD_TO_DATASET_TYPE_CONFIG: Record<
  DATASET_TYPE,
  AddToDatasetTypeConfig
> = {
  [DATASET_TYPE.DATASET]: {
    entityName: "dataset",
    icon: Database,
    noSelectionExplainerId: EXPLAINER_ID.whats_an_experiment,
    successExplainerId: EXPLAINER_ID.i_added_items_to_a_dataset_now_what,
    emptyStateDescription:
      "Define inputs and expected outputs to evaluate your LLM application's performance.",
  },
  [DATASET_TYPE.TEST_SUITE]: {
    entityName: "test suite",
    icon: ListChecks,
    noSelectionExplainerId:
      EXPLAINER_ID.why_would_i_want_to_add_traces_to_an_test_suite,
    successExplainerId: EXPLAINER_ID.i_added_traces_to_an_test_suite_now_what,
    emptyStateDescription:
      "Define test cases with assertions to evaluate your LLM application's performance.",
  },
};
