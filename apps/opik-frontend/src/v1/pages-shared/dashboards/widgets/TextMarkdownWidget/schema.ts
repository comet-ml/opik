import { z } from "zod";

export const TextMarkdownWidgetSchema = z.object({
  content: z
    .string({
      required_error: "Markdown content is required",
    })
    .min(1, { message: "Markdown content is required" }),
});

export type TextMarkdownWidgetFormData = z.infer<
  typeof TextMarkdownWidgetSchema
>;
