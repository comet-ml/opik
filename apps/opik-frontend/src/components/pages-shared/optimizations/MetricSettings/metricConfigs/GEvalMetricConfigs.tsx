import React, { useRef } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";

import { Label } from "@/components/ui/label";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import DatasetVariablesHint from "../DatasetVariablesHint";

import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import {
  mustachePlugin,
  codeMirrorPromptTheme,
} from "@/constants/codeMirrorPlugins";

import { GEvalMetricParameters } from "@/types/optimizations";
import { Explainer } from "@/types/shared";

const CODEMIRROR_EXTENSIONS = [EditorView.lineWrapping, mustachePlugin];
const CODEMIRROR_BASIC_SETUP = {
  foldGutter: false,
  allowMultipleSelections: false,
  lineNumbers: false,
  highlightActiveLine: false,
};

interface GEvalFieldProps {
  id: string;
  label: string;
  explainer: Explainer;
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  editorRef: React.MutableRefObject<EditorView | null>;
  onFocus: () => void;
}

const GEvalField: React.FC<GEvalFieldProps> = ({
  id,
  label,
  explainer,
  value,
  onChange,
  placeholder,
  editorRef,
  onFocus,
}) => (
  <div className="space-y-2">
    <div className="flex items-center">
      <Label htmlFor={id} className="mr-1.5 text-sm">
        {label}
      </Label>
      <ExplainerIcon {...explainer} />
    </div>
    <div className="min-h-16 rounded-md border border-input bg-background px-3 py-2 focus-within:border-primary">
      <CodeMirror
        onCreateEditor={(view) => {
          editorRef.current = view;
        }}
        onFocus={onFocus}
        theme={codeMirrorPromptTheme}
        value={value}
        onChange={onChange}
        placeholder={placeholder}
        basicSetup={CODEMIRROR_BASIC_SETUP}
        extensions={CODEMIRROR_EXTENSIONS}
      />
    </div>
  </div>
);

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
