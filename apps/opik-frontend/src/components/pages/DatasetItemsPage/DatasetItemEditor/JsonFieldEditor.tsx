import React from "react";
import { useFormContext } from "react-hook-form";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { EditorState } from "@codemirror/state";
import { jsonLanguage } from "@codemirror/lang-json";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { FormErrorSkeleton } from "@/components/ui/form";

interface JsonFieldEditorProps {
  fieldName: string;
  isEditing: boolean;
  onBlur?: () => void;
}

const JsonFieldEditor: React.FC<JsonFieldEditorProps> = ({
  fieldName,
  isEditing,
  onBlur,
}) => {
  const form = useFormContext<Record<string, unknown>>();
  const theme = useCodemirrorTheme({ editable: isEditing });

  const value = form.watch(fieldName) as string;
  const fieldError = form.formState.errors[fieldName];

  const handleChange = (newValue: string) => {
    form.setValue(fieldName, newValue, { shouldDirty: true });
  };

  return (
    <div className="flex flex-col gap-2">
      <div className="overflow-hidden rounded-md border">
        <CodeMirror
          id={fieldName}
          theme={theme}
          value={value || ""}
          onChange={handleChange}
          onBlur={onBlur}
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
      {fieldError && isEditing && (
        <FormErrorSkeleton>{String(fieldError.message)}</FormErrorSkeleton>
      )}
    </div>
  );
};

export default JsonFieldEditor;
