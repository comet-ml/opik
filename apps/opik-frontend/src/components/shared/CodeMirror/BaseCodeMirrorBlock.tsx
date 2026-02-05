import React, { useMemo } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { EditorState } from "@codemirror/state";
import { jsonLanguage } from "@codemirror/lang-json";
import { pythonLanguage } from "@codemirror/lang-python";
import { yamlLanguage } from "@codemirror/lang-yaml";
import { LanguageSupport } from "@codemirror/language";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { cn } from "@/lib/utils";
import CopyButton from "@/components/shared/CopyButton/CopyButton";

// ============================================================================
// LANGUAGE MAPPING
// ============================================================================

/**
 * Maps language string hints to CodeMirror language extensions.
 * Supports common languages used in LLM/ML workflows.
 */
function getLanguageExtension(
  language: string | null | undefined,
): LanguageSupport | null {
  if (!language) return null;

  const lang = language.toLowerCase();

  // JSON and related
  if (lang === "json" || lang === "jsonl") {
    return new LanguageSupport(jsonLanguage);
  }

  // Python
  if (lang === "python" || lang === "py") {
    return new LanguageSupport(pythonLanguage);
  }

  // YAML
  if (lang === "yaml" || lang === "yml") {
    return new LanguageSupport(yamlLanguage);
  }

  // Default: no highlighting for unsupported languages
  return null;
}

// ============================================================================
// TYPES
// ============================================================================

export interface BaseCodeMirrorBlockProps {
  code: string;
  language?: "json" | "python" | "yaml" | string | null;
  label?: string | null;
  showLineNumbers?: boolean;
  showCopy?: boolean;
  wrap?: boolean;
  maxHeight?: string;
  className?: string;
}

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * BaseCodeMirrorBlock - Shared CodeMirror code block component
 *
 * This component provides a consistent code display experience across the app.
 * Used by both PrettyLLMMessageCodeBlock and CodeWidget.
 *
 * Features:
 * - Syntax highlighting for JSON, Python, YAML
 * - Optional line numbers
 * - Optional copy button
 * - Optional line wrapping
 * - Optional max height constraint
 *
 * Styles:
 * - Border: border-border, rounded-md
 * - Background: bg-primary-foreground
 * - Header: border-b, label text, copy button
 */
const BaseCodeMirrorBlock: React.FC<BaseCodeMirrorBlockProps> = ({
  code,
  language = null,
  label,
  showLineNumbers = true,
  showCopy = true,
  wrap = false,
  maxHeight,
  className,
}) => {
  const theme = useCodemirrorTheme();

  const extensions = useMemo(() => {
    const exts = [
      EditorState.readOnly.of(true),
      EditorView.editable.of(false),
      EditorView.contentAttributes.of({ tabindex: "0" }),
    ];

    // Add line wrapping if enabled
    if (wrap) {
      exts.push(EditorView.lineWrapping);
    }

    // Add language support if available
    const langExt = getLanguageExtension(language);
    if (langExt) {
      exts.push(langExt);
    }

    return exts;
  }, [language, wrap]);

  const basicSetup = useMemo(
    () => ({
      lineNumbers: showLineNumbers,
      foldGutter: false,
      highlightActiveLine: false,
      highlightActiveLineGutter: false,
      highlightSelectionMatches: false,
      searchKeymap: false,
    }),
    [showLineNumbers],
  );

  if (!code) return null;

  const showHeader = label || showCopy;

  return (
    <div
      className={cn(
        "overflow-hidden rounded-md border border-border bg-primary-foreground",
        className,
      )}
    >
      {/* Header */}
      {showHeader && (
        <div className="flex h-8 items-center justify-between border-b border-border px-3">
          <div className="flex items-center gap-1">
            {label && (
              <span className="comet-body-xs text-muted-slate">{label}</span>
            )}
            {language && (
              <span className="comet-body-xs text-light-slate">
                ({language})
              </span>
            )}
          </div>
          {showCopy && (
            <CopyButton
              text={code}
              tooltipText="Copy code"
              message="Code copied to clipboard"
              size="icon-2xs"
              className="p-0"
            />
          )}
        </div>
      )}

      {/* Code content with syntax highlighting */}
      <div className="[&>div>.absolute]:!hidden [&_.cm-editor]:!bg-primary-foreground [&_.cm-gutters]:!bg-primary-foreground">
        <CodeMirror
          value={code}
          theme={theme}
          extensions={extensions}
          basicSetup={basicSetup}
          maxHeight={maxHeight}
          aria-label={label || "Code block"}
        />
      </div>
    </div>
  );
};

export default BaseCodeMirrorBlock;
