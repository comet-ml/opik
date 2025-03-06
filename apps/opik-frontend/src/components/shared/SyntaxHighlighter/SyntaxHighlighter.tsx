import React, { useMemo, useState } from "react";
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

type PrettifyConfig = {
  fieldType: "input" | "output";
};

type SyntaxHighlighterProps = {
  data: object;
  prettifyConfig?: PrettifyConfig;
};

const SyntaxHighlighter: React.FunctionComponent<SyntaxHighlighterProps> = ({
  data,
  prettifyConfig,
}) => {
  const [mode, setMode] = useState(
    prettifyConfig ? MODE_TYPE.pretty : MODE_TYPE.yaml,
  );
  const theme = useCodemirrorTheme();

  const options = useMemo(() => {
    if (prettifyConfig) {
      return [
        {
          value: MODE_TYPE.pretty,
          label: "Pretty ✨",
        },
        ...DEFAULT_OPTIONS,
      ];
    }

    return DEFAULT_OPTIONS;
  }, [prettifyConfig]);

  const code: {
    prettified: boolean;
    message: string;
    mode: MODE_TYPE;
  } = useMemo(() => {
    let response;
    switch (mode) {
      case MODE_TYPE.yaml:
        return {
          message: yml.dump(data, { lineWidth: -1 }).trim(),
          prettified: false,
          mode: MODE_TYPE.yaml,
        };
      case MODE_TYPE.json:
        return {
          message: JSON.stringify(data, null, 2),
          prettified: false,
          mode: MODE_TYPE.json,
        };
      case MODE_TYPE.pretty:
        response = prettifyMessage(data, {
          type: prettifyConfig!.fieldType,
        });

        return {
          message: response.prettified
            ? (response.message as string)
            : yml.dump(data, { lineWidth: -1 }).trim(),
          prettified: response.prettified,
          mode: response.prettified ? MODE_TYPE.pretty : MODE_TYPE.yaml,
        };

      default:
        return {
          message: yml.dump({}, { lineWidth: -1 }).trim(),
          prettified: false,
          mode: MODE_TYPE.yaml,
        };
    }
  }, [mode, data, prettifyConfig]);

  return (
    <div className="overflow-hidden rounded-md bg-primary-foreground">
      <div className="flex h-10 items-center justify-between border-b border-border pr-2">
        <SelectBox
          value={mode}
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
              EditorView.lineWrapping,
              EditorState.readOnly.of(true),
              EditorView.editable.of(false),
            ]}
          />
        )}
      </div>
    </div>
  );
};

export default SyntaxHighlighter;
