import React, { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Loader2, Users } from "lucide-react";
import { toast } from "@/components/ui/use-toast";
import { Span, Trace } from "@/types/traces";
import useAnnotationQueuesList from "@/api/annotationQueues/useAnnotationQueuesList";
import useAddItemsToQueueMutation from "@/api/annotationQueues/useAddItemsToQueueMutation";
import { useWorkspaceName } from "@/hooks/useWorkspaceName";

type AddToQueueDialogProps = {
  rows: Array<Trace | Span>;
  open: boolean;
  setOpen: (open: boolean | number) => void;
  onSuccess?: () => void;
};

const addToQueueFormSchema = z.object({
  queueId: z.string().min(1, "Please select a queue"),
});

type AddToQueueFormValues = z.infer<typeof addToQueueFormSchema>;

const AddToQueueDialog: React.FC<AddToQueueDialogProps> = ({
  rows,
  open,
  setOpen,
  onSuccess,
}) => {
  const workspaceName = useWorkspaceName();
  const [isSubmitting, setIsSubmitting] = useState(false);

  const form = useForm<AddToQueueFormValues>({
    resolver: zodResolver(addToQueueFormSchema),
    defaultValues: {
      queueId: "",
    },
  });

  // Get active annotation queues
  const { data: queues, isLoading: queuesLoading } = useAnnotationQueuesList({
    workspaceName,
    projectId: undefined, // Get all workspace queues
    page: 1,
    size: 100,
  });

  const addItemsToQueue = useAddItemsToQueueMutation();

  const handleClose = () => {
    setOpen(false);
    form.reset();
  };

  const onSubmit = async (values: AddToQueueFormValues) => {
    if (!rows.length) return;

    setIsSubmitting(true);
    try {
      // Convert traces/spans to queue items
      const items = rows.map((row) => ({
        item_type: "trace" as const, // Assuming all are traces for now
        item_id: row.id,
      }));

      await addItemsToQueue.mutateAsync({
        queueId: values.queueId,
        items,
      });

      toast({
        title: "Items Added to Queue",
        description: `Successfully added ${rows.length} item${
          rows.length > 1 ? "s" : ""
        } to the annotation queue.`,
      });

      handleClose();
      onSuccess?.();
    } catch (error) {
      toast({
        title: "Failed to Add Items",
        description: "There was an error adding items to the queue. Please try again.",
        variant: "destructive",
      });
    } finally {
      setIsSubmitting(false);
    }
  };

  const activeQueues = queues?.data?.filter((queue) => queue.status === "active") || [];

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Users className="size-4" />
            Add to Annotation Queue
          </DialogTitle>
        </DialogHeader>

        <div className="space-y-4">
          <div className="text-sm text-muted-foreground">
            Add {rows.length} selected item{rows.length > 1 ? "s" : ""} to an annotation queue for SME review.
          </div>

          <Form {...form}>
            <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
              <FormField
                control={form.control}
                name="queueId"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Annotation Queue</FormLabel>
                    <Select
                      onValueChange={field.onChange}
                      value={field.value}
                      disabled={queuesLoading || isSubmitting}
                    >
                      <FormControl>
                        <SelectTrigger>
                          <SelectValue placeholder="Select a queue" />
                        </SelectTrigger>
                      </FormControl>
                      <SelectContent>
                        {queuesLoading ? (
                          <SelectItem value="" disabled>
                            <div className="flex items-center gap-2">
                              <Loader2 className="size-4 animate-spin" />
                              Loading queues...
                            </div>
                          </SelectItem>
                        ) : activeQueues.length === 0 ? (
                          <SelectItem value="" disabled>
                            No active queues available
                          </SelectItem>
                        ) : (
                          activeQueues.map((queue) => (
                            <SelectItem key={queue.id} value={queue.id}>
                              <div className="flex flex-col">
                                <span className="font-medium">{queue.name}</span>
                                {queue.description && (
                                  <span className="text-xs text-muted-foreground">
                                    {queue.description}
                                  </span>
                                )}
                              </div>
                            </SelectItem>
                          ))
                        )}
                      </SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <div className="flex justify-end gap-2 pt-4">
                <Button
                  type="button"
                  variant="outline"
                  onClick={handleClose}
                  disabled={isSubmitting}
                >
                  Cancel
                </Button>
                <Button
                  type="submit"
                  disabled={isSubmitting || activeQueues.length === 0}
                >
                  {isSubmitting && <Loader2 className="mr-2 size-4 animate-spin" />}
                  Add to Queue
                </Button>
              </div>
            </form>
          </Form>
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default AddToQueueDialog;