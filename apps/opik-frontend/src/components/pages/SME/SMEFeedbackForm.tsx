import React from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";

import { Button } from "@/components/ui/button";
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Slider } from "@/components/ui/slider";
import { Card, CardContent } from "@/components/ui/card";
import { Save } from "lucide-react";

import { SMEAnnotationQueue, SMEAnnotationSubmission, AnnotationQueueScope } from "@/types/annotation-queues";
import useSMEAnnotationMutation from "@/api/annotation-queues/useSMEAnnotationMutation";

type SMEFeedbackFormProps = {
  queue: SMEAnnotationQueue;
  shareToken: string;
  itemId: string;
  onSubmitSuccess?: () => void;
};

const createFormSchema = (feedbackDefinitions: string[], commentsEnabled: boolean) => {
  const feedbackScores = feedbackDefinitions.reduce((acc, definition) => {
    acc[definition] = z.number().min(0).max(1);
    return acc;
  }, {} as Record<string, z.ZodNumber>);

  return z.object({
    feedbackScores: z.object(feedbackScores),
    comment: commentsEnabled ? z.string().optional() : z.string().optional(),
  });
};

const SMEFeedbackForm: React.FunctionComponent<SMEFeedbackFormProps> = ({
  queue,
  shareToken,
  itemId,
  onSubmitSuccess,
}) => {
  const mutation = useSMEAnnotationMutation();

  const formSchema = createFormSchema(queue.feedback_definitions, queue.comments_enabled);
  type FormData = z.infer<typeof formSchema>;

  const form = useForm<FormData>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      feedbackScores: queue.feedback_definitions.reduce((acc, definition) => {
        acc[definition] = 0.5; // Default to middle value
        return acc;
      }, {} as Record<string, number>),
      comment: "",
    },
  });

  const onSubmit = (data: FormData) => {
    const submission: SMEAnnotationSubmission = {
      feedback_scores: Object.entries(data.feedbackScores).map(([name, value]) => ({
        name,
        value,
      })),
      comment: data.comment,
    };

    mutation.mutate(
      { shareToken, itemId, annotation: submission },
      {
        onSuccess: () => {
          // Reset form for next item
          form.reset();
          onSubmitSuccess?.();
        },
      }
    );
  };

  return (
    <div className="space-y-6">
      <Form {...form}>
        <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
          {/* Feedback Scores Section */}
          <div className="space-y-5">
            <div className="space-y-1">
              <h3 className="text-base font-medium text-gray-900">Rate this {queue.scope === AnnotationQueueScope.TRACE ? "trace" : "conversation"}</h3>
              <p className="text-sm text-gray-500">
                Score each criteria from 0 (poor) to 1 (excellent)
              </p>
            </div>

            <div className="space-y-5">
              {queue.feedback_definitions.map((definition) => (
                <FormField
                  key={definition}
                  control={form.control}
                  name={`feedbackScores.${definition}`}
                  render={({ field }) => (
                    <FormItem className="space-y-3">
                      <div className="flex items-center justify-between">
                        <FormLabel className="text-sm font-medium text-gray-700">
                          {definition}
                        </FormLabel>
                        <div className="bg-blue-50 text-blue-700 px-2 py-1 rounded text-sm font-medium">
                          {field.value.toFixed(1)}
                        </div>
                      </div>
                      <FormControl>
                        <div className="px-1">
                          <Slider
                            value={[field.value]}
                            onValueChange={(values) => field.onChange(values[0])}
                            max={1}
                            min={0}
                            step={0.1}
                            className="w-full"
                          />
                          <div className="flex justify-between text-xs text-gray-400 mt-1">
                            <span>Poor (0.0)</span>
                            <span>Excellent (1.0)</span>
                          </div>
                        </div>
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              ))}
            </div>
          </div>

          {/* Comments Section */}
          {queue.comments_enabled && (
            <div className="space-y-3 pt-2 border-t border-gray-200">
              <FormField
                control={form.control}
                name="comment"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel className="text-sm font-medium text-gray-700">
                      Additional Comments
                    </FormLabel>
                    <p className="text-xs text-gray-500 mb-2">
                      Share any specific feedback or suggestions for improvement
                    </p>
                    <FormControl>
                      <Textarea
                        placeholder="Type your feedback here..."
                        className="min-h-24 resize-none"
                        {...field}
                      />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </div>
          )}

          {/* Submit Button */}
          <div className="pt-4 border-t border-gray-200">
            <Button 
              type="submit" 
              className="w-full h-12 text-base font-medium" 
              disabled={mutation.isPending}
            >
              {mutation.isPending ? (
                <>
                  <div className="mr-2 h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent" />
                  Saving...
                </>
              ) : (
                <>
                  <Save className="mr-2 h-4 w-4" />
                  Save & Continue
                </>
              )}
            </Button>
          </div>
        </form>
      </Form>
    </div>
  );
};

export default SMEFeedbackForm;
