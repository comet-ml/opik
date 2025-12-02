import { z } from "zod";

export const TextMarkdownWidgetSchema = z.object({
  title: z
    .string({
      required_error: "Widget title is required",
    })
    .min(1, { message: "Widget title is required" }),
  subtitle: z.string().optional(),
  content: z
    .string({
      required_error: "Markdown content is required",
    })
    .min(1, { message: "Markdown content is required" }),
});

export type TextMarkdownWidgetFormData = z.infer<
  typeof TextMarkdownWidgetSchema
>;
