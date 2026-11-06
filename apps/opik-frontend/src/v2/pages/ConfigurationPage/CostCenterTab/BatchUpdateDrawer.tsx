import React, { useState } from "react";
import { FileJson, Sparkles } from "lucide-react";
import { Sheet, SheetContent, SheetTopBar } from "@/ui/sheet";
import { Button } from "@/ui/button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/ui/tabs";
import { Textarea } from "@/ui/textarea";
import UploadField from "@/shared/UploadField/UploadField";
import CopyButton from "@/shared/CopyButton/CopyButton";
import {
  ModelPriceList,
  ModelPriceProblem,
  ModelPriceReplace,
  ModelPriceReplaceResult,
  describeReplaceResult,
  parseLiteLlmUpload,
  upsertMany,
} from "@/lib/model-prices";
import useSaveModelPrices from "./useSaveModelPrices";

type BatchUpdateDrawerProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  list: ModelPriceList;
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

const INSTALL = "npx skills add comet-ml/opik-skills -g --all";
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
};

const CodeLine: React.FC<{ text: string }> = ({ text }) => (
  <div className="flex items-center gap-2 rounded-md border bg-muted px-3 py-2">
    <code className="comet-code min-w-0 flex-1 break-words">{text}</code>
    <CopyButton text={text} variant="outline" />
  </div>
);

const BatchUpdateDrawer: React.FC<BatchUpdateDrawerProps> = ({
  open,
  setOpen,
  list,
}) => {
  const { send, isSaving } = useSaveModelPrices();
  const [text, setText] = useState("");
  const [parseErrors, setParseErrors] = useState<string[]>([]);
  const [serverErrors, setServerErrors] = useState<ModelPriceProblem[]>([]);
  const [preview, setPreview] = useState<Preview>();

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
    const upload = parseLiteLlmUpload(text);
    if (upload.errors.length) {
      setParseErrors(upload.errors);
      return;
    }
    const request = upsertMany(list, upload.rows);
    const outcome = await send(request, true);
    if (outcome.kind === "previewed") {
      setPreview({ request, result: outcome.result });
    } else if (outcome.kind === "invalid") {
      setServerErrors(outcome.errors);
    }
  };

  const save = async () => {
    if (!preview) return;
    const outcome = await send(preview.request, false);
    if (outcome.kind === "saved") {
      setOpen(false);
    } else if (outcome.kind === "invalid") {
      setServerErrors(outcome.errors);
      setPreview(undefined);
    } else {
      setPreview(undefined);
    }
  };

  const described = preview ? describeReplaceResult(preview.result) : undefined;
  const changeCount = described
    ? described.created.length + described.updated.length
    : 0;

  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetContent
        side="right"
        className="flex w-full max-w-none flex-col p-0 sm:max-w-[720px]"
        header={<SheetTopBar variant="form" />}
        blockOverlayClose
      >
        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto px-6 py-4">
          <div>
            <h3 className="comet-title-xs">Batch update prices</h3>
            <p className="comet-body-s text-muted-slate">
              Update many models at once. Models not in the update keep their
              current prices.
            </p>
          </div>
          <Tabs defaultValue="json">
            <TabsList variant="segmented">
              <TabsTrigger value="json" variant="segmented">
                <FileJson className="mr-1.5 size-3.5" />
                Upload or paste JSON
              </TabsTrigger>
              <TabsTrigger value="skill" variant="segmented">
                <Sparkles className="mr-1.5 size-3.5" />
                Use the Opik skill
              </TabsTrigger>
            </TabsList>
            <TabsContent value="json" className="space-y-3">
              <UploadField
                accept=".json,application/json"
                description="Drop a .json file or click to choose. The file is read once in your browser and not stored."
                onFileSelect={onFile}
              />
              <p className="comet-body-xs text-center text-light-slate">
                or paste
              </p>
              <Textarea
                className="min-h-[200px] font-mono"
                placeholder={EXAMPLE}
                value={text}
                onChange={(event) => changeText(event.target.value)}
              />
              <details className="comet-body-s rounded-md border p-3">
                <summary className="cursor-pointer">File format</summary>
                <p className="mt-2 text-muted-slate">
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
                </p>
              </details>
              {parseErrors.length > 0 && (
                <ul className="comet-body-s space-y-1 rounded-md border border-destructive/40 p-3 text-destructive">
                  {parseErrors.map((error, i) => (
                    <li key={i}>{error}</li>
                  ))}
                </ul>
              )}
              {serverErrors.length > 0 && (
                <ul className="comet-body-s space-y-1 rounded-md border border-destructive/40 p-3 text-destructive">
                  {serverErrors.map((problem, i) => (
                    <li key={i}>{problem.message}</li>
                  ))}
                </ul>
              )}
              {described && (
                <div
                  className="comet-body-s space-y-2 rounded-md border p-3"
                  data-testid="batch-preview"
                >
                  <p className="comet-body-s-accented">
                    {changeCount
                      ? `${described.created.length} added, ${described.updated.length} changed`
                      : "Nothing changes"}
                  </p>
                  <ul className="list-disc pl-5 text-muted-slate">
                    {described.created.map((line, i) => (
                      <li key={`c${i}`}>Added {line}</li>
                    ))}
                    {described.updated.map((line, i) => (
                      <li key={`u${i}`}>Changed {line}</li>
                    ))}
                  </ul>
                  {preview!.result.warnings.length > 0 && (
                    <div className="rounded-md bg-warning-box-bg p-2">
                      {preview!.result.warnings.map((warning, i) => (
                        <p key={i}>{warning.message}</p>
                      ))}
                    </div>
                  )}
                </div>
              )}
              <div className="flex justify-end gap-2">
                {preview ? (
                  <Button
                    size="sm"
                    onClick={save}
                    disabled={isSaving || !changeCount}
                  >
                    Save {changeCount}{" "}
                    {changeCount === 1 ? "change" : "changes"}
                  </Button>
                ) : (
                  <Button
                    size="sm"
                    onClick={previewChanges}
                    disabled={isSaving || !text.trim()}
                  >
                    Preview changes
                  </Button>
                )}
              </div>
            </TabsContent>
            <TabsContent value="skill" className="space-y-4">
              <p className="comet-body-s text-muted-slate">
                Upload a price sheet in any format—Excel, PDF, or an email
                table—directly from Claude Code, Codex or Cursor. Review and
                confirm changes in your agent.
              </p>
              <ol className="space-y-4">
                <li className="space-y-2">
                  <p className="comet-body-s-accented">
                    1. Install the Opik skills once
                  </p>
                  <CodeLine text={INSTALL} />
                </li>
                <li className="space-y-2">
                  <p className="comet-body-s-accented">
                    2. Ask Claude Code, Codex or Cursor
                  </p>
                  <CodeLine text={ASK} />
                </li>
                <li className="space-y-2">
                  <p className="comet-body-s-accented">3. The skill then</p>
                  <ul className="comet-body-s list-disc space-y-1 pl-5 text-muted-slate">
                    {SKILL_STEPS.map((step) => (
                      <li key={step}>{step}</li>
                    ))}
                  </ul>
                </li>
              </ol>
            </TabsContent>
          </Tabs>
        </div>
      </SheetContent>
    </Sheet>
  );
};

export default BatchUpdateDrawer;
