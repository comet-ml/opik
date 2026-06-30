import React from "react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";

import { Label } from "@/ui/label";

import {
  mustachePlugin,
  codeMirrorPromptTheme,
} from "@/constants/codeMirrorPlugins";

// Match the Figma card text leading (20px for 14px text).
const roomyLineHeight = EditorView.theme({
  ".cm-content": { lineHeight: "20px" },
});
const CODEMIRROR_EXTENSIONS = [
  EditorView.lineWrapping,
  mustachePlugin,
  roomyLineHeight,
];
const CODEMIRROR_BASIC_SETUP = {
  foldGutter: false,
  allowMultipleSelections: false,
  lineNumbers: false,
  highlightActiveLine: false,
};

interface GEvalFieldProps {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  editorRef: React.MutableRefObject<EditorView | null>;
  onFocus: () => void;
}

const GEvalField: React.FC<GEvalFieldProps> = ({
  id,
  label,
  value,
  onChange,
  placeholder,
  editorRef,
  onFocus,
}) => (
  <div className="space-y-1">
    <Label htmlFor={id} className="text-sm">
      {label}
    </Label>
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

export default GEvalField;
