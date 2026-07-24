import React, { useMemo, useState } from "react";
import { Label } from "@/ui/label";
import { FormErrorSkeleton } from "@/ui/form";
import { Tag } from "@/ui/tag";
import { CodeMetricParameters } from "@/types/optimizations";
import CodeMirror from "@uiw/react-codemirror";
import { python } from "@codemirror/lang-python";
import { EditorView } from "@codemirror/view";
import { Extension } from "@codemirror/state";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { parsePythonMethodParameters } from "@/lib/pythonArgumentsParser";
import Autocomplete from "@/shared/Autocomplete/Autocomplete";
import { cn } from "@/lib/utils";
import { DEFAULT_CODE_METRIC_CONFIGS } from "@/constants/optimizations";

interface CodeMetricConfigsProps {
  configs: Partial<CodeMetricParameters>;
  onChange: (configs: Partial<CodeMetricParameters>) => void;
  datasetVariables?: string[];
  error?: string;
}

// Shared with the v1 editor and the form's default-value source so the
// template only lives in one place.
const DEFAULT_CODE = DEFAULT_CODE_METRIC_CONFIGS.CODE;

// Static CodeMirror configuration - defined at module level to avoid recreation on every render
const CODEMIRROR_EXTENSIONS: Extension[] = [python(), EditorView.lineWrapping];
const CODEMIRROR_BASIC_SETUP = {
  lineNumbers: true,
  foldGutter: false,
  highlightActiveLine: true,
} as const;

// Parse the `score()` signature into the params the mapping UI can bind to a
// dataset column. `self` and `*args`/`**kwargs` are dropped by the parser;
// `output` is dropped here because the backend always injects the LLM response
// under `output` and never maps it. Returns `null` when the signature can't be
// parsed (transient while the user is typing) so callers can preserve existing
// mappings instead of clearing them.
const parseScoreParams = (code: string): string[] | null => {
  if (!code) return [];
  try {
    return parsePythonMethodParameters(code, "score")
      .map((param) => param.name)
      .filter((name) => name !== "output");
  } catch {
    return null;
  }
};

const CodeMetricConfigs = ({
  configs,
  onChange,
  datasetVariables = [],
  error,
}: CodeMetricConfigsProps) => {
  const theme = useCodemirrorTheme();
  const [isFocused, setIsFocused] = useState(false);

  // Rows to render, derived from the current `score()` signature. A parse
  // failure hides the section rather than showing stale rows; the persisted
  // mappings are still preserved by handleCodeChange.
  const mappableParams = useMemo(
    () => parseScoreParams(configs.code ?? "") ?? [],
    [configs.code],
  );

  const argumentsMap = configs.arguments ?? {};

  // Keep the persisted `arguments` map in lock-step with the signature: drop
  // entries for params that no longer exist. When the signature can't be parsed
  // (mid-edit), leave the map untouched so mappings survive transient syntax
  // errors.
  const handleCodeChange = (value: string) => {
    const params = parseScoreParams(value);
    if (params === null) {
      onChange({ ...configs, code: value });
      return;
    }
    const nextArguments: Record<string, string> = {};
    params.forEach((param) => {
      if (argumentsMap[param]) nextArguments[param] = argumentsMap[param];
    });
    onChange({ ...configs, code: value, arguments: nextArguments });
  };

  // Store only non-empty mappings: an empty selection means "no rename", which
  // the backend resolves via a same-named column, so it must not persist as an
  // explicit column reference (which would otherwise fail the column-exists
  // check downstream).
  const handleArgumentChange = (param: string, column: string) => {
    const next = { ...argumentsMap };
    if (column) {
      next[param] = column;
    } else {
      delete next[param];
    }
    onChange({ ...configs, arguments: next });
  };

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
            error && !isFocused && "border-destructive",
          )}
        >
          <CodeMirror
            theme={theme}
            value={configs.code ?? ""}
            onChange={handleCodeChange}
            placeholder={DEFAULT_CODE}
            onFocus={() => setIsFocused(true)}
            onBlur={() => setIsFocused(false)}
            extensions={CODEMIRROR_EXTENSIONS}
            minHeight="200px"
            maxHeight="400px"
            basicSetup={CODEMIRROR_BASIC_SETUP}
          />
        </div>
        {error && <FormErrorSkeleton>{error}</FormErrorSkeleton>}
        <p className="text-xs text-muted-slate">
          Define a class that extends <code>BaseMetric</code> with a{" "}
          <code>score</code> method that takes <code>output</code> (the LLM
          response) and <code>**kwargs</code> (dataset fields). Read fields with{" "}
          <code>kwargs.get(&quot;field_name&quot;)</code> so a missing field
          doesn&apos;t raise, returning a <code>ScoreResult</code>.
        </p>
      </div>

      {mappableParams.length > 0 && (
        <div className="space-y-2 pt-2">
          <div>
            <Label className="text-sm">
              Argument mapping ({mappableParams.length})
            </Label>
            <p className="text-xs text-muted-slate">
              Map each <code>score()</code> argument to a dataset column. Leave
              blank to use a column with the same name.
            </p>
          </div>
          <div className="flex flex-col gap-2">
            {mappableParams.map((param) => (
              <div key={param} className="flex items-center justify-between">
                <div className="flex max-w-[45%] items-center pr-2">
                  <Tag variant="green" size="md" className="truncate">
                    {param}
                  </Tag>
                </div>
                <div className="w-[55%]">
                  <Autocomplete
                    value={argumentsMap[param] ?? ""}
                    onValueChange={(value) =>
                      handleArgumentChange(param, value)
                    }
                    items={datasetVariables}
                    placeholder="Select a dataset column"
                    emptyMessage="No dataset columns"
                  />
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};

export default CodeMetricConfigs;
