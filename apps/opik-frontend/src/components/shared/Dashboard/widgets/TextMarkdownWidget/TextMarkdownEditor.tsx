import React, { forwardRef, useImperativeHandle } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import get from "lodash/get";

import {
  DashboardWidget,
  TextMarkdownWidget,
  WidgetEditorHandle,
} from "@/types/dashboard";
import {
  useDashboardStore,
  selectUpdatePreviewWidget,
} from "@/store/DashboardStore";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { cn } from "@/lib/utils";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { TextMarkdownWidgetSchema, TextMarkdownWidgetFormData } from "./schema";
import WidgetEditorBaseLayout from "@/components/shared/Dashboard/WidgetConfigDialog/WidgetEditorBaseLayout";

const TextMarkdownEditor = forwardRef<WidgetEditorHandle>((_, ref) => {
  const widgetData = useDashboardStore(
    (state) => state.previewWidget!,
  ) as DashboardWidget & TextMarkdownWidget;
  const updatePreviewWidget = useDashboardStore(selectUpdatePreviewWidget);

  const { config } = widgetData;
  const codemirrorTheme = useCodemirrorTheme();
  const content = config.content || "";

  const form = useForm<TextMarkdownWidgetFormData>({
    resolver: zodResolver(TextMarkdownWidgetSchema),
    mode: "onTouched",
    defaultValues: {
      content,
    },
  });

  useImperativeHandle(ref, () => ({
    submit: async () => {
      return await form.trigger();
    },
    isValid: form.formState.isValid,
  }));

  const handleContentChange = (value: string) => {
    updatePreviewWidget({
      config: {
        ...config,
        content: value,
      },
    });
  };

  return (
    <Form {...form}>
      <WidgetEditorBaseLayout>
        <div className="space-y-4">
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
                        "border-destructive": Boolean(
                          validationErrors?.message,
                        ),
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
      </WidgetEditorBaseLayout>
    </Form>
  );
});

TextMarkdownEditor.displayName = "TextMarkdownEditor";

export default TextMarkdownEditor;
