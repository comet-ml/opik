import React, { forwardRef, useImperativeHandle } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import get from "lodash/get";

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
import { cn } from "@/lib/utils";
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

  const handleTitleChange = (value: string) => {
    onChange({ title: value });
  };

  const handleSubtitleChange = (value: string) => {
    onChange({ subtitle: value });
  };

  const handleContentChange = (value: string) => {
    onChange({
      config: {
        ...config,
        content: value,
      },
    });
  };

  return (
    <Form {...form}>
      <div className="space-y-4">
        <FormField
          control={form.control}
          name="title"
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, ["title"]);
            return (
              <FormItem>
                <FormLabel>Widget title</FormLabel>
                <FormControl>
                  <Input
                    className={cn({
                      "border-destructive": Boolean(validationErrors?.message),
                    })}
                    placeholder="Enter widget title"
                    {...field}
                    onChange={(e) => {
                      field.onChange(e);
                      handleTitleChange(e.target.value);
                    }}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            );
          }}
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
                    handleSubtitleChange(e.target.value);
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
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, ["content"]);
            return (
              <FormItem>
                <FormLabel>Markdown content</FormLabel>
                <FormControl>
                  <div
                    className={cn("overflow-hidden rounded-md border", {
                      "border-destructive": Boolean(validationErrors?.message),
                    })}
                  >
                    <CodeMirror
                      value={field.value}
                      onChange={(value) => {
                        field.onChange(value);
                        handleContentChange(value);
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
            );
          }}
        />
      </div>
    </Form>
  );
});

TextMarkdownEditor.displayName = "TextMarkdownEditor";

export default TextMarkdownEditor;
