import { useEffect, useMemo, useRef } from "react";
import { useForm, useFieldArray } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Plus, X } from "lucide-react";

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
  DialogClose,
  DialogAutoScrollBody,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import TextareaAutosize from "react-textarea-autosize";
import { cn } from "@/lib/utils";
import { TEXT_AREA_CLASSES } from "@/components/ui/textarea";
import { Label } from "@/components/ui/label";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormMessage,
} from "@/components/ui/form";
import {
  MetricType,
  LLMJudgeConfig,
  BehaviorDisplayRow,
} from "@/types/evaluation-suites";

function createSchema(existingNames: string[]) {
  return z.object({
    name: z
      .string()
      .min(1, "Name is required")
      .superRefine((val, ctx) => {
        const trimmed = val.trim().toLowerCase();
        if (existingNames.some((n) => n.toLowerCase() === trimmed)) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: "An evaluator with this name already exists",
          });
        }
      }),
    assertions: z
      .array(z.object({ value: z.string() }))
      .min(1)
      .refine((arr) => arr.some((a) => a.value.trim().length > 0), {
        message: "At least one assertion is required",
      }),
  });
}

type EvaluatorFormType = z.infer<ReturnType<typeof createSchema>>;

const DEFAULT_FORM_VALUES: EvaluatorFormType = {
  name: "",
  assertions: [{ value: "" }],
};

interface AddEditEvaluatorDialogProps {
  open: boolean;
  setOpen: (open: boolean) => void;
  evaluator?: BehaviorDisplayRow;
  onSubmit: (evaluator: Omit<BehaviorDisplayRow, "id">) => void;
  existingNames?: string[];
}

function AddEditEvaluatorDialog({
  open,
  setOpen,
  evaluator,
  onSubmit,
  existingNames = [],
}: AddEditEvaluatorDialogProps) {
  const isEdit = Boolean(evaluator);
  const lastAppendedIndexRef = useRef<number | null>(null);
  const textareaRefsMap = useRef<Map<number, HTMLTextAreaElement>>(new Map());

  const filteredNames = useMemo(
    () =>
      evaluator
        ? existingNames.filter(
            (n) => n.toLowerCase() !== evaluator.name.toLowerCase(),
          )
        : existingNames,
    [existingNames, evaluator],
  );

  const schema = useMemo(() => createSchema(filteredNames), [filteredNames]);

  const form = useForm<EvaluatorFormType>({
    resolver: zodResolver(schema),
    defaultValues: DEFAULT_FORM_VALUES,
    mode: "onChange",
  });

  const { fields, append, remove } = useFieldArray({
    control: form.control,
    name: "assertions",
  });

  useEffect(() => {
    if (!open) return;

    if (evaluator) {
      const assertions = (evaluator.config as LLMJudgeConfig).assertions ?? [];
      form.reset({
        name: evaluator.name,
        assertions:
          assertions.length > 0
            ? assertions.map((a) => ({ value: a }))
            : DEFAULT_FORM_VALUES.assertions,
      });
    } else {
      form.reset(DEFAULT_FORM_VALUES);
    }

    lastAppendedIndexRef.current = null;
    textareaRefsMap.current.clear();
  }, [open, evaluator, form]);

  useEffect(() => {
    if (lastAppendedIndexRef.current !== null) {
      const textarea = textareaRefsMap.current.get(
        lastAppendedIndexRef.current,
      );
      if (textarea) {
        textarea.focus();
        lastAppendedIndexRef.current = null;
      }
    }
  }, [fields.length]);

  const appendAndFocus = () => {
    lastAppendedIndexRef.current = fields.length;
    append({ value: "" });
  };

  const handleFormSubmit = (data: EvaluatorFormType) => {
    const trimmedAssertions = data.assertions
      .map((a) => a.value.trim())
      .filter((v) => v.length > 0);

    onSubmit({
      name: data.name.trim(),
      type: MetricType.LLM_AS_JUDGE,
      config: { assertions: trimmedAssertions },
    });
    setOpen(false);
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <Form {...form}>
          <form
            onSubmit={form.handleSubmit(handleFormSubmit)}
            className="grid gap-4"
          >
            <DialogHeader>
              <DialogTitle>
                {isEdit ? "Edit evaluator" : "Add new evaluator"}
              </DialogTitle>
            </DialogHeader>
            <DialogAutoScrollBody>
              <div className="flex flex-col">
                <FormField
                  control={form.control}
                  name="name"
                  render={({ field }) => (
                    <FormItem className="pb-4">
                      <Label>Name</Label>
                      <FormControl>
                        <Input placeholder="Enter evaluator name" {...field} />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />

                <div className="flex flex-col gap-2 pb-4">
                  <Label>Assertions</Label>
                  {fields.map((fieldItem, index) => (
                    <div key={fieldItem.id} className="flex items-start gap-2">
                      <FormField
                        control={form.control}
                        name={`assertions.${index}.value`}
                        render={({ field }) => (
                          <FormItem className="flex-1">
                            <FormControl>
                              <TextareaAutosize
                                placeholder="e.g. Response should be factually accurate and cite sources"
                                className={cn(
                                  TEXT_AREA_CLASSES,
                                  "min-h-0 resize-none",
                                )}
                                minRows={1}
                                maxRows={6}
                                {...field}
                                ref={(el) => {
                                  field.ref(el);
                                  if (el) {
                                    textareaRefsMap.current.set(index, el);
                                  } else {
                                    textareaRefsMap.current.delete(index);
                                  }
                                }}
                              />
                            </FormControl>
                            <FormMessage />
                          </FormItem>
                        )}
                      />
                      {fields.length > 1 && (
                        <div className="flex pt-1">
                          <Button
                            type="button"
                            variant="minimal"
                            size="icon-xs"
                            onClick={() => remove(index)}
                          >
                            <X />
                          </Button>
                        </div>
                      )}
                    </div>
                  ))}
                  <FormField
                    control={form.control}
                    name="assertions"
                    render={() => (
                      <FormItem>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    className="w-fit"
                    onClick={appendAndFocus}
                  >
                    <Plus className="mr-1 size-4" />
                    Add assertion
                  </Button>
                </div>
              </div>
            </DialogAutoScrollBody>
            <DialogFooter>
              <DialogClose asChild>
                <Button variant="outline">Cancel</Button>
              </DialogClose>
              <Button type="submit" disabled={!form.formState.isValid}>
                {isEdit ? "Save evaluator" : "Add evaluator"}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
}

export default AddEditEvaluatorDialog;
