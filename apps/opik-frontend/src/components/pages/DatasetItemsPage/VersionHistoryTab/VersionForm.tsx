import React from "react";
import { useForm, Controller } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";

import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
} from "@/components/ui/form";
import { Textarea } from "@/components/ui/textarea";
import TagListRenderer from "@/components/shared/TagListRenderer/TagListRenderer";

const versionFormSchema = z.object({
  versionNote: z.string().optional(),
  tags: z.array(z.string()),
});

export type VersionFormData = z.infer<typeof versionFormSchema>;

type VersionFormProps = {
  id: string;
  initialValues?: Partial<VersionFormData>;
  onSubmit: (data: VersionFormData) => void;
  immutableTags?: string[];
};

const VersionForm: React.FC<VersionFormProps> = ({
  id,
  initialValues,
  onSubmit,
  immutableTags = [],
}) => {
  const form = useForm<VersionFormData>({
    resolver: zodResolver(versionFormSchema),
    defaultValues: {
      versionNote: initialValues?.versionNote ?? "",
      tags: initialValues?.tags ?? [],
    },
  });

  return (
    <Form {...form}>
      <form
        id={id}
        onSubmit={form.handleSubmit(onSubmit)}
        className="space-y-6"
      >
        <FormField
          control={form.control}
          name="versionNote"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Version note (optional)</FormLabel>
              <FormControl>
                <Textarea
                  {...field}
                  placeholder="Describe what changed in this version"
                  className="min-h-32 resize-none"
                />
              </FormControl>
            </FormItem>
          )}
        />

        <Controller
          control={form.control}
          name="tags"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Tags</FormLabel>
              <FormControl>
                <TagListRenderer
                  tags={field.value}
                  immutableTags={immutableTags}
                  onAddTag={(newTag) =>
                    form.setValue("tags", [...field.value, newTag])
                  }
                  onDeleteTag={(tagToDelete) =>
                    form.setValue(
                      "tags",
                      field.value.filter((tag) => tag !== tagToDelete),
                    )
                  }
                  align="start"
                />
              </FormControl>
            </FormItem>
          )}
        />
      </form>
    </Form>
  );
};

export default VersionForm;
