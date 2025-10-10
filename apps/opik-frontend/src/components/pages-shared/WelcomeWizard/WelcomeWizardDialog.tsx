import React, { useCallback, useState } from "react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import useWelcomeWizardSubmitMutation from "@/api/welcome-wizard/useWelcomeWizardSubmitMutation";
import { WelcomeWizardSubmission } from "@/types/welcome-wizard";
import { Loader } from "lucide-react";
import { useToast } from "@/components/ui/use-toast";

interface WelcomeWizardDialogProps {
  open: boolean;
  onClose: () => void;
}

const formSchema = z.object({
  role: z.string().optional(),
  integrations: z.array(z.string()).optional(),
  customIntegration: z.string().optional(),
  email: z.string().email("Invalid email address").optional().or(z.literal("")),
  joinBetaProgram: z.boolean().optional(),
});

type FormData = z.infer<typeof formSchema>;

const ROLES = [
  "Software Engineer",
  "Data Scientist",
  "ML Engineer",
  "Researcher",
  "Student",
  "Other",
];

const INTEGRATIONS = [
  "OpenAI",
  "Anthropic",
  "Bedrock",
  "Gemini",
  "Ollama",
  "LangChain",
  "LangGraph",
  "LlamaIndex",
  "Haystack",
  "LiteLLM",
  "CrewAI",
  "Other",
];

const WelcomeWizardDialog: React.FunctionComponent<
  WelcomeWizardDialogProps
> = ({ open, onClose }) => {
  const { mutate: submitWizard, isPending } = useWelcomeWizardSubmitMutation();
  const { toast } = useToast();
  const [showCustomIntegration, setShowCustomIntegration] = useState(false);

  const form = useForm<FormData>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      role: "",
      integrations: [],
      customIntegration: "",
      email: "",
      joinBetaProgram: false,
    },
  });

  const integrations = form.watch("integrations");

  // Show custom integration input when "Other" is selected
  React.useEffect(() => {
    setShowCustomIntegration(integrations?.includes("Other") || false);
  }, [integrations]);

  const handleDismiss = useCallback(() => {
    // Submit empty data to mark as completed without collecting any info
    const emptySubmission: WelcomeWizardSubmission = {};
    submitWizard(emptySubmission, {
      onSuccess: () => {
        onClose();
      },
    });
  }, [submitWizard, onClose]);

  const onSubmit = useCallback(
    (data: FormData) => {
      // Combine integrations with custom integration if provided
      let finalIntegrations = data.integrations || [];
      if (data.customIntegration?.trim()) {
        finalIntegrations = [
          ...finalIntegrations.filter((i) => i !== "Other"),
          data.customIntegration.trim(),
        ];
      }

      const submission: WelcomeWizardSubmission = {
        role: data.role || undefined,
        integrations:
          finalIntegrations.length > 0 ? finalIntegrations : undefined,
        email: data.email || undefined,
        joinBetaProgram: data.joinBetaProgram || undefined,
      };
      submitWizard(submission, {
        onSuccess: () => {
          toast({ description: "Welcome wizard submitted successfully!" });
          onClose();
        },
      });
    },
    [submitWizard, onClose, toast],
  );

  return (
    <Dialog
      open={open}
      onOpenChange={(isOpen) => {
        if (!isOpen) {
          handleDismiss();
        }
      }}
    >
      <DialogContent
        className="sm:max-w-[600px]"
        onPointerDownOutside={(e) => e.preventDefault()}
      >
        <DialogHeader>
          <DialogTitle>Welcome to Opik 🚀</DialogTitle>
          <DialogDescription>
            We&apos;re moving fast! Tell us who you are so we can share the most
            relevant guides and updates with you.
          </DialogDescription>
        </DialogHeader>
        <Form {...form}>
          <form
            onSubmit={form.handleSubmit(onSubmit)}
            className="grid gap-4 py-4"
          >
            <FormField
              control={form.control}
              name="role"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Your Role</FormLabel>
                  <Select
                    onValueChange={field.onChange}
                    defaultValue={field.value}
                  >
                    <FormControl>
                      <SelectTrigger>
                        <SelectValue placeholder="Select your role" />
                      </SelectTrigger>
                    </FormControl>
                    <SelectContent>
                      {ROLES.map((role) => (
                        <SelectItem key={role} value={role}>
                          {role}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="integrations"
              render={() => (
                <FormItem>
                  <div className="mb-4">
                    <FormLabel>Integrations you use</FormLabel>
                  </div>
                  <div className="grid grid-cols-3 gap-4">
                    {INTEGRATIONS.map((item) => (
                      <FormField
                        key={item}
                        control={form.control}
                        name="integrations"
                        render={({ field }) => {
                          return (
                            <FormItem
                              key={item}
                              className="flex flex-row items-start space-x-3 space-y-0"
                            >
                              <FormControl>
                                <Checkbox
                                  checked={field.value?.includes(item)}
                                  onCheckedChange={(checked) => {
                                    return checked
                                      ? field.onChange([
                                          ...(field.value || []),
                                          item,
                                        ])
                                      : field.onChange(
                                          field.value?.filter(
                                            (value) => value !== item,
                                          ),
                                        );
                                  }}
                                />
                              </FormControl>
                              <FormLabel className="font-normal">
                                {item}
                              </FormLabel>
                            </FormItem>
                          );
                        }}
                      />
                    ))}
                  </div>
                  <FormMessage />
                </FormItem>
              )}
            />

            {showCustomIntegration && (
              <FormField
                control={form.control}
                name="customIntegration"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Specify other integration</FormLabel>
                    <FormControl>
                      <Input placeholder="Enter integration name" {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            )}

            <FormField
              control={form.control}
              name="email"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>
                    Email{" "}
                    <span className="text-xs text-muted-foreground">
                      (optional)
                    </span>
                  </FormLabel>
                  <FormControl>
                    <Input
                      placeholder="your@email.com"
                      {...field}
                      type="email"
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="joinBetaProgram"
              render={({ field }) => (
                <FormItem className="flex flex-row items-start space-x-3 space-y-0">
                  <FormControl>
                    <Checkbox
                      checked={field.value}
                      onCheckedChange={field.onChange}
                    />
                  </FormControl>
                  <div className="space-y-1 leading-none">
                    <FormLabel className="font-normal">
                      Join beta programs and get early access
                    </FormLabel>
                  </div>
                </FormItem>
              )}
            />

            <div className="space-y-2">
              <Button type="submit" disabled={isPending} className="w-full">
                {isPending && <Loader className="mr-2 size-4 animate-spin" />}
                Submit
              </Button>
              <Button
                type="button"
                variant="link"
                onClick={handleDismiss}
                className="w-full text-sm text-muted-foreground"
              >
                Skip and go to Opik
              </Button>
            </div>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
};

export default WelcomeWizardDialog;
