import { useRef } from "react";
import { EditorView } from "@codemirror/view";

import GEvalField from "./GEvalField";
import DatasetVariablesHint from "../DatasetVariablesHint";

import {
  GEvalMetricParameters,
  MetricParamErrors,
} from "@/types/optimizations";

interface GEvalMetricConfigsProps {
  configs: Partial<GEvalMetricParameters>;
  onChange: (configs: Partial<GEvalMetricParameters>) => void;
  datasetVariables?: string[];
  errors?: MetricParamErrors;
}

const GEvalMetricConfigs = ({
  configs,
  onChange,
  datasetVariables = [],
  errors,
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
    <div className="flex w-72 flex-col gap-3">
      <GEvalField
        id="task_introduction"
        label="Task introduction"
        value={configs.task_introduction ?? ""}
        onChange={(value) => onChange({ ...configs, task_introduction: value })}
        placeholder="Describe the task you're evaluating"
        editorRef={taskIntroEditorRef}
        onFocus={() => {
          lastFocusedEditorRef.current = taskIntroEditorRef.current;
        }}
        error={errors?.task_introduction?.message}
      />

      <div className="space-y-1">
        <GEvalField
          id="evaluation_criteria"
          label="Evaluation criteria"
          value={configs.evaluation_criteria ?? ""}
          onChange={(value) =>
            onChange({ ...configs, evaluation_criteria: value })
          }
          placeholder="Define your evaluation criteria"
          editorRef={evalCriteriaEditorRef}
          onFocus={() => {
            lastFocusedEditorRef.current = evalCriteriaEditorRef.current;
          }}
          error={errors?.evaluation_criteria?.message}
        />

        <DatasetVariablesHint
          datasetVariables={datasetVariables}
          onSelect={handleVariableSelect}
        />
      </div>
    </div>
  );
};

export default GEvalMetricConfigs;
