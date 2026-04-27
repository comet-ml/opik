import { Explainer } from "@/types/shared";
import { buildDocsUrl } from "@/v1/lib/utils";
import {
  EXPLAINER_ID,
  EXPLAINERS_MAP as BASE_EXPLAINERS,
} from "@/constants/explainers";

export { EXPLAINER_ID };

export const EXPLAINERS_MAP: Record<EXPLAINER_ID, Explainer> = {
  ...BASE_EXPLAINERS,
  [EXPLAINER_ID.i_created_a_project_now_what]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.i_created_a_project_now_what],
    docLink: buildDocsUrl("/tracing/log_traces"),
  },
  [EXPLAINER_ID.what_are_traces]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.what_are_traces],
    docLink: buildDocsUrl("/tracing/log_traces"),
  },
  [EXPLAINER_ID.what_are_threads]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.what_are_threads],
    docLink: buildDocsUrl("/tracing/log_chat_conversations"),
  },
  [EXPLAINER_ID.whats_online_evaluation]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.whats_online_evaluation],
    docLink: buildDocsUrl("/production/rules"),
  },
  [EXPLAINER_ID.i_added_traces_to_an_test_suite_now_what]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.i_added_traces_to_an_test_suite_now_what],
    docLink: buildDocsUrl("/evaluation/overview", "#running-an-evaluation"),
  },
  [EXPLAINER_ID.i_added_items_to_a_dataset_now_what]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.i_added_items_to_a_dataset_now_what],
    docLink: buildDocsUrl("/evaluation/overview", "#running-an-evaluation"),
  },
  [EXPLAINER_ID.why_would_i_want_to_add_traces_to_an_test_suite]: {
    id: EXPLAINER_ID.why_would_i_want_to_add_traces_to_an_test_suite,
    description:
      "Add traces to a test suite to evaluate your agent's performance using real production data.",
  },
  [EXPLAINER_ID.hows_the_cost_estimated]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.hows_the_cost_estimated],
    docLink: buildDocsUrl("/tracing/cost_tracking"),
  },
  [EXPLAINER_ID.hows_the_thread_cost_estimated]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.hows_the_thread_cost_estimated],
    docLink: buildDocsUrl("/tracing/cost_tracking"),
  },
  [EXPLAINER_ID.whats_that_prompt_select]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.whats_that_prompt_select],
    docLink: buildDocsUrl(
      "/production/rules",
      "#writing-your-own-llm-as-a-judge-metric",
    ),
  },
  [EXPLAINER_ID.what_are_annotation_queues]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.what_are_annotation_queues],
    docLink: buildDocsUrl("/evaluation/annotation_queues"),
  },
  [EXPLAINER_ID.whats_an_experiment]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.whats_an_experiment],
    docLink: buildDocsUrl("/evaluation/overview"),
  },
  [EXPLAINER_ID.whats_a_prompt_commit]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.whats_a_prompt_commit],
    docLink: buildDocsUrl("/prompt_engineering/prompt_management"),
  },
  [EXPLAINER_ID.what_are_experiment_items]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.what_are_experiment_items],
    docLink: buildDocsUrl(
      "/evaluation/overview",
      "#analyzing-evaluation-results",
    ),
  },
  [EXPLAINER_ID.whats_the_experiment_configuration]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.whats_the_experiment_configuration],
    docLink: buildDocsUrl("/evaluation/concepts", "#experiment-configuration"),
  },
  [EXPLAINER_ID.what_does_it_mean_to_compare_my_experiments]: {
    ...BASE_EXPLAINERS[
      EXPLAINER_ID.what_does_it_mean_to_compare_my_experiments
    ],
    docLink: buildDocsUrl(
      "/evaluation/overview",
      "#analyzing-evaluation-results",
    ),
  },
  [EXPLAINER_ID.whats_the_test_suite_item]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.whats_the_test_suite_item],
    docLink: buildDocsUrl("/evaluation/concepts", "#experiments"),
  },
  [EXPLAINER_ID.whats_a_test_suite]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.whats_a_test_suite],
    docLink: buildDocsUrl("/evaluation/concepts", "#experiments"),
  },
  [EXPLAINER_ID.whats_the_prompt_library]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.whats_the_prompt_library],
    docLink: buildDocsUrl("/prompt_engineering/prompt_management"),
  },
  [EXPLAINER_ID.how_do_i_use_this_prompt]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.how_do_i_use_this_prompt],
    docLink: buildDocsUrl(
      "/prompt_engineering/prompt_management",
      "#linking-prompts-to-experiments",
    ),
  },
  [EXPLAINER_ID.what_are_commits]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.what_are_commits],
    docLink: buildDocsUrl(
      "/prompt_engineering/prompt_management",
      "#managing-prompts-stored-in-code",
    ),
  },
  [EXPLAINER_ID.how_do_i_write_my_prompt]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.how_do_i_write_my_prompt],
    docLink: buildDocsUrl("/prompt_engineering/prompt_management"),
  },
  [EXPLAINER_ID.what_happens_if_i_edit_my_prompt]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.what_happens_if_i_edit_my_prompt],
    docLink: buildDocsUrl("/prompt_engineering/prompt_management"),
  },
  [EXPLAINER_ID.whats_the_playground]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.whats_the_playground],
    docLink: buildDocsUrl("/prompt_engineering/playground"),
  },
  [EXPLAINER_ID.whats_an_optimization_run]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.whats_an_optimization_run],
    docLink: buildDocsUrl("/agent_optimization/overview"),
  },
  [EXPLAINER_ID.why_would_i_compare_commits]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.why_would_i_compare_commits],
    docLink: buildDocsUrl("/evaluation/evaluate_prompt"),
  },
  [EXPLAINER_ID.whats_the_optimizer]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.whats_the_optimizer],
    docLink: buildDocsUrl("/agent_optimization/optimization/concepts"),
  },
  [EXPLAINER_ID.what_are_trial_items]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.what_are_trial_items],
    docLink: buildDocsUrl("/agent_optimization/optimization/concepts"),
  },
  [EXPLAINER_ID.metric_equals]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.metric_equals],
    docLink: buildDocsUrl("/evaluation/metrics/heuristic_metrics", "#equals"),
  },
  [EXPLAINER_ID.metric_contains]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.metric_contains],
    docLink: buildDocsUrl("/evaluation/metrics/heuristic_metrics", "#contains"),
  },
  [EXPLAINER_ID.metric_regex_match]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.metric_regex_match],
    docLink: buildDocsUrl(
      "/evaluation/metrics/heuristic_metrics",
      "#regexmatch",
    ),
  },
  [EXPLAINER_ID.metric_is_json]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.metric_is_json],
    docLink: buildDocsUrl("/evaluation/metrics/heuristic_metrics", "#isjson"),
  },
  [EXPLAINER_ID.metric_levenshtein]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.metric_levenshtein],
    docLink: buildDocsUrl(
      "/evaluation/metrics/heuristic_metrics",
      "#levenshteinratio",
    ),
  },
  [EXPLAINER_ID.metric_sentence_bleu]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.metric_sentence_bleu],
    docLink: buildDocsUrl("/evaluation/metrics/heuristic_metrics", "#bleu"),
  },
  [EXPLAINER_ID.metric_corpus_bleu]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.metric_corpus_bleu],
    docLink: buildDocsUrl("/evaluation/metrics/heuristic_metrics", "#bleu"),
  },
  [EXPLAINER_ID.metric_rouge]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.metric_rouge],
    docLink: buildDocsUrl("/evaluation/metrics/heuristic_metrics", "#rouge"),
  },
  [EXPLAINER_ID.metric_hallucination]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.metric_hallucination],
    docLink: buildDocsUrl("/evaluation/metrics/hallucination"),
  },
  [EXPLAINER_ID.metric_g_eval]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.metric_g_eval],
    docLink: buildDocsUrl("/evaluation/metrics/g_eval"),
  },
  [EXPLAINER_ID.metric_moderation]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.metric_moderation],
    docLink: buildDocsUrl("/evaluation/metrics/moderation"),
  },
  [EXPLAINER_ID.metric_usefulness]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.metric_usefulness],
    docLink: buildDocsUrl("/evaluation/metrics/usefulness"),
  },
  [EXPLAINER_ID.metric_answer_relevance]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.metric_answer_relevance],
    docLink: buildDocsUrl("/evaluation/metrics/answer_relevance"),
  },
  [EXPLAINER_ID.metric_context_precision]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.metric_context_precision],
    docLink: buildDocsUrl("/evaluation/metrics/context_precision"),
  },
  [EXPLAINER_ID.metric_context_recall]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.metric_context_recall],
    docLink: buildDocsUrl("/evaluation/metrics/context_recall"),
  },
  [EXPLAINER_ID.prompt_generation_learn_more]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.prompt_generation_learn_more],
    docLink: buildDocsUrl("/prompt_engineering/improve", "#prompt-generator"),
  },
  [EXPLAINER_ID.prompt_improvement_learn_more]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.prompt_improvement_learn_more],
    docLink: buildDocsUrl("/prompt_engineering/improve", "#prompt-improver"),
  },
  [EXPLAINER_ID.prompt_improvement_optimizer]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.prompt_improvement_optimizer],
    docLink: buildDocsUrl("/agent_optimization/overview"),
  },
  [EXPLAINER_ID.whats_an_alert]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.whats_an_alert],
    docLink: buildDocsUrl("/production/alerts"),
  },
  [EXPLAINER_ID.what_are_dashboards]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.what_are_dashboards],
    docLink: buildDocsUrl("/production/dashboards"),
  },
  [EXPLAINER_ID.whats_the_optimization_config]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.whats_the_optimization_config],
    docLink: buildDocsUrl("/agent_optimization/optimization_studio"),
  },
  [EXPLAINER_ID.whats_the_metric_settings]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.whats_the_metric_settings],
    docLink: buildDocsUrl("/evaluation/metrics/overview"),
  },
  [EXPLAINER_ID.whats_the_algorithm_settings]: {
    ...BASE_EXPLAINERS[EXPLAINER_ID.whats_the_algorithm_settings],
    docLink: buildDocsUrl("/agent_optimization/optimization/concepts"),
  },
};
