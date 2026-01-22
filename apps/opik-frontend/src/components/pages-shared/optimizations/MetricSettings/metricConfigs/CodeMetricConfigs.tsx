import React, { useState } from "react";
import { Label } from "@/components/ui/label";
import { CodeMetricParameters } from "@/types/optimizations";
import CodeMirror from "@uiw/react-codemirror";
import { python } from "@codemirror/lang-python";
import { EditorView } from "@codemirror/view";
import { Extension } from "@codemirror/state";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { cn } from "@/lib/utils";

interface CodeMetricConfigsProps {
  configs: Partial<CodeMetricParameters>;
  onChange: (configs: Partial<CodeMetricParameters>) => void;
}

const DEFAULT_CODE = `from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult

class MyMetric(BaseMetric):
    def __init__(self, name: str = "my_metric"):
        super().__init__(name=name)
    
    def score(self, output: str, **kwargs) -> ScoreResult:
        # output: the LLM response
        # kwargs: contains dataset_item fields
        return ScoreResult(
            name=self.name,
            value=1.0,
            reason="Evaluation passed"
        )`;

// Static CodeMirror configuration - defined at module level to avoid recreation on every render
const CODEMIRROR_EXTENSIONS: Extension[] = [python(), EditorView.lineWrapping];
const CODEMIRROR_BASIC_SETUP = {
  lineNumbers: true,
  foldGutter: false,
  highlightActiveLine: true,
} as const;

const CodeMetricConfigs = ({ configs, onChange }: CodeMetricConfigsProps) => {
  const theme = useCodemirrorTheme();
  const [isFocused, setIsFocused] = useState(false);

  return (
    <div className="flex w-full flex-col gap-2">
      <div className="space-y-2">
        <Label htmlFor="code" className="text-sm">
          Python code
        </Label>
        <div
          className={cn(
            "overflow-hidden rounded-md border bg-primary-foreground transition-colors",
            isFocused && "border-primary",
          )}
        >
          <CodeMirror
            theme={theme}
            value={configs.code ?? ""}
            onChange={(value) => onChange({ ...configs, code: value })}
            placeholder={DEFAULT_CODE}
            onFocus={() => setIsFocused(true)}
            onBlur={() => setIsFocused(false)}
            extensions={CODEMIRROR_EXTENSIONS}
            minHeight="200px"
            maxHeight="400px"
            basicSetup={CODEMIRROR_BASIC_SETUP}
          />
        </div>
        <p className="text-xs text-muted-slate">
          Define a class that extends <code>BaseMetric</code> with a{" "}
          <code>score</code> method that takes <code>output</code> (the LLM
          response) and <code>**kwargs</code> (dataset fields), returning a{" "}
          <code>ScoreResult</code>.
        </p>
      </div>
    </div>
  );
};

export default CodeMetricConfigs;
