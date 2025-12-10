import React, { useCallback, useEffect } from "react";
import { z } from "zod";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import { ServerIcon, ServerCrash } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { Description } from "@/components/ui/description";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import {
  useLocalEvaluatorEnabled,
  useSetLocalEvaluatorEnabled,
  useLocalEvaluatorUrl,
  useSetLocalEvaluatorUrl,
} from "@/store/PlaygroundStore";
import useLocalEvaluatorHealthcheck from "@/api/local-evaluator/useLocalEvaluatorHealthcheck";

const formSchema = z.object({
  enabled: z.boolean(),
  url: z.string().url("Invalid URL format").min(1, "URL is required"),
});

type FormType = z.infer<typeof formSchema>;

interface LocalEvaluatorConfigDialogProps {
  open: boolean;
  setOpen: (open: boolean) => void;
}

const LocalEvaluatorConfigDialog: React.FC<LocalEvaluatorConfigDialogProps> = ({
  open,
  setOpen,
}) => {
  const enabled = useLocalEvaluatorEnabled();
  const setEnabled = useSetLocalEvaluatorEnabled();
  const url = useLocalEvaluatorUrl();
  const setUrl = useSetLocalEvaluatorUrl();

  const { data: isHealthy } = useLocalEvaluatorHealthcheck(
    { url },
    { enabled: enabled && !!url },
  );

  const form = useForm<FormType>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      enabled,
      url,
    },
  });

  // Reset form when dialog opens
  useEffect(() => {
    if (open) {
      form.reset({ enabled, url });
    }
  }, [open, enabled, url, form]);

  const handleSubmit = useCallback(
    (data: FormType) => {
      setEnabled(data.enabled);
      setUrl(data.url);
      setOpen(false);
    },
    [setEnabled, setUrl, setOpen],
  );

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            Local Evaluator Settings
            {enabled && (
              isHealthy ? (
                <TooltipWrapper content="Server is online">
                  <ServerIcon className="size-4 text-green-500" />
                </TooltipWrapper>
              ) : (
                <TooltipWrapper content="Server is offline">
                  <ServerCrash className="size-4 text-destructive" />
                </TooltipWrapper>
              )
            )}
          </DialogTitle>
        </DialogHeader>
        <Form {...form}>
          <form
            className="flex flex-col gap-4"
            onSubmit={form.handleSubmit(handleSubmit)}
          >
            <FormField
              control={form.control}
              name="enabled"
              render={({ field }) => (
                <FormItem className="flex flex-row items-center justify-between rounded-lg border p-4">
                  <div className="space-y-0.5">
                    <FormLabel className="text-base">
                      Enable local evaluator
                    </FormLabel>
                    <Description>
                      Run Python metrics from your local eval_app server
                    </Description>
                  </div>
                  <FormControl>
                    <Switch
                      checked={field.value}
                      onCheckedChange={field.onChange}
                    />
                  </FormControl>
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="url"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Server URL</FormLabel>
                  <FormControl>
                    <Input placeholder="http://localhost:5001" {...field} />
                  </FormControl>
                  <Description>
                    Start the server with:{" "}
                    <code className="rounded bg-muted px-1 py-0.5 font-mono text-xs">
                      opik eval-app
                    </code>
                  </Description>
                  <FormMessage />
                </FormItem>
              )}
            />

            <DialogFooter className="pt-4">
              <Button variant="outline" onClick={() => setOpen(false)}>
                Cancel
              </Button>
              <Button type="submit">Save</Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
};

export default LocalEvaluatorConfigDialog;
