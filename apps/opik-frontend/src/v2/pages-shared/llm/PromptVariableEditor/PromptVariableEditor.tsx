import React, {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
} from "react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView, keymap } from "@codemirror/view";
import { insertNewlineAndIndent } from "@codemirror/commands";
import isEmpty from "lodash/isEmpty";

import JsonTreePopover from "@/shared/JsonTreePopover/JsonTreePopover";
import { JsonObject, JsonValue } from "@/types/shared";
import { cn } from "@/lib/utils";
import {
  codeMirrorPromptTheme,
  mustachePlugin,
  VariableHintExtension,
} from "@/constants/codeMirrorPlugins";
import { useJsonPopover } from "@/v2/pages-shared/llm/LLMPromptMessages/useJsonPopover";

// @codemirror/view before 6.38 drops Chrome's own Shift+Enter line break.
// Opt-in only: the Agent playground submits on Shift+Enter.
const shiftEnterNewlineKeymap = keymap.of([
  { key: "Shift-Enter", run: insertNewlineAndIndent },
]);

export type PromptVariableEditorHandle = {
  insertAtCursor: (text: string) => void;
  focus: () => void;
  focusAtEnd: () => void;
  triggerVariableSearch: () => void;
};

type PromptVariableEditorProps = {
  value: string;
  onChange: (value: string) => void;
  onFocus?: () => void;
  onBlur?: () => void;
  placeholder?: string;
  jsonTreeData?: JsonObject | null;
  jsonTreeEmptyState?: string;
  onJsonPathSelect?: (path: string, value: JsonValue) => void;
  openInsideTag?: boolean;
  shiftEnterInsertsNewline?: boolean;
  size?: "default" | "xs";
};

const PromptVariableEditor = forwardRef<
  PromptVariableEditorHandle,
  PromptVariableEditorProps
>(
  (
    {
      value,
      onChange,
      onFocus,
      onBlur,
      placeholder,
      jsonTreeData,
      jsonTreeEmptyState,
      onJsonPathSelect,
      openInsideTag,
      shiftEnterInsertsNewline = false,
      size = "default",
    },
    ref,
  ) => {
    const editorViewRef = useRef<EditorView | null>(null);
    const focusOnCreateRef = useRef(false);
    const variableHintRef = useRef(new VariableHintExtension());
    const [focusedVariableKey, setFocusedVariableKey] = useState<string | null>(
      null,
    );
    const hasJsonData = !isEmpty(jsonTreeData);

    const insertTextAtCursor = useCallback((text: string) => {
      const view = editorViewRef.current;
      if (!view) return;
      const cursorPos = view.state.selection.main.head;
      view.dispatch({
        changes: { from: cursorPos, insert: text },
        selection: { anchor: cursorPos + text.length },
      });
      view.focus();
    }, []);

    const focusAtEnd = useCallback((view: EditorView) => {
      view.dispatch({ selection: { anchor: view.state.doc.length } });
      view.focus();
    }, []);

    const handleCreateEditor = useCallback(
      (view: EditorView) => {
        editorViewRef.current = view;
        if (focusOnCreateRef.current) {
          focusOnCreateRef.current = false;
          focusAtEnd(view);
        }
      },
      [focusAtEnd],
    );

    const {
      isJsonPopoverOpen,
      jsonSearchQuery,
      popoverPosition,
      braceStartPos,
      handleJsonPathSelect,
      handlePopoverOpenChange,
      handleEditorUpdate,
      braceKeyExtension,
      triggerVariableSearch,
    } = useJsonPopover({
      editorViewRef,
      hasJsonData,
      insertTextAtCursor,
      onJsonPathSelect,
      openInsideTag,
    });

    useImperativeHandle(ref, () => ({
      insertAtCursor: insertTextAtCursor,
      focus: () => editorViewRef.current?.focus(),
      focusAtEnd: () => {
        const view = editorViewRef.current;
        if (view) {
          focusAtEnd(view);
        } else {
          // The editor is created a render after mount, so a focus request
          // for a new editor has to wait for it.
          focusOnCreateRef.current = true;
        }
      },
      triggerVariableSearch,
    }));

    const hintText =
      isJsonPopoverOpen && !jsonSearchQuery
        ? focusedVariableKey || "Find variable"
        : null;

    useEffect(() => {
      const view = editorViewRef.current;
      if (!view) return;
      variableHintRef.current.update(view, {
        text: hintText,
        pos: braceStartPos,
      });
    }, [hintText, braceStartPos]);

    return (
      <div
        className={cn("relative", size === "xs" && "[&_.cm-editor]:text-xs")}
      >
        <CodeMirror
          onCreateEditor={handleCreateEditor}
          onFocus={onFocus}
          onBlur={onBlur}
          onUpdate={handleEditorUpdate}
          theme={codeMirrorPromptTheme}
          value={value}
          onChange={onChange}
          placeholder={placeholder}
          editable
          basicSetup={{
            foldGutter: false,
            allowMultipleSelections: false,
            lineNumbers: false,
            highlightActiveLine: false,
          }}
          extensions={[
            EditorView.lineWrapping,
            mustachePlugin,
            ...(shiftEnterInsertsNewline ? [shiftEnterNewlineKeymap] : []),
            ...(braceKeyExtension ? [braceKeyExtension] : []),
            ...(hasJsonData ? variableHintRef.current.getExtension() : []),
          ]}
        />
        {hasJsonData && (
          <JsonTreePopover
            data={jsonTreeData ?? {}}
            emptyState={jsonTreeEmptyState}
            onSelect={handleJsonPathSelect}
            open={isJsonPopoverOpen}
            onOpenChange={handlePopoverOpenChange}
            searchQuery={jsonSearchQuery}
            onFocusedPathChange={setFocusedVariableKey}
            trigger={
              <span
                className="pointer-events-none"
                aria-hidden="true"
                style={{
                  position: "absolute",
                  top: popoverPosition.top,
                  left: popoverPosition.left,
                  width: 1,
                  height: 1,
                }}
              />
            }
          />
        )}
      </div>
    );
  },
);

PromptVariableEditor.displayName = "PromptVariableEditor";

export default PromptVariableEditor;
