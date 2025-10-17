import React from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
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
import { Textarea } from "@/components/ui/textarea";
import useRequestIntegrationMutation from "@/api/feedback/useRequestIntegrationMutation";

const requestIntegrationSchema = z.object({
  integrationRequest: z
    .string()
    .min(5, "Please provide at least 10 characters describing the integration")
    .max(1000, "Description must be 1000 characters or less"),
});

type RequestIntegrationFormData = z.infer<typeof requestIntegrationSchema>;

type RequestIntegrationDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
};

const RequestIntegrationDialog: React.FunctionComponent<
  RequestIntegrationDialogProps
> = ({ open, setOpen }) => {
  const { mutate: requestIntegration, isPending: isRequestingIntegration } =
    useRequestIntegrationMutation();

  const form = useForm<RequestIntegrationFormData>({
    resolver: zodResolver(requestIntegrationSchema),
    defaultValues: {
      integrationRequest: "",
    },
  });

  const onSubmit = (data: RequestIntegrationFormData) => {
    requestIntegration(
      { feedback: data.integrationRequest },
      {
        onSuccess: () => {
          form.reset();
          setOpen(false);
        },
      },
    );
  };

  const handleCancel = () => {
    form.reset();
    setOpen(false);
  };

  const handleOpenChange = (newOpen: boolean) => {
    setOpen(newOpen);
    if (!newOpen) {
      form.reset();
    }
  };

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>Request an integration</DialogTitle>
          <DialogDescription>
            Please let our team know which additional integration you would like
            to see supported in Opik.
          </DialogDescription>
        </DialogHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
            <FormField
              control={form.control}
              name="integrationRequest"
              render={({ field }) => (
                <FormItem>
                  <FormLabel className="sr-only">
                    Integration description
                  </FormLabel>
                  <FormControl>
                    <Textarea
                      {...field}
                      placeholder="Describe the integration you'd like to see..."
                      className="min-h-32 resize-none"
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <DialogFooter className="gap-3 md:gap-0">
              <Button type="button" variant="outline" onClick={handleCancel}>
                Cancel
              </Button>
              <Button
                type="submit"
                disabled={
                  !form.formState.isValid ||
                  form.formState.isSubmitting ||
                  isRequestingIntegration
                }
              >
                Submit request
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
};

export default RequestIntegrationDialog;
