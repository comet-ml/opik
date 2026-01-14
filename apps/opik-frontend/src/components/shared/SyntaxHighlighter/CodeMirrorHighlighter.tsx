import React, {
  ReactNode,
  useRef,
  useEffect,
  useCallback,
  useMemo,
} from "react";
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
import { hyperLink } from "@uiw/codemirror-extensions-hyper-link";
import { createBase64ExpandExtension } from "./base64Extension";

export interface CodeMirrorHighlighterProps {
  searchValue?: string;
  localSearchValue?: string;
  setLocalSearchValue: (value: string) => void;
  codeOutput: CodeOutput;
  modeSelector: ReactNode;
  copyButton: ReactNode;
  withSearch?: boolean;
  scrollRef?: React.RefObject<HTMLDivElement>;
  onScroll?: (e: React.UIEvent<HTMLDivElement>) => void;
  maxHeight?: string;
}

const CodeMirrorHighlighter: React.FC<CodeMirrorHighlighterProps> = ({
  codeOutput,
  searchValue,
  localSearchValue,
  setLocalSearchValue,
  modeSelector,
  copyButton,
  withSearch,
  scrollRef,
  onScroll,
  maxHeight,
}) => {
  const viewRef = useRef<EditorView | null>(null);
  const theme = useCodemirrorTheme();
  const searchPanelTheme = useSearchPanelTheme();

  const {
    extension: searchExtension,
    findNext,
    findPrev,
    initSearch,
  } = useCodeMirrorSearch({
    searchValue: localSearchValue || searchValue,
    caseSensitive: false,
    view: viewRef.current,
    codeOutput,
  });

  const base64Extension = useMemo(() => createBase64ExpandExtension(), []);

  // Keep latest onScroll callback in ref to avoid stale closures
  const onScrollRef = useRef(onScroll);
  useEffect(() => {
    onScrollRef.current = onScroll;
  }, [onScroll]);

  const handleCreateEditor = useCallback(
    (view: EditorView) => {
      viewRef.current = view;
      initSearch(view, localSearchValue || searchValue);

      // Expose CodeMirror's scroll container via scrollRef
      if (scrollRef) {
        (scrollRef as React.MutableRefObject<HTMLDivElement | null>).current =
          view.scrollDOM as HTMLDivElement;
      }

      // Attach scroll listener - uses ref to always get latest callback
      const handleScroll = () => {
        if (onScrollRef.current) {
          onScrollRef.current({
            currentTarget: view.scrollDOM,
          } as React.UIEvent<HTMLDivElement>);
        }
      };

      view.scrollDOM.addEventListener("scroll", handleScroll, {
        passive: true,
      });
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [localSearchValue, searchValue, initSearch],
  );

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
          EditorView.lineWrapping,
          EditorState.readOnly.of(true),
          EditorView.editable.of(false),
          EditorView.contentAttributes.of({ tabindex: "0" }),
          searchPanelTheme,
          searchExtension,
          hyperLink,
          base64Extension,
          EXTENSION_MAP[codeOutput.mode] as LRLanguage,
        ]}
        maxHeight={maxHeight || "700px"}
        onCreateEditor={handleCreateEditor}
      />
    </SyntaxHighlighterLayout>
  );
};

export default CodeMirrorHighlighter;
