import React, { useRef } from "react";
import { Label } from "@/components/ui/label";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { GEvalMetricParameters } from "@/types/optimizations";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import {
  mustachePlugin,
  codeMirrorPromptTheme,
} from "@/constants/codeMirrorPlugins";
import DatasetVariablesHint from "../DatasetVariablesHint";

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
      <div className="space-y-2">
        <div className="flex items-center">
          <Label htmlFor="task_introduction" className="mr-1.5 text-sm">
            Task introduction
          </Label>
          <ExplainerIcon
            {...EXPLAINERS_MAP[EXPLAINER_ID.geval_task_introduction]}
          />
        </div>
        <div className="min-h-16 rounded-md border border-input bg-background px-3 py-2 focus-within:border-primary">
          <CodeMirror
            onCreateEditor={(view) => {
              taskIntroEditorRef.current = view;
            }}
            onFocus={() => {
              lastFocusedEditorRef.current = taskIntroEditorRef.current;
            }}
            theme={codeMirrorPromptTheme}
            value={configs.task_introduction ?? ""}
            onChange={(value) =>
              onChange({ ...configs, task_introduction: value })
            }
            placeholder="Describe the task context and what you're evaluating..."
            basicSetup={{
              foldGutter: false,
              allowMultipleSelections: false,
              lineNumbers: false,
              highlightActiveLine: false,
            }}
            extensions={[EditorView.lineWrapping, mustachePlugin]}
          />
        </div>
      </div>

      <div className="space-y-2">
        <div className="flex items-center">
          <Label htmlFor="evaluation_criteria" className="mr-1.5 text-sm">
            Evaluation criteria
          </Label>
          <ExplainerIcon
            {...EXPLAINERS_MAP[EXPLAINER_ID.geval_evaluation_criteria]}
          />
        </div>
        <div className="min-h-16 rounded-md border border-input bg-background px-3 py-2 focus-within:border-primary">
          <CodeMirror
            onCreateEditor={(view) => {
              evalCriteriaEditorRef.current = view;
            }}
            onFocus={() => {
              lastFocusedEditorRef.current = evalCriteriaEditorRef.current;
            }}
            theme={codeMirrorPromptTheme}
            value={configs.evaluation_criteria ?? ""}
            onChange={(value) =>
              onChange({ ...configs, evaluation_criteria: value })
            }
            placeholder="Define evaluation criteria: accuracy, completeness, relevance..."
            basicSetup={{
              foldGutter: false,
              allowMultipleSelections: false,
              lineNumbers: false,
              highlightActiveLine: false,
            }}
            extensions={[EditorView.lineWrapping, mustachePlugin]}
          />
        </div>
      </div>

      <DatasetVariablesHint
        datasetVariables={datasetVariables}
        onSelect={handleVariableSelect}
      />
    </div>
  );
};

export default GEvalMetricConfigs;
