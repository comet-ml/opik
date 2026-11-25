import React, { useEffect, useState } from "react";
import copy from "clipboard-copy";
import { Check, ChevronRight, Copy, FileUp, Sparkles } from "lucide-react";
import { Sheet, SheetContent, SheetDescription, SheetTopBar } from "@/ui/sheet";
import { Button } from "@/ui/button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/ui/tabs";
import { Textarea } from "@/ui/textarea";
import { cn } from "@/lib/utils";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import UploadField from "@/shared/UploadField/UploadField";
import { Alert, AlertDescription } from "@/ui/alert";
import {
  BuiltInPrice,
  ModelPriceList,
  ModelPriceReplace,
  ModelPriceReplaceResult,
  builtInLookup,
  describeBatchProblem,
  describeReplaceResult,
  parsePriceUpload,
  problemsForRows,
  summarizeBatch,
  upsertMany,
} from "@/lib/model-prices";
import useSaveModelPrices from "./useSaveModelPrices";

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
  uploaded: number;
  warnings: string[];
};

const CopyTextButton: React.FC<{ text: string }> = ({ text }) => {
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!copied) return;
    const timer = setTimeout(() => setCopied(false), 2000);
    return () => clearTimeout(timer);
  }, [copied]);

  return (
    <Button
      variant="outline"
      size="2xs"
      className="shrink-0 bg-background"
      onClick={() => copy(text).then(() => setCopied(true))}
    >
      {copied ? (
        <Check className="mr-1 size-3" />
      ) : (
        <Copy className="mr-1 size-3" />
      )}
      {copied ? "Copied" : "Copy"}
    </Button>
  );
};

const CodeLine: React.FC<{ text: string }> = ({ text }) => (
  <div className="flex items-center gap-2 rounded-md bg-muted py-1.5 pl-3 pr-1.5">
    <code className="comet-code min-w-0 flex-1 truncate" title={text}>
      {text}
    </code>
    <CopyTextButton text={text} />
  </div>
);

const Disclosure: React.FC<{
  label: string;
  open: boolean;
  onToggle: () => void;
}> = ({ label, open, onToggle }) => (
  <button
    type="button"
    className="comet-body-xs inline-flex items-center gap-1 text-muted-slate hover:text-foreground"
    aria-expanded={open}
    onClick={onToggle}
  >
    <ChevronRight
      className={cn("size-3.5 transition-transform", open && "rotate-90")}
    />
    {label}
  </button>
);

const Step: React.FC<{
  number: number;
  title: string;
  children: React.ReactNode;
}> = ({ number, title, children }) => (
  <li className="space-y-2">
    <p className="comet-body-s-accented flex items-center gap-2">
      <span className="comet-body-xs-accented flex size-5 shrink-0 items-center justify-center rounded-full bg-foreground text-background">
        {number}
      </span>
      {title}
    </p>
    <div className="pl-7">{children}</div>
  </li>
);

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
  const [isFormatOpen, setIsFormatOpen] = useState(false);
  const [isSkillOpen, setIsSkillOpen] = useState(false);
  const [isDiscardOpen, setIsDiscardOpen] = useState(false);
  const [tab, setTab] = useState("upload");

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
    const { request, positions } = upsertMany(list, upload.rows);
    const outcome = await send(request, true);
    if (outcome.kind === "previewed") {
      setPreview({
        request,
        result: outcome.result,
        uploaded: positions.length,
        warnings: problemsForRows(outcome.result.warnings, positions).map(
          (warning) => describeBatchProblem(warning, request.rows),
        ),
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
            <TabsList variant="segmented" className="w-fit">
              <TabsTrigger value="upload" variant="segmented">
                <FileUp className="mr-1.5 size-3.5" />
                Upload or paste JSON or CSV
              </TabsTrigger>
              <TabsTrigger value="skill" variant="segmented">
                <Sparkles className="mr-1.5 size-3.5" />
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
              <Textarea
                className="min-h-[200px] font-mono placeholder:text-light-slate"
                placeholder={EXAMPLE}
                spellCheck={false}
                value={text}
                onChange={(event) => changeText(event.target.value)}
              />
              <Disclosure
                label="File format"
                open={isFormatOpen}
                onToggle={() => setIsFormatOpen((value) => !value)}
              />
              {isFormatOpen && (
                <p className="comet-body-xs text-muted-slate">
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
                  <code>gpt-4o on openai</code>. Other columns are ignored.
                </p>
              )}
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
                <div
                  className="comet-body-s space-y-2 rounded-md border p-3"
                  data-testid="batch-preview"
                >
                  <p className="comet-body-s-accented">{summary}</p>
                  <ul className="list-disc pl-5 text-muted-slate">
                    {described.created.map((line, i) => (
                      <li key={`c${i}`}>Added {line}</li>
                    ))}
                    {described.updated.map((line, i) => (
                      <li key={`u${i}`}>Changed {line}</li>
                    ))}
                  </ul>
                  {preview.warnings.length > 0 && (
                    <div
                      className="rounded-md border border-warning-box-text/40 bg-warning-box-bg p-3"
                      data-testid="batch-preview-warnings"
                    >
                      <p className="comet-body-s-accented">
                        Check these before saving
                      </p>
                      <ul className="list-disc pl-5">
                        {preview.warnings.map((warning, i) => (
                          <li key={i}>{warning}</li>
                        ))}
                      </ul>
                    </div>
                  )}
                </div>
              )}
            </TabsContent>
            <TabsContent value="skill" className="mt-4 space-y-4">
              <p className="comet-body-xs text-muted-slate">
                Upload a price sheet in any format—Excel, PDF, or an email
                table—directly from Claude Code, Codex or Cursor. Review and
                confirm changes in your agent.
              </p>
              <ol className="space-y-4">
                <Step number={1} title="Install the Opik skills once">
                  <CodeLine text={INSTALL} />
                </Step>
                <Step number={2} title="Ask Claude Code, Codex or Cursor">
                  <CodeLine text={ASK} />
                </Step>
                <Step number={3} title="The skill then">
                  <ul className="comet-body-xs list-disc space-y-1 pl-5 text-foreground">
                    {SKILL_STEPS.map((step) => (
                      <li key={step}>{step}</li>
                    ))}
                  </ul>
                </Step>
              </ol>
              <div className="space-y-2">
                <Disclosure
                  label="Skill instructions"
                  open={isSkillOpen}
                  onToggle={() => setIsSkillOpen((value) => !value)}
                />
                {isSkillOpen && (
                  <p className="comet-body-xs pl-5 text-muted-slate">
                    The skill uses the same organization price list as this
                    page. It reads the list and its version, sends your changes
                    as a dry run to show what would change, and saves with that
                    version only after you confirm. If someone saved in between,
                    the save is refused and the skill reads the list again.
                  </p>
                )}
              </div>
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
