import React, { useEffect, forwardRef, useImperativeHandle } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";

import {
  AddWidgetConfig,
  TextMarkdownWidget,
  WidgetEditorHandle,
} from "@/types/dashboard";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { TextMarkdownWidgetSchema, TextMarkdownWidgetFormData } from "./schema";

type TextMarkdownEditorProps = AddWidgetConfig & {
  onChange: (data: Partial<AddWidgetConfig>) => void;
  onValidationChange?: (isValid: boolean) => void;
};

const TextMarkdownEditor = forwardRef<
  WidgetEditorHandle,
  TextMarkdownEditorProps
>(({ title, subtitle, config, onChange }, ref) => {
  const codemirrorTheme = useCodemirrorTheme();
  const content = (config as TextMarkdownWidget["config"])?.content || "";

  const form = useForm<TextMarkdownWidgetFormData>({
    resolver: zodResolver(TextMarkdownWidgetSchema),
    mode: "onTouched",
    defaultValues: {
      title,
      subtitle: subtitle || "",
      content,
    },
  });

  useImperativeHandle(ref, () => ({
    submit: async () => {
      const isValid = await form.trigger();
      if (isValid) {
        const values = form.getValues();
        onChange({
          title: values.title,
          subtitle: values.subtitle,
          config: {
            ...config,
            content: values.content,
          },
        });
      }
      return isValid;
    },
    isValid: form.formState.isValid,
  }));

  useEffect(() => {
    form.reset({
      title,
      subtitle: subtitle || "",
      content,
    });
  }, [title, subtitle, content, form]);

  const handleFieldChange = (
    field: keyof TextMarkdownWidgetFormData,
    value: string,
  ) => {
    if (field === "title") {
      onChange({ title: value });
    } else if (field === "subtitle") {
      onChange({ subtitle: value });
    } else if (field === "content") {
      onChange({
        config: {
          ...config,
          content: value,
        },
      });
    }
  };

  return (
    <Form {...form}>
      <div className="space-y-4">
        <FormField
          control={form.control}
          name="title"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Widget title</FormLabel>
              <FormControl>
                <Input
                  placeholder="Enter widget title"
                  {...field}
                  onChange={(e) => {
                    field.onChange(e);
                    handleFieldChange("title", e.target.value);
                  }}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

        <FormField
          control={form.control}
          name="subtitle"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Widget subtitle (optional)</FormLabel>
              <FormControl>
                <Input
                  placeholder="Enter widget subtitle"
                  {...field}
                  onChange={(e) => {
                    field.onChange(e);
                    handleFieldChange("subtitle", e.target.value);
                  }}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

        <FormField
          control={form.control}
          name="content"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Markdown content</FormLabel>
              <FormControl>
                <div className="overflow-hidden rounded-md border">
                  <CodeMirror
                    value={field.value}
                    onChange={(value) => {
                      field.onChange(value);
                      handleFieldChange("content", value);
                    }}
                    theme={codemirrorTheme}
                    basicSetup={{
                      lineNumbers: true,
                      foldGutter: true,
                      highlightActiveLineGutter: true,
                      highlightActiveLine: true,
                    }}
                    minHeight="200px"
                    maxHeight="300px"
                  />
                </div>
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
      </div>
    </Form>
  );
});

TextMarkdownEditor.displayName = "TextMarkdownEditor";

export default TextMarkdownEditor;
