import React, { useMemo, useRef } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { EditorState } from "@codemirror/state";
import { LRLanguage } from "@codemirror/language";
import { hyperLink } from "@uiw/codemirror-extensions-hyper-link";
import ReactMarkdown from "react-markdown";
import rehypeRaw from "rehype-raw";
import remarkBreaks from "remark-breaks";
import remarkGfm from "remark-gfm";
import { isNull } from "lodash";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { useSearchPanelTheme } from "@/shared/SyntaxHighlighter/hooks/useSearchPanelTheme";
import { useCodeMirrorSearch } from "@/shared/SyntaxHighlighter/hooks/useCodeMirrorSearch";
import { useMarkdownSearch } from "@/shared/SyntaxHighlighter/hooks/useMarkdownSearch";
import { createBase64ExpandExtension } from "@/shared/SyntaxHighlighter/base64Extension";
import { EXTENSION_MAP, MODE_TYPE } from "@/shared/SyntaxHighlighter/constants";
import { CodeOutput } from "@/shared/SyntaxHighlighter/types";
import { cn, isStringMarkdown } from "@/lib/utils";
import LinkifyText from "@/shared/LinkifyText/LinkifyText";

type CodeBlockBodyProps = {
  code: CodeOutput;
  searchValue?: string;
};

const CodeBlockBody: React.FC<CodeBlockBodyProps> = ({ code, searchValue }) => {
  if (code.mode === MODE_TYPE.pretty) {
    return <MarkdownBody code={code} searchValue={searchValue} />;
  }
  return <CodeMirrorBody code={code} searchValue={searchValue} />;
};

const MarkdownBody: React.FC<CodeBlockBodyProps> = ({ code, searchValue }) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const { searchPlugin, searchPlainText } = useMarkdownSearch({
    searchValue,
    container: containerRef.current,
  });

  const content = useMemo(() => {
    if (isNull(code.message)) return "";

    if (isStringMarkdown(code.message)) {
      return (
        <ReactMarkdown
          className={cn("prose dark:prose-invert comet-markdown")}
          remarkPlugins={[remarkBreaks, remarkGfm, searchPlugin]}
          rehypePlugins={[rehypeRaw]}
        >
          {code.message}
        </ReactMarkdown>
      );
    }

    return (
      <div className="comet-markdown whitespace-pre-wrap">
        <LinkifyText>{searchPlainText(code.message)}</LinkifyText>
      </div>
    );
  }, [code.message, searchPlugin, searchPlainText]);

  return (
    <div
      ref={containerRef}
      className="comet-body-s min-h-[22px] px-2 text-foreground-secondary"
    >
      {content}
    </div>
  );
};

const CodeMirrorBody: React.FC<CodeBlockBodyProps> = ({
  code,
  searchValue,
}) => {
  const viewRef = useRef<EditorView | null>(null);
  const theme = useCodemirrorTheme({ transparent: true });
  const searchPanelTheme = useSearchPanelTheme();

  const { extension: searchExtension, initSearch } = useCodeMirrorSearch({
    searchValue,
    caseSensitive: false,
    view: viewRef.current,
    codeOutput: code,
  });

  const base64Extension = useMemo(() => createBase64ExpandExtension(), []);

  const handleCreateEditor = (view: EditorView) => {
    viewRef.current = view;
    initSearch(view, searchValue);
  };

  return (
    <div className="px-2">
      <CodeMirror
        theme={theme}
        value={code.message}
        basicSetup={{
          searchKeymap: false,
          lineNumbers: false,
          foldGutter: false,
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
          EXTENSION_MAP[code.mode] as LRLanguage,
        ]}
        maxHeight="700px"
        onCreateEditor={handleCreateEditor}
      />
    </div>
  );
};

export default CodeBlockBody;
