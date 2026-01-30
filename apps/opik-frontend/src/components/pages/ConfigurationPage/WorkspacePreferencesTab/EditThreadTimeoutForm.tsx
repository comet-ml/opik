import { z } from "zod";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import { DropdownOption } from "@/types/shared";
import { isValidIso8601Duration, formatIso8601Duration } from "@/lib/date";

const formSchema = z.object({
  timeout_to_mark_thread_as_inactive: z
    .string()
    .refine((value) => isValidIso8601Duration(value, 7), {
      message: "Please select a valid timeout duration (maximum 7 days)",
    }),
});

export type EditThreadTimeoutFormValues = z.infer<typeof formSchema>;

const TIMEOUT_VALUES = ["PT5M", "PT10M", "PT15M", "PT20M", "PT30M", "PT1H"];

const TIMEOUT_OPTIONS: DropdownOption<string>[] = TIMEOUT_VALUES.map(
  (value) => ({
    value,
    label: formatIso8601Duration(value) ?? "Invalid duration",
  }),
);

export interface EditThreadTimeoutFormProps {
  defaultValue: string;
  onSubmit: (values: EditThreadTimeoutFormValues) => void;
  formId: string;
}

const EditThreadTimeoutForm: React.FC<EditThreadTimeoutFormProps> = ({
  defaultValue,
  onSubmit,
  formId,
}) => {
  const form = useForm<EditThreadTimeoutFormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      timeout_to_mark_thread_as_inactive: defaultValue,
    },
  });

  const handleSubmit = form.handleSubmit((values) => {
    onSubmit(values);
  });

  return (
    <Form {...form}>
      <form id={formId} onSubmit={handleSubmit} className="space-y-6">
        <FormField
          control={form.control}
          name="timeout_to_mark_thread_as_inactive"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Cooldown period</FormLabel>
              <FormControl>
                <SelectBox
                  value={field.value}
                  onChange={field.onChange}
                  options={TIMEOUT_OPTIONS}
                  placeholder="Select timeout duration"
                  className="w-full"
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
      </form>
    </Form>
  );
};

export default EditThreadTimeoutForm;
