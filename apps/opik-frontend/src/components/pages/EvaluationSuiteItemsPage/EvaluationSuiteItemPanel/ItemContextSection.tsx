import { useCallback, useEffect, useRef, useState } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { jsonLanguage } from "@codemirror/lang-json";
import { cn } from "@/lib/utils";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { useDatasetItemEditorAutosaveContext } from "@/components/pages-shared/datasets/DatasetItemEditor/DatasetItemEditorAutosaveContext";

const DEBOUNCE_MS = 400;

const EDITOR_EXTENSIONS = [jsonLanguage, EditorView.lineWrapping];

const EDITOR_BASIC_SETUP = {
  lineNumbers: false,
  foldGutter: false,
  highlightActiveLine: false,
  highlightSelectionMatches: false,
} as const;

function serializeData(data: Record<string, unknown> | undefined): string {
  return data ? JSON.stringify(data, null, 2) : "{}";
}

function ItemContextSection() {
  const { datasetItem, handleFieldChange } =
    useDatasetItemEditorAutosaveContext();

  const theme = useCodemirrorTheme({ editable: true });

  const data = datasetItem?.data as Record<string, unknown> | undefined;

  const [editorValue, setEditorValue] = useState(() => serializeData(data));
  const [jsonError, setJsonError] = useState<string | null>(null);

  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const lastDataRef = useRef(data);

  useEffect(() => {
    const serializedData = serializeData(data);
    const serializedLast = serializeData(lastDataRef.current);
    if (serializedData !== serializedLast) {
      lastDataRef.current = data;
      setEditorValue(serializedData);
      setJsonError(null);
    }
  }, [data]);

  const handleChange = useCallback(
    (value: string) => {
      setEditorValue(value);

      if (debounceRef.current) {
        clearTimeout(debounceRef.current);
      }

      debounceRef.current = setTimeout(() => {
        try {
          const parsed = JSON.parse(value);
          if (typeof parsed === "object" && parsed !== null) {
            setJsonError(null);
            lastDataRef.current = parsed;
            handleFieldChange(parsed);
          } else {
            setJsonError("Must be a JSON object");
          }
        } catch {
          setJsonError("Invalid JSON");
        }
      }, DEBOUNCE_MS);
    },
    [handleFieldChange],
  );

  // Clear pending debounce when item switches (data changes) to prevent stale writes
  useEffect(() => {
    return () => {
      if (debounceRef.current) {
        clearTimeout(debounceRef.current);
      }
    };
  }, [data]);

  return (
    <div>
      <h3 className="comet-body-s-accented mb-2">Data</h3>
      <div
        className={cn(
          "overflow-hidden rounded-md border",
          jsonError && "border-destructive",
        )}
      >
        <CodeMirror
          theme={theme}
          value={editorValue}
          onChange={handleChange}
          extensions={EDITOR_EXTENSIONS}
          basicSetup={EDITOR_BASIC_SETUP}
        />
      </div>
      {jsonError && (
        <p className="comet-body-xs mt-1 text-destructive">{jsonError}</p>
      )}
    </div>
  );
}

export default ItemContextSection;
