/**
 * CodeMirror editor for system prompts with mustache variable highlighting
 */

import React, { useRef, useImperativeHandle, forwardRef } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { mustachePlugin } from "@/constants/codeMirrorPlugins";
import { SystemPromptEditorProps } from "./types";
import { cn } from "@/lib/utils";

export interface SystemPromptEditorHandle {
  /** Insert text at the current cursor position */
  insertAtCursor: (text: string) => void;
  /** Focus the editor */
  focus: () => void;
}

const SystemPromptEditor = forwardRef<
  SystemPromptEditorHandle,
  SystemPromptEditorProps
>(
  (
    { value, onChange, placeholder, readOnly = false, minHeight = "300px" },
    ref,
  ) => {
    const editorViewRef = useRef<EditorView | null>(null);

    useImperativeHandle(ref, () => ({
      insertAtCursor: (text: string) => {
        const view = editorViewRef.current;
        if (view) {
          const cursorPos = view.state.selection.main.head;
          view.dispatch({
            changes: { from: cursorPos, insert: text },
            selection: { anchor: cursorPos + text.length },
          });
          view.focus();
        }
      },
      focus: () => {
        editorViewRef.current?.focus();
      },
    }));

    return (
      <div
        className={cn(
          "rounded-md border border-input bg-background",
          // CodeMirror container styles - no max height, let dialog scroll
          "[&_.cm-editor]:min-h-[200px] [&_.cm-editor]:cursor-text [&_.cm-editor]:text-sm",
          "[&_.cm-editor.cm-focused]:outline-none",
          // Scroller padding - no overflow, expand with content
          "[&_.cm-scroller]:p-3 [&_.cm-scroller]:font-sans",
          // Line padding
          "[&_.cm-line]:px-1",
          // Content area
          "[&_.cm-content]:min-h-[200px] [&_.cm-content]:p-0",
          // Placeholder styles
          "[&_.cm-placeholder]:text-muted-foreground [&_.cm-placeholder]:font-light",
        )}
        style={{ "--editor-min-height": minHeight } as React.CSSProperties}
      >
        <CodeMirror
          onCreateEditor={(view) => {
            editorViewRef.current = view;
          }}
          value={value}
          onChange={onChange}
          placeholder={placeholder || "Enter your system prompt..."}
          editable={!readOnly}
          basicSetup={{
            foldGutter: false,
            allowMultipleSelections: false,
            lineNumbers: false,
            highlightActiveLine: false,
          }}
          extensions={[EditorView.lineWrapping, mustachePlugin]}
        />
      </div>
    );
  },
);

SystemPromptEditor.displayName = "SystemPromptEditor";

export default SystemPromptEditor;
