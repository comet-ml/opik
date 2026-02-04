import React from "react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";

import { Label } from "@/components/ui/label";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";

import {
  mustachePlugin,
  codeMirrorPromptTheme,
} from "@/constants/codeMirrorPlugins";

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

export default GEvalField;
