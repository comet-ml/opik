import React, { useMemo, useState } from "react";
import useWorkflowEnableMutation from "@/api/automations/useWorkflowEnableMutation";
import CodeHighlighter from "@/shared/CodeHighlighter/CodeHighlighter";
import { SelectBox } from "@/shared/SelectBox/SelectBox";
import { SUPPORTED_LANGUAGE } from "@/constants/codeLanguage";
import { Button } from "@/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { useToast } from "@/ui/use-toast";
import {
  Workflow,
  WorkflowEnabled,
  WorkflowOverrides,
} from "@/types/event-automations";
import {
  initialOverrideForm,
  isNumberOverride,
  OVERRIDE_LABELS,
  OverrideForm,
  OverrideKey,
  overrideFieldsFor,
  overrideFormErrors,
  toOverrides,
} from "./helpers";

type WorkflowEnableDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  projectId: string;
  recipe: string;
  workflow?: Workflow;
  // What a recommendation suggests; the judge score is still the user's to choose.
  suggested?: WorkflowOverrides;
};

const GithubAction: React.FC<{ yaml: string }> = ({ yaml }) => (
  <div>
    <div className="comet-body-s-accented mb-1">
      Add this step to a GitHub workflow
    </div>
    <p className="comet-body-xs mb-2 text-muted-slate">
      It tells Opik a CI job ran. Fill in the OPIK_API_KEY, OPIK_WORKSPACE and
      OPIK_URL placeholders from your repository secrets.
    </p>
    <CodeHighlighter data={yaml} language={SUPPORTED_LANGUAGE.yaml} />
  </div>
);

const WorkflowEnableDialog: React.FC<WorkflowEnableDialogProps> = ({
  open,
  setOpen,
  projectId,
  recipe,
  workflow,
  suggested,
}) => {
  const fields = useMemo(
    () => overrideFieldsFor(recipe, workflow?.overrides),
    [recipe, workflow?.overrides],
  );
  const [form, setForm] = useState<OverrideForm>(() =>
    initialOverrideForm(fields, workflow?.overrides, suggested),
  );
  const [touched, setTouched] = useState(false);
  const [preview, setPreview] = useState<WorkflowEnabled | null>(null);
  const [done, setDone] = useState<WorkflowEnabled | null>(null);
  const enable = useWorkflowEnableMutation();
  const { toast } = useToast();

  const name = workflow?.name ?? recipe;
  const scoreNames = workflow?.available_score_names ?? [];
  const errors = useMemo(
    () => overrideFormErrors(recipe, form),
    [recipe, form],
  );
  const hasErrors = Object.keys(errors).length > 0;

  const request = () => ({
    recipe,
    project_id: projectId,
    overrides: toOverrides(form),
  });

  const handlePreview = () => {
    setTouched(true);
    if (hasErrors) return;
    enable.mutate(
      { request: request(), dryRun: true },
      { onSuccess: setPreview },
    );
  };

  const handleEnable = () => {
    enable.mutate(
      { request: request() },
      {
        onSuccess: (result) => {
          const count = result.created.length;
          toast({
            description: `${name} enabled: ${count} automation${
              count === 1 ? "" : "s"
            } created.`,
          });
          if (result.setup?.github_action) {
            setDone(result);
          } else {
            setOpen(false);
          }
        },
      },
    );
  };

  const setField = (key: OverrideKey, value: string) =>
    setForm((previous) => ({ ...previous, [key]: value }));

  const renderField = (key: OverrideKey) => {
    const id = `workflow-override-${key}`;
    const error = touched ? errors[key] : undefined;
    let control: React.ReactNode;
    if (key === "score_name") {
      control =
        scoreNames.length === 0 ? (
          <p className="comet-body-xs text-muted-slate">
            No scores were seen in this project in the last 14 days. Run a judge
            first, then come back.
          </p>
        ) : (
          <SelectBox
            id={id}
            value={form.score_name ?? ""}
            onChange={(value) => setField("score_name", value)}
            options={scoreNames.map((score) => ({
              value: score,
              label: score,
            }))}
            placeholder="Choose the judge score"
          />
        );
    } else {
      control = (
        <Input
          id={id}
          dimension="sm"
          type={isNumberOverride(key) ? "number" : "text"}
          value={form[key] ?? ""}
          onChange={(event) => setField(key, event.target.value)}
        />
      );
    }
    return (
      <div key={key} className="flex flex-col gap-1">
        <Label htmlFor={id}>{OVERRIDE_LABELS[key]}</Label>
        {control}
        {error && <p className="comet-body-xs text-destructive">{error}</p>}
      </div>
    );
  };

  const renderPreview = (result: WorkflowEnabled) => {
    const estimate = result.estimate;
    const replayed =
      !!estimate &&
      (estimate.fired > 0 || estimate.matched > 0 || estimate.items > 0);
    return (
      <div className="flex flex-col gap-3">
        <div>
          <div className="comet-body-s-accented mb-1">Would create</div>
          {result.would_create.length > 0 ? (
            <ul className="comet-body-s list-disc pl-5">
              {result.would_create.map((automationName) => (
                <li key={automationName}>{automationName}</li>
              ))}
            </ul>
          ) : (
            <p className="comet-body-s text-muted-slate">
              Nothing new: every automation of this recipe already exists.
            </p>
          )}
        </div>
        {estimate && (
          <p className="comet-body-s text-muted-slate">
            {replayed
              ? `Over the last ${
                  estimate.window_days
                } days this would have fired ${estimate.fired} times, matched ${
                  estimate.matched
                } and written ${estimate.items} items${
                  estimate.cost_usd > 0 ? ` for $${estimate.cost_usd}` : ""
                }.`
              : `Nothing in the last ${estimate.window_days} days would have fired it.`}
          </p>
        )}
        {result.warnings.length > 0 && (
          <ul className="comet-body-s list-disc pl-5 text-[var(--tag-orange-text)]">
            {result.warnings.map((warning) => (
              <li key={warning}>{warning}</li>
            ))}
          </ul>
        )}
        {result.setup?.github_action && (
          <GithubAction yaml={result.setup.github_action} />
        )}
      </div>
    );
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-screen-sm">
        <DialogHeader>
          <DialogTitle>Enable {name}</DialogTitle>
          <DialogDescription>
            {workflow?.description ??
              "Creates this recipe's automations for the project."}
          </DialogDescription>
        </DialogHeader>
        <div className="max-h-[65vh] overflow-y-auto">
          {done ? (
            <div className="flex flex-col gap-3">
              <p className="comet-body-s">
                {name} is on. One step is left for you:
              </p>
              <GithubAction yaml={done.setup!.github_action!} />
            </div>
          ) : preview ? (
            renderPreview(preview)
          ) : (
            <div className="flex flex-col gap-3">{fields.map(renderField)}</div>
          )}
        </div>
        <DialogFooter>
          {done ? (
            <DialogClose asChild>
              <Button>Close</Button>
            </DialogClose>
          ) : preview ? (
            <>
              <Button
                variant="outline"
                disabled={enable.isPending}
                onClick={() => setPreview(null)}
              >
                Back
              </Button>
              <Button disabled={enable.isPending} onClick={handleEnable}>
                Enable
              </Button>
            </>
          ) : (
            <>
              <DialogClose asChild>
                <Button variant="outline">Cancel</Button>
              </DialogClose>
              <Button
                disabled={enable.isPending || (touched && hasErrors)}
                onClick={handlePreview}
              >
                Preview
              </Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default WorkflowEnableDialog;
