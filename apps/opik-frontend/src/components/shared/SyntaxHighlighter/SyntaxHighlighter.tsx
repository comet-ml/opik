import React, { useMemo, useState } from "react";
import useLocalStorageState from "use-local-storage-state";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { EditorState } from "@codemirror/state";
import { LRLanguage } from "@codemirror/language";
import { jsonLanguage } from "@codemirror/lang-json";
import { yamlLanguage } from "@codemirror/lang-yaml";
import * as yml from "js-yaml";

import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import CopyButton from "@/components/shared/CopyButton/CopyButton";
import { prettifyMessage } from "@/lib/traces";
import { useSearchPanelTheme } from "@/hooks/useSearchPanelTheme";
import { search } from "@codemirror/search";

enum MODE_TYPE {
  yaml = "yaml",
  json = "json",
  pretty = "pretty",
}

const DEFAULT_OPTIONS = [
  { value: MODE_TYPE.yaml, label: "YAML" },
  { value: MODE_TYPE.json, label: "JSON" },
];

const EXTENSION_MAP: { [key in MODE_TYPE]: LRLanguage | null } = {
  [MODE_TYPE.yaml]: yamlLanguage,
  [MODE_TYPE.json]: jsonLanguage,
  [MODE_TYPE.pretty]: null,
};

const UNUSED_SYNTAX_HIGHLIGHTER_KEY = "__unused_syntax_highlighter_key__";

type PrettifyConfig = {
  fieldType: "input" | "output";
};

type SyntaxHighlighterProps = {
  data: object;
  prettifyConfig?: PrettifyConfig;
  preserveKey?: string;
};

const SyntaxHighlighter: React.FunctionComponent<SyntaxHighlighterProps> = ({
  data,
  prettifyConfig,
  preserveKey,
}) => {
  const defaultMode = prettifyConfig ? MODE_TYPE.pretty : MODE_TYPE.yaml;
  const [localMode, setLocalMode] = useState(defaultMode);

  const [preservedMode, setPreservedMode] = useLocalStorageState(
    preserveKey ?? UNUSED_SYNTAX_HIGHLIGHTER_KEY,
    {
      defaultValue: defaultMode,
    },
  );

  const [mode, setMode] = useMemo(
    () =>
      preserveKey
        ? [preservedMode, setPreservedMode]
        : [localMode, setLocalMode],
    [localMode, preserveKey, preservedMode, setPreservedMode],
  );

  const theme = useCodemirrorTheme();
  const searchPanelTheme = useSearchPanelTheme();

  const code: {
    message: string;
    mode: MODE_TYPE;
    prettified: boolean;
    canBePrettified: boolean;
  } = useMemo(() => {
    const response = prettifyConfig
      ? prettifyMessage(data, {
          type: prettifyConfig.fieldType,
        })
      : {
          message: data,
          prettified: false,
        };

    switch (mode) {
      case MODE_TYPE.yaml:
        return {
          message: yml.dump(data, { lineWidth: -1 }).trim(),
          mode: MODE_TYPE.yaml,
          prettified: false,
          canBePrettified: response.prettified,
        };
      case MODE_TYPE.json:
        return {
          message: JSON.stringify(data, null, 2),
          mode: MODE_TYPE.json,
          prettified: false,
          canBePrettified: response.prettified,
        };
      case MODE_TYPE.pretty:
        return {
          message: response.prettified
            ? (response.message as string)
            : yml.dump(data, { lineWidth: -1 }).trim(),
          mode: response.prettified ? MODE_TYPE.pretty : MODE_TYPE.yaml,
          prettified: response.prettified,
          canBePrettified: response.prettified,
        };

      default:
        return {
          message: yml.dump({}, { lineWidth: -1 }).trim(),
          mode: MODE_TYPE.yaml,
          prettified: false,
          canBePrettified: false,
        };
    }
  }, [mode, data, prettifyConfig]);

  const options = useMemo(() => {
    if (prettifyConfig) {
      return [
        {
          value: MODE_TYPE.pretty,
          label: "Pretty ✨",
          ...(!code.canBePrettified && {
            disabled: !code.canBePrettified,
            tooltip: "Pretty ✨ is not available yet for this format.",
          }),
        },
        ...DEFAULT_OPTIONS,
      ];
    }

    return DEFAULT_OPTIONS;
  }, [prettifyConfig, code.canBePrettified]);

  return (
    <div className="overflow-hidden rounded-md bg-primary-foreground">
      <div className="flex h-10 items-center justify-between border-b border-border pr-2">
        <SelectBox
          value={code.mode}
          onChange={(v) => setMode(v as MODE_TYPE)}
          options={options}
          className="w-fit"
          variant="ghost"
        />
        <CopyButton
          message="Successfully copied code"
          text={code.message}
          tooltipText="Copy code"
        />
      </div>
      <div>
        {code.mode === MODE_TYPE.pretty ? (
          <div className="p-3">
            <MarkdownPreview>{code.message}</MarkdownPreview>
          </div>
        ) : (
          <CodeMirror
            theme={theme}
            value={code.message}
            extensions={[
              EXTENSION_MAP[code.mode] as LRLanguage,
              search({
                top: true,
              }),
              EditorView.lineWrapping,
              EditorState.readOnly.of(true),
              EditorView.editable.of(false),
              EditorView.contentAttributes.of({ tabindex: "0" }),
              searchPanelTheme,
            ]}
            maxHeight="700px"
          />
        )}
      </div>
    </div>
  );
};

export default SyntaxHighlighter;
