import React from "react";
import OverallPerformanceActionsPanel from "@/components/pages/HomePage/OverallPerformanceActionsPanel";
import ExplainerCallout from "@/components/shared/ExplainerCallout/ExplainerCallout";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

const OverallPerformanceSection = () => {
  return (
    <div className="py-6">
      <div className="sticky top-0 z-10 bg-soft-background pb-3 pt-2">
        <h2 className="comet-title-m truncate break-words">
          Overall performance
        </h2>
        <OverallPerformanceActionsPanel />
      </div>
      <div className="min-h-72">
        <ExplainerCallout {...EXPLAINERS_MAP[EXPLAINER_ID.metric_equals]} />
        <ExplainerCallout {...EXPLAINERS_MAP[EXPLAINER_ID.metric_contains]} />
        <ExplainerCallout
          {...EXPLAINERS_MAP[EXPLAINER_ID.metric_regex_match]}
        />
        <ExplainerCallout {...EXPLAINERS_MAP[EXPLAINER_ID.metric_is_json]} />
        <ExplainerCallout
          {...EXPLAINERS_MAP[EXPLAINER_ID.metric_levenshtein]}
        />

        <ExplainerCallout
          {...EXPLAINERS_MAP[EXPLAINER_ID.metric_sentence_bleu]}
        />
        <ExplainerCallout
          {...EXPLAINERS_MAP[EXPLAINER_ID.metric_corpus_bleu]}
        />
        <ExplainerCallout {...EXPLAINERS_MAP[EXPLAINER_ID.metric_rouge]} />
        <ExplainerCallout {...EXPLAINERS_MAP[EXPLAINER_ID.metric_sentiment]} />
        <ExplainerCallout
          {...EXPLAINERS_MAP[EXPLAINER_ID.metric_hallucination]}
        />
        <ExplainerCallout {...EXPLAINERS_MAP[EXPLAINER_ID.metric_g_eval]} />
        <ExplainerCallout {...EXPLAINERS_MAP[EXPLAINER_ID.metric_moderation]} />
        <ExplainerCallout {...EXPLAINERS_MAP[EXPLAINER_ID.metric_usefulness]} />
        <ExplainerCallout
          {...EXPLAINERS_MAP[EXPLAINER_ID.metric_answer_relevance]}
        />
        <ExplainerCallout
          {...EXPLAINERS_MAP[EXPLAINER_ID.metric_context_precision]}
        />
        <ExplainerCallout
          {...EXPLAINERS_MAP[EXPLAINER_ID.metric_context_recall]}
        />
      </div>
      <div className="min-h-72">Cost chart</div>
    </div>
  );
};

export default OverallPerformanceSection;
