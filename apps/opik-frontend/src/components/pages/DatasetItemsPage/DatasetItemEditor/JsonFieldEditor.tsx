import React, { useCallback } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { EditorState } from "@codemirror/state";
import { jsonLanguage } from "@codemirror/lang-json";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import useJsonInput from "@/hooks/useJsonInput";
import { FormErrorSkeleton } from "@/components/ui/form";

interface JsonFieldEditorProps {
  value: Record<string, unknown> | null | undefined;
  onChange: (value: Record<string, unknown> | null) => void;
  isEditing: boolean;
  fieldName: string;
}

const JsonFieldEditor: React.FC<JsonFieldEditorProps> = ({
  value,
  onChange,
  isEditing,
  fieldName,
}) => {
  const theme = useCodemirrorTheme({ editable: isEditing });

  const handleJsonChange = useCallback(
    (newValue: Record<string, unknown> | null) => {
      onChange(newValue);
    },
    [onChange],
  );

  const {
    jsonString,
    showInvalidJSON,
    handleJsonChange: handleChange,
    handleJsonBlur,
  } = useJsonInput({
    value,
    onChange: handleJsonChange,
  });

  return (
    <div className="flex flex-col gap-2">
      <div className="overflow-hidden rounded-md border">
        <CodeMirror
          id={fieldName}
          theme={theme}
          value={jsonString}
          onChange={handleChange}
          onBlur={handleJsonBlur}
          extensions={[
            jsonLanguage,
            EditorView.lineWrapping,
            EditorState.readOnly.of(!isEditing),
            EditorView.editable.of(isEditing),
          ]}
          basicSetup={{
            lineNumbers: false,
            foldGutter: false,
            highlightActiveLine: false,
            highlightSelectionMatches: false,
          }}
        />
      </div>
      {showInvalidJSON && isEditing && (
        <FormErrorSkeleton>Invalid JSON</FormErrorSkeleton>
      )}
    </div>
  );
};

export default JsonFieldEditor;
