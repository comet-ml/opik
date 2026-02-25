import React, {
  ReactNode,
  useRef,
  useState,
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
  editable?: boolean;
  onSave?: (newInput: object) => void;
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
  editable,
  onSave,
}) => {
  const viewRef = useRef<EditorView | null>(null);
  const [isEditing, setIsEditing] = useState(false);
  const [editValue, setEditValue] = useState("");
  const theme = useCodemirrorTheme();
  const searchPanelTheme = useSearchPanelTheme();

  useEffect(() => {
    if (!editable) {
      setIsEditing(false);
      setEditValue("");
    }
  }, [editable]);

  const handleChange = useCallback(
    (value: string) => {
      if (editable) {
        setIsEditing(true);
        setEditValue(value);
      }
    },
    [editable],
  );

  const handleRun = useCallback(() => {
    try {
      const parsed = JSON.parse(editValue);
      onSave?.(parsed);
      setIsEditing(false);
    } catch {
      // Invalid JSON — do nothing
    }
  }, [editValue, onSave]);

  const handleCancel = useCallback(() => {
    setIsEditing(false);
    setEditValue("");
    if (viewRef.current) {
      viewRef.current.dispatch({
        changes: {
          from: 0,
          to: viewRef.current.state.doc.length,
          insert: codeOutput.message,
        },
      });
    }
  }, [codeOutput.message]);

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

  const extensions = useMemo(() => {
    const exts = [
      EditorView.lineWrapping,
      EditorView.contentAttributes.of({ tabindex: "0" }),
      searchPanelTheme,
      searchExtension,
      hyperLink,
      base64Extension,
      EXTENSION_MAP[codeOutput.mode] as LRLanguage,
    ];
    if (!editable) {
      exts.unshift(
        EditorState.readOnly.of(true),
        EditorView.editable.of(false),
      );
    }
    return exts;
  }, [editable, searchPanelTheme, searchExtension, base64Extension, codeOutput.mode]);

  return (
    <SyntaxHighlighterLayout
      leftHeader={modeSelector}
      rightHeader={
        <>
          {editable && isEditing && (
            <>
              <button
                onClick={handleRun}
                className="comet-body-xs-accented rounded bg-primary px-2 py-0.5 text-white hover:bg-primary/90"
              >
                Run
              </button>
              <button
                onClick={handleCancel}
                className="comet-body-xs-accented rounded border px-2 py-0.5 hover:bg-muted"
              >
                Cancel
              </button>
            </>
          )}
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
      <div className={editable ? "rounded border border-dashed border-primary/30" : ""}>
        <CodeMirror
          theme={theme}
          value={codeOutput.message}
          basicSetup={{
            searchKeymap: false,
          }}
          extensions={extensions}
          maxHeight={maxHeight || "700px"}
          onCreateEditor={handleCreateEditor}
          onChange={editable ? handleChange : undefined}
        />
      </div>
    </SyntaxHighlighterLayout>
  );
};

export default CodeMirrorHighlighter;
