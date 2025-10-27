import { Button, ButtonProps } from "@/components/ui/button";
import { Textarea, TextareaProps } from "@/components/ui/textarea";
import { cn, updateTextAreaHeight } from "@/lib/utils";
import { zodResolver } from "@hookform/resolvers/zod";
import { ArrowRight, Check, X } from "lucide-react";
import React, { useCallback, useEffect, useRef } from "react";
import { FormProvider, useForm, useFormContext } from "react-hook-form";
import { z } from "zod";
import isFunction from "lodash/isFunction";

const CancelButton = React.forwardRef<HTMLButtonElement, ButtonProps>(
  (buttonProps, ref) => {
    return (
      <Button {...buttonProps} variant="outline" size="icon-xs" ref={ref}>
        <span className="sr-only">Cancel edit comment</span>
        <X />
      </Button>
    );
  },
);
CancelButton.displayName = "CommentFormCancelButton";

const SubmitButton = React.forwardRef<
  HTMLButtonElement,
  ButtonProps & {
    editMode?: boolean;
  }
>(({ editMode, ...buttonProps }, ref) => {
  const {
    formState: { isValid, isDirty },
  } = useFormContext();

  const SubmitIcon = editMode ? Check : ArrowRight;

  return (
    <Button
      {...buttonProps}
      size="icon-xs"
      ref={ref}
      type="submit"
      disabled={!isValid || !isDirty || buttonProps.disabled}
    >
      <span className="sr-only">Approve edit comment</span>
      <SubmitIcon />
    </Button>
  );
});
SubmitButton.displayName = "CommentFormSubmitButton";

const MAX_LENGTH_LIMIT = 5000;

export type StandaloneTextareaFieldProps = TextareaProps & {
  value?: string;
  onValueChange?: (value: string) => void;
  error?: string;
};

const StandaloneTextareaField = React.forwardRef<
  HTMLTextAreaElement,
  StandaloneTextareaFieldProps
>(({ value = "", onValueChange, error, className, ...props }, ref) => {
  const textAreaRef = useRef<HTMLTextAreaElement | null>(null);

  useEffect(() => {
    const { unsubscribe } = { unsubscribe: () => {} };
    updateTextAreaHeight(textAreaRef.current);
    return () => unsubscribe();
  });

  const callbackTextareaRef = useCallback(
    (e: HTMLTextAreaElement | null) => {
      textAreaRef.current = e;
      if (isFunction(ref)) {
        ref(e);
      } else if (ref) {
        ref.current = e;
      }
      updateTextAreaHeight(e);
    },
    [ref],
  );

  const handleChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    if (textAreaRef.current) {
      updateTextAreaHeight(textAreaRef.current);
    }
    onValueChange?.(e.target.value);
  };

  return (
    <Textarea
      {...props}
      value={value}
      onChange={handleChange}
      autoFocus
      ref={callbackTextareaRef}
      maxLength={MAX_LENGTH_LIMIT}
      className={cn(
        "min-h-[64px] w-full rounded-md border p-3 pr-10 resize-none overflow-hidden",
        {
          "border-destructive": error,
        },
        className,
      )}
    />
  );
});

StandaloneTextareaField.displayName = "StandaloneTextareaField";

type TextareaFieldProps = Omit<
  TextareaProps,
  "onChange" | "onFocus" | "ref" | "value"
>;
const TextareaField: React.FC<TextareaFieldProps> = (props) => {
  const textAreaRef = useRef<HTMLTextAreaElement | null>(null);
  const { register, formState, watch } = useFormContext<CommentFormValues>();

  const { ref: commentTextRef, ...commentTextRegsiter } =
    register("commentText");

  useEffect(() => {
    const { unsubscribe } = watch(() => {
      updateTextAreaHeight(textAreaRef.current);
    });
    return () => unsubscribe();
  }, [watch]);

  const callbackTextareaRef = useCallback(
    (e: HTMLTextAreaElement | null) => {
      commentTextRef(e);
      textAreaRef.current = e;
      updateTextAreaHeight(e);
    },
    [commentTextRef],
  );

  return (
    <Textarea
      {...props}
      {...commentTextRegsiter}
      autoFocus
      ref={callbackTextareaRef}
      maxLength={MAX_LENGTH_LIMIT}
      className={cn(
        "min-h-[64px] w-full rounded-md border p-3 pr-10 resize-none overflow-hidden",
        {
          "border-destructive": formState.errors.commentText,
        },
        props.className,
      )}
    />
  );
};

type UserCommentFormComponents = {
  SubmitButton: typeof SubmitButton;
  CancelButton: typeof CancelButton;
  TextareaField: typeof TextareaField;
  StandaloneTextareaField: typeof StandaloneTextareaField;
};

const commentSchema = z.object({
  commentText: z
    .string()
    .min(1, "Can not be empty")
    .max(MAX_LENGTH_LIMIT, `Max ${MAX_LENGTH_LIMIT} characters`),
});
type CommentFormValues = z.infer<typeof commentSchema>;

export type UserCommentFormProps = {
  onSubmit: (data: CommentFormValues) => void;
  actions?: React.ReactNode;
  commentText?: string;
  className?: string;
  children: React.ReactNode;
};
const UserCommentForm: UserCommentFormComponents &
  React.FC<UserCommentFormProps> = ({
  onSubmit,
  actions,
  commentText,
  className,
  children,
}) => {
  const form = useForm<CommentFormValues>({
    resolver: zodResolver(commentSchema),
    defaultValues: {
      commentText,
    },
  });

  const {
    reset,
    formState: { isSubmitSuccessful },
  } = form;

  const onKeyDown: React.KeyboardEventHandler<HTMLFormElement> = (e) => {
    if ((e.metaKey || e.ctrlKey) && e.key === "Enter") {
      e.preventDefault();
      form.handleSubmit(onSubmit)();
    }
  };

  useEffect(() => {
    if (isSubmitSuccessful) {
      reset();
    }
  }, [isSubmitSuccessful, reset]);

  return (
    <FormProvider {...form}>
      <form
        onSubmit={form.handleSubmit(onSubmit)}
        className={cn("w-full", className)}
        onKeyDown={onKeyDown}
      >
        <div className="relative">
          {children}

          <div className="absolute bottom-0 right-0 flex gap-2 p-2">
            {actions}
          </div>
        </div>
      </form>
    </FormProvider>
  );
};

UserCommentForm.CancelButton = CancelButton;
UserCommentForm.SubmitButton = SubmitButton;
UserCommentForm.TextareaField = TextareaField;
UserCommentForm.StandaloneTextareaField = StandaloneTextareaField;

export default UserCommentForm;
