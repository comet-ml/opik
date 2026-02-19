import { useRef } from "react";
import { EditorView } from "@codemirror/view";

import GEvalField from "./GEvalField";
import DatasetVariablesHint from "../DatasetVariablesHint";

import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

import { GEvalMetricParameters } from "@/types/optimizations";

interface GEvalMetricConfigsProps {
  configs: Partial<GEvalMetricParameters>;
  onChange: (configs: Partial<GEvalMetricParameters>) => void;
  datasetVariables?: string[];
}

const GEvalMetricConfigs = ({
  configs,
  onChange,
  datasetVariables = [],
}: GEvalMetricConfigsProps) => {
  const taskIntroEditorRef = useRef<EditorView | null>(null);
  const evalCriteriaEditorRef = useRef<EditorView | null>(null);
  const lastFocusedEditorRef = useRef<EditorView | null>(null);

  const handleVariableSelect = (variable: string) => {
    const view = lastFocusedEditorRef.current ?? evalCriteriaEditorRef.current;
    const variableText = `{{${variable}}}`;

    if (view) {
      const cursorPos = view.state.selection.main.head;
      view.dispatch({
        changes: { from: cursorPos, insert: variableText },
        selection: { anchor: cursorPos + variableText.length },
      });
      view.focus();
    }
  };

  return (
    <div className="flex w-72 flex-col gap-6">
      <GEvalField
        id="task_introduction"
        label="Task introduction"
        explainer={EXPLAINERS_MAP[EXPLAINER_ID.geval_task_introduction]}
        value={configs.task_introduction ?? ""}
        onChange={(value) => onChange({ ...configs, task_introduction: value })}
        placeholder="Describe the task context and what you're evaluating..."
        editorRef={taskIntroEditorRef}
        onFocus={() => {
          lastFocusedEditorRef.current = taskIntroEditorRef.current;
        }}
      />

      <GEvalField
        id="evaluation_criteria"
        label="Evaluation criteria"
        explainer={EXPLAINERS_MAP[EXPLAINER_ID.geval_evaluation_criteria]}
        value={configs.evaluation_criteria ?? ""}
        onChange={(value) =>
          onChange({ ...configs, evaluation_criteria: value })
        }
        placeholder="Define evaluation criteria: accuracy, completeness, relevance..."
        editorRef={evalCriteriaEditorRef}
        onFocus={() => {
          lastFocusedEditorRef.current = evalCriteriaEditorRef.current;
        }}
      />

      <DatasetVariablesHint
        datasetVariables={datasetVariables}
        onSelect={handleVariableSelect}
      />
    </div>
  );
};

export default GEvalMetricConfigs;
