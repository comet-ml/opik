import React, { forwardRef, useImperativeHandle, useState } from "react";
import CodeMirror from "@uiw/react-codemirror";

import {
  DashboardWidget,
  OllieChartWidgetType,
  WidgetEditorHandle,
} from "@/types/dashboard";
import {
  useDashboardStore,
  selectUpdatePreviewWidget,
} from "@/store/DashboardStore";
import { Label } from "@/ui/label";
import { cn } from "@/lib/utils";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";

const parseSpec = (value: string): Record<string, unknown> | null => {
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === "object" && !Array.isArray(parsed)
      ? parsed
      : null;
  } catch {
    return null;
  }
};

const OllieChartEditor = forwardRef<WidgetEditorHandle>((_, ref) => {
  const widgetData = useDashboardStore(
    (state) => state.previewWidget!,
  ) as DashboardWidget & OllieChartWidgetType;
  const updatePreviewWidget = useDashboardStore(selectUpdatePreviewWidget);
  const codemirrorTheme = useCodemirrorTheme();

  const { config } = widgetData;
  const [specText, setSpecText] = useState(() =>
    JSON.stringify(config.spec ?? {}, null, 2),
  );
  const [sqlText, setSqlText] = useState(() => config.query?.sql ?? "");
  const specValid = parseSpec(specText) !== null;

  useImperativeHandle(ref, () => ({
    submit: async () => specValid,
    isValid: specValid,
  }));

  const handleSpecChange = (value: string) => {
    setSpecText(value);
    const spec = parseSpec(value);
    if (spec) {
      updatePreviewWidget({ config: { ...config, spec } });
    }
  };

  const handleSqlChange = (value: string) => {
    setSqlText(value);
    updatePreviewWidget({
      config: { ...config, query: { ...config.query, sql: value } },
    });
  };

  return (
    <div className="space-y-4">
      {config.description && (
        <p className="comet-body-s text-muted-slate">{config.description}</p>
      )}
      {config.query && (
        <div className="space-y-2">
          <Label>Query</Label>
          <div className="overflow-hidden rounded-md border">
            <CodeMirror
              value={sqlText}
              onChange={handleSqlChange}
              theme={codemirrorTheme}
              height="160px"
            />
          </div>
          <p className="comet-body-xs text-light-slate">
            {"{{window_start}}"} and {"{{window_end}}"} follow the dashboard
            date range.
          </p>
        </div>
      )}
      <div className="space-y-2">
        <Label>Vega-Lite spec</Label>
        <div
          className={cn("overflow-hidden rounded-md border", {
            "border-destructive": !specValid,
          })}
        >
          <CodeMirror
            value={specText}
            onChange={handleSpecChange}
            theme={codemirrorTheme}
            height="240px"
          />
        </div>
        {!specValid && (
          <p className="comet-body-xs text-destructive">
            The spec must be a JSON object
          </p>
        )}
      </div>
    </div>
  );
});

OllieChartEditor.displayName = "OllieChartEditor";

export default OllieChartEditor;
