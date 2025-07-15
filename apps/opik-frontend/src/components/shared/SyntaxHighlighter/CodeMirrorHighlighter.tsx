import React, { ReactNode, useRef, useState } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { EditorState } from "@codemirror/state";
import { LRLanguage } from "@codemirror/language";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { useSearchPanelTheme } from "./hooks/useSearchPanelTheme";
import { useCodeMirrorSearch } from "./hooks/useCodeMirrorSearch";
import { EXTENSION_MAP } from "./constants";
import { CodeOutput } from "./types";
import SyntaxHighlighterLayout from "./SyntaxHighlighterLayout";
import SyntaxHighlighterSearch from "./SyntaxHighlighterSearch";

export interface CodeMirrorHighlighterProps {
  searchValue?: string;
  codeOutput: CodeOutput;
  modeSelector: ReactNode;
  copyButton: ReactNode;
  withSearch?: boolean;
}

const CodeMirrorHighlighter: React.FC<CodeMirrorHighlighterProps> = ({
  codeOutput,
  searchValue,
  modeSelector,
  copyButton,
  withSearch,
}) => {
  const viewRef = useRef<EditorView | null>(null);
  const [localSearchValue, setLocalSearchValue] = useState<string>("");
  const theme = useCodemirrorTheme();
  const searchPanelTheme = useSearchPanelTheme();

  const {
    extension: searchExtension,
    findNext,
    findPrev,
    currentMatchIndex,
    totalMatches,
  } = useCodeMirrorSearch({
    searchValue: localSearchValue || searchValue,
    caseSensitive: false,
    view: viewRef.current,
    data: codeOutput,
  });

  const handleCreateEditor = (view: EditorView) => {
    viewRef.current = view;
  };

  return (
    <SyntaxHighlighterLayout
      leftHeader={modeSelector}
      rightHeader={
        <>
          {withSearch && (
            <SyntaxHighlighterSearch
              searchValue={localSearchValue}
              onSearch={setLocalSearchValue}
              onPrev={findPrev}
              onNext={findNext}
              currentMatchIndex={currentMatchIndex}
              totalMatches={totalMatches}
            />
          )}
          {copyButton}
        </>
      }
    >
      <CodeMirror
        theme={theme}
        value={codeOutput.message}
        basicSetup={{
          searchKeymap: false,
        }}
        extensions={[
          EXTENSION_MAP[codeOutput.mode] as LRLanguage,
          EditorView.lineWrapping,
          EditorState.readOnly.of(true),
          EditorView.editable.of(false),
          EditorView.contentAttributes.of({ tabindex: "0" }),
          searchPanelTheme,
          searchExtension,
        ]}
        maxHeight="150px"
        onCreateEditor={handleCreateEditor}
      />
    </SyntaxHighlighterLayout>
  );
};

export default CodeMirrorHighlighter;
