import React, { useState } from "react";
import { EditorView } from "@codemirror/view";
import { jsonLanguage } from "@codemirror/lang-json";
import { Sheet, SheetContent, SheetDescription, SheetTopBar } from "@/ui/sheet";
import { Button } from "@/ui/button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/ui/tabs";
import CodeMirror from "@/shared/CodeMirrorEditor/CodeMirrorEditor";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import CodeSnippet from "@/shared/CodeSnippet/CodeSnippet";
import TimelineStep from "@/shared/TimelineStep/TimelineStep";
import { INSTALL_OPIK_SKILLS_COMMAND } from "@/constants/shared";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import UploadField from "@/shared/UploadField/UploadField";
import { Alert, AlertDescription, AlertTitle } from "@/ui/alert";
import {
  BuiltInPrice,
  ModelPriceList,
  ModelPriceProblem,
  ModelPriceReplace,
  ModelPriceReplaceResult,
  builtInLookup,
  describeBatchProblem,
  describeReplaceResult,
  describeRowLimit,
  isInformational,
  parsePriceUpload,
  problemsForRows,
  summarizeBatch,
  upsertMany,
} from "@/lib/model-prices";
import useSaveModelPrices from "./useSaveModelPrices";
import ExpandableHint from "./ExpandableHint";
import WarningCallout from "./WarningCallout";

type BatchUpdateDrawerProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  list: ModelPriceList;
  builtIns: BuiltInPrice[];
};

const EXAMPLE = `{
  "gpt-5": {
    "litellm_provider": "openai",
    "input_cost_per_token": 0.000001,
    "output_cost_per_token": 0.00001,
    "cache_read_input_token_cost": 0.000000125
  },
  "acme-finetune-v3": {
    "litellm_provider": "acme",
    "input_cost_per_token": 0.000002,
    "output_cost_per_token": 0.000008
  }
}`;

const ASK =
  "Upload the model prices in ./acme-llm-contract.xlsx to my Opik organization. Include custom price fields. Show me what will change and any errors, then ask me to confirm before saving.";

const SKILL_STEPS = [
  "maps your columns to model, provider, prices in their original units, and notes;",
  "runs the same checks as this page and previews updates, new models, errors, and skipped rows in your agent;",
  "saves only after you confirm, using the list version so it cannot overwrite a colleague's edit;",
  "verifies the saved prices and reports updated, added, and skipped counts, or explains any failure;",
  "Preview, confirmation, and results stay in your agent.",
];

type Preview = {
  request: ModelPriceReplace;
  result: ModelPriceReplaceResult;
  uploaded: number;
  warnings: string[];
  notes: string[];
};

const BatchUpdateDrawer: React.FC<BatchUpdateDrawerProps> = ({
  open,
  setOpen,
  list,
  builtIns,
}) => {
  const { send, isSaving } = useSaveModelPrices();
  const [text, setText] = useState("");
  const [parseErrors, setParseErrors] = useState<string[]>([]);
  const [serverErrors, setServerErrors] = useState<string[]>([]);
  const [preview, setPreview] = useState<Preview>();
  const [isDiscardOpen, setIsDiscardOpen] = useState(false);
  const [tab, setTab] = useState("upload");
  const theme = useCodemirrorTheme({ editable: true });

  const requestClose = () => {
    if (text.trim()) {
      setIsDiscardOpen(true);
      return;
    }
    setOpen(false);
  };

  const changeText = (value: string) => {
    setText(value);
    setParseErrors([]);
    setServerErrors([]);
    setPreview(undefined);
  };

  const onFile = (file: File | undefined) => {
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => changeText(String(reader.result ?? ""));
    reader.readAsText(file);
  };

  const previewChanges = async () => {
    const upload = parsePriceUpload(text);
    if (upload.errors.length) {
      setParseErrors(upload.errors);
      return;
    }
    const { request, positions, duplicates } = upsertMany(list, upload.rows);
    const rowLimit = describeRowLimit(list, request);
    if (duplicates.length || rowLimit) {
      setParseErrors(rowLimit ? [...duplicates, rowLimit] : duplicates);
      return;
    }
    const outcome = await send(request, true);
    if (outcome.kind === "previewed") {
      const warnings = problemsForRows(outcome.result.warnings, positions);
      const describeProblem = (problem: ModelPriceProblem) =>
        describeBatchProblem(problem, request.rows);
      setPreview({
        request,
        result: outcome.result,
        uploaded: positions.length,
        warnings: warnings
          .filter((warning) => !isInformational(warning))
          .map(describeProblem),
        notes: [
          ...(upload.notes ?? []),
          ...warnings.filter(isInformational).map(describeProblem),
        ],
      });
    } else if (outcome.kind === "invalid") {
      setServerErrors(
        outcome.errors.map((problem) =>
          describeBatchProblem(problem, request.rows),
        ),
      );
    }
  };

  const save = async () => {
    if (!preview) return;
    const outcome = await send(preview.request, false);
    if (outcome.kind === "saved") {
      setOpen(false);
    } else if (outcome.kind === "invalid") {
      setServerErrors(
        outcome.errors.map((problem) =>
          describeBatchProblem(problem, preview.request.rows),
        ),
      );
      setPreview(undefined);
    } else {
      setPreview(undefined);
    }
  };

  const described = preview
    ? describeReplaceResult(preview.result, builtInLookup(builtIns))
    : undefined;
  const changeCount = described
    ? described.created.length + described.updated.length
    : 0;
  const unchangedCount = preview
    ? Math.max(0, preview.uploaded - changeCount)
    : 0;
  const summary = described
    ? summarizeBatch({
        added: described.created.length,
        changed: described.updated.length,
        unchanged: unchangedCount,
      })
    : "";

  return (
    <Sheet
      open={open}
      onOpenChange={(next) => (next ? setOpen(true) : requestClose())}
    >
      <SheetContent
        side="right"
        className="flex w-full max-w-none flex-col p-0 sm:max-w-[720px]"
        header={<SheetTopBar variant="form" title="Batch update prices" />}
      >
        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto px-6 pb-6">
          <SheetDescription className="comet-body-s text-muted-slate">
            Update many models at once. Models not in the update keep their
            current prices.
          </SheetDescription>
          <Tabs value={tab} onValueChange={setTab}>
            <TabsList variant="segmented-primary" className="w-fit">
              <TabsTrigger value="upload" variant="segmented-primary">
                Upload or paste JSON or CSV
              </TabsTrigger>
              <TabsTrigger value="skill" variant="segmented-primary">
                Use the Opik skill
              </TabsTrigger>
            </TabsList>
            <TabsContent value="upload" className="mt-4 space-y-3">
              <UploadField
                onFileSelect={onFile}
                accept=".json,application/json,.csv,text/csv"
                description="Drop a .json or .csv file or"
              />
              <p className="comet-body-xs text-muted-slate">
                The file is read once in your browser and not stored.
              </p>
              <div className="comet-body-xs flex items-center gap-3 text-muted-slate">
                <span className="h-px flex-1 bg-border" />
                or paste
                <span className="h-px flex-1 bg-border" />
              </div>
              <div className="max-h-80 overflow-y-auto rounded-md border">
                <CodeMirror
                  theme={theme}
                  value={text}
                  onChange={changeText}
                  minHeight="200px"
                  extensions={[
                    EditorView.lineWrapping,
                    ...(text.trimStart().startsWith("{") ? [jsonLanguage] : []),
                  ]}
                  placeholder={EXAMPLE}
                  basicSetup={{
                    lineNumbers: false,
                    foldGutter: false,
                    highlightActiveLine: false,
                    highlightSelectionMatches: false,
                  }}
                />
              </div>
              <ExpandableHint label="File format">
                <p>
                  An object keyed by model name, in LiteLLM&apos;s format.
                  Prices are dollars per token:{" "}
                  <code>input_cost_per_token</code>,{" "}
                  <code>output_cost_per_token</code>,{" "}
                  <code>cache_read_input_token_cost</code> and{" "}
                  <code>cache_creation_input_token_cost</code>. Add{" "}
                  <code>litellm_provider</code> for the provider (leave it out
                  for any provider) and an optional <code>note</code>. A model
                  that already has an organization price is updated; others are
                  added.
                  <br />
                  <br />
                  Or a CSV with a <code>model</code> column, like the one Export
                  as CSV writes: <code>provider</code> (leave it empty for any
                  provider), prices in dollars per 1M tokens in{" "}
                  <code>input_price_per_1m</code>,{" "}
                  <code>output_price_per_1m</code>,{" "}
                  <code>cache_read_price_per_1m</code> and{" "}
                  <code>cache_write_price_per_1m</code>, and{" "}
                  <code>priced_like</code> for an alias, like{" "}
                  <code>gpt-4o on openai</code>, and an optional{" "}
                  <code>note</code>. Rows whose <code>type</code> is Default are
                  skipped, because they use the built-in price. Other columns
                  are ignored.
                </p>
              </ExpandableHint>
              {parseErrors.length > 0 && (
                <Alert variant="destructive" size="sm">
                  <AlertDescription size="sm">
                    <ul className="space-y-1">
                      {parseErrors.map((error, i) => (
                        <li key={i}>{error}</li>
                      ))}
                    </ul>
                  </AlertDescription>
                </Alert>
              )}
              {serverErrors.length > 0 && (
                <Alert variant="destructive" size="sm">
                  <AlertDescription size="sm">
                    <ul className="space-y-1">
                      {serverErrors.map((error, i) => (
                        <li key={i}>{error}</li>
                      ))}
                    </ul>
                  </AlertDescription>
                </Alert>
              )}
              {described && preview && (
                <Alert variant="callout" size="sm" data-testid="batch-preview">
                  <AlertTitle size="sm">{summary}</AlertTitle>
                  <AlertDescription size="sm">
                    <ul className="list-disc space-y-1 pl-5">
                      {described.created.map((line, i) => (
                        <li key={`c${i}`}>Added {line}</li>
                      ))}
                      {described.updated.map((line, i) => (
                        <li key={`u${i}`}>Changed {line}</li>
                      ))}
                      {preview.notes.map((note, i) => (
                        <li key={`n${i}`}>{note}</li>
                      ))}
                    </ul>
                  </AlertDescription>
                </Alert>
              )}
              {preview && preview.warnings.length > 0 && (
                <WarningCallout data-testid="batch-preview-warnings">
                  <p className="comet-body-xs-accented">
                    Check these before saving
                  </p>
                  <ul className="list-disc space-y-1 pl-5">
                    {preview.warnings.map((warning, i) => (
                      <li key={i}>{warning}</li>
                    ))}
                  </ul>
                </WarningCallout>
              )}
            </TabsContent>
            <TabsContent value="skill" className="mt-4 space-y-4">
              <p className="comet-body-xs text-muted-slate">
                Upload a price sheet in any format—Excel, PDF, or an email
                table—directly from Claude Code, Codex or Cursor. Review and
                confirm changes in your agent.
              </p>
              <div className="flex flex-col">
                <TimelineStep number={1}>
                  <div className="flex flex-col gap-2.5">
                    <h4 className="comet-body-s-accented">
                      Install the Opik skills once
                    </h4>
                    <CodeSnippet
                      title="Terminal"
                      code={INSTALL_OPIK_SKILLS_COMMAND}
                    />
                  </div>
                </TimelineStep>
                <TimelineStep number={2}>
                  <div className="flex flex-col gap-2.5">
                    <h4 className="comet-body-s-accented">
                      Ask Claude Code, Codex or Cursor
                    </h4>
                    <CodeSnippet title="Prompt" code={ASK} />
                  </div>
                </TimelineStep>
                <TimelineStep number={3} isLast>
                  <div className="flex flex-col gap-2.5">
                    <h4 className="comet-body-s-accented">The skill then</h4>
                    <ul className="comet-body-xs list-disc space-y-1 pl-5">
                      {SKILL_STEPS.map((step) => (
                        <li key={step}>{step}</li>
                      ))}
                    </ul>
                  </div>
                </TimelineStep>
              </div>
              <ExpandableHint label="Skill instructions">
                <p>
                  The skill uses the same organization price list as this page.
                  It reads the list and its version, sends your changes as a dry
                  run to show what would change, and saves with that version
                  only after you confirm. If someone saved in between, the save
                  is refused and the skill reads the list again.
                </p>
              </ExpandableHint>
            </TabsContent>
          </Tabs>
        </div>
        <div className="flex items-center justify-end gap-2 border-t px-6 py-3">
          <Button size="sm" variant="outline" onClick={requestClose}>
            Cancel
          </Button>
          {tab === "upload" &&
            (preview ? (
              <Button
                size="sm"
                onClick={save}
                disabled={isSaving || !changeCount}
              >
                {changeCount
                  ? `Save ${changeCount} ${
                      changeCount === 1 ? "change" : "changes"
                    }`
                  : "Nothing to save"}
              </Button>
            ) : (
              <Button
                size="sm"
                onClick={previewChanges}
                disabled={isSaving || !text.trim()}
              >
                Preview changes
              </Button>
            ))}
        </div>
      </SheetContent>
      <ConfirmDialog
        open={isDiscardOpen}
        setOpen={setIsDiscardOpen}
        onConfirm={() => setOpen(false)}
        title="Discard this update?"
        description="The prices you pasted or uploaded here aren't saved yet."
        confirmText="Discard"
        cancelText="Keep editing"
        confirmButtonVariant="destructive"
      />
    </Sheet>
  );
};

export default BatchUpdateDrawer;
