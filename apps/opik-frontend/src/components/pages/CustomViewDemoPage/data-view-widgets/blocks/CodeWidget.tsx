import React, { useMemo } from "react";
import { z } from "zod";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { EditorState } from "@codemirror/state";
import { jsonLanguage } from "@codemirror/lang-json";
import { pythonLanguage } from "@codemirror/lang-python";
import { yamlLanguage } from "@codemirror/lang-yaml";
import { LanguageSupport } from "@codemirror/language";
import {
  DynamicString,
  NullableDynamicString,
  NullableDynamicBoolean,
} from "@/lib/data-view";
import CopyButton from "@/components/shared/CopyButton/CopyButton";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";

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

export interface CodeWidgetProps {
  content: string;
  language?: string | null;
  label?: string | null;
  wrap?: boolean;
  showLineNumbers?: boolean;
  showCopy?: boolean;
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const codeWidgetConfig = {
  type: "Code" as const,
  category: "block" as const,
  schema: z.object({
    content: DynamicString.describe("Code content to display"),
    language: NullableDynamicString.describe(
      "Language hint (e.g., json, python, javascript)",
    ),
    label: NullableDynamicString.describe(
      "Label for the code block (e.g., Function Input)",
    ),
    wrap: NullableDynamicBoolean.describe(
      "Enable line wrapping vs horizontal scroll",
    ),
    showLineNumbers: NullableDynamicBoolean.describe("Show line numbers"),
    showCopy: NullableDynamicBoolean.describe("Show copy button in header"),
  }),
  description:
    "Syntax highlighted code block with optional line numbers and copy functionality.",
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * CodeWidget - Syntax highlighted code display
 *
 * Figma reference: Node 239-15130
 * Styles:
 * - Border: #E2E8F0 (border-border), rounded-md
 * - Background: #F8FAFC (bg-primary-foreground)
 * - Header: border-b, 12px Inter Regular #64748B (comet-body-xs text-muted-slate)
 * - Line numbers: 15px Ubuntu Mono, #94A3B8 (text-light-slate)
 * - Code: 15px Ubuntu Mono, #374151
 *
 * Props:
 * - content: Code string to display
 * - language: Optional language hint (for future syntax highlighting)
 * - label: Header label (e.g., "Function Input")
 * - wrap: Enable line wrapping (default: false, horizontal scroll)
 * - showLineNumbers: Show line numbers (default: true)
 * - showCopy: Show copy button (default: true)
 */
export const CodeWidget: React.FC<CodeWidgetProps> = ({
  content,
  language,
  label,
  wrap = false,
  showLineNumbers = true,
  showCopy = true,
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

  if (!content) return null;

  return (
    <div className="overflow-hidden rounded-md border border-border bg-primary-foreground">
      {/* Header */}
      {(label || showCopy) && (
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
              text={content}
              tooltipText="Copy code"
              message="Code copied"
              size="icon-xs"
            />
          )}
        </div>
      )}

      {/* Code content with syntax highlighting */}
      <CodeMirror
        value={content}
        theme={theme}
        extensions={extensions}
        basicSetup={{
          lineNumbers: showLineNumbers,
          foldGutter: false,
          highlightActiveLine: false,
          highlightSelectionMatches: false,
          searchKeymap: false,
        }}
        maxHeight="400px"
        aria-label={label || "Code block"}
      />
    </div>
  );
};

export default CodeWidget;
