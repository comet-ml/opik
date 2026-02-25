# Form Handling Patterns

## React Hook Form + Zod Setup

```typescript
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";

// Define schema
const formSchema = z.object({
  name: z.string().min(1, "Name is required"),
  email: z.string().email("Invalid email"),
  description: z.string().optional(),
});

type FormData = z.infer<typeof formSchema>;

// Use in component
const form = useForm<FormData>({
  resolver: zodResolver(formSchema),
  defaultValues: {
    name: "",
    email: "",
    description: "",
  },
});
```

## Form JSX Structure

```typescript
<Form {...form}>
  <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
    <FormField
      control={form.control}
      name="name"
      render={({ field }) => (
        <FormItem>
          <FormLabel>Name</FormLabel>
          <FormControl>
            <Input {...field} />
          </FormControl>
          <FormMessage />
        </FormItem>
      )}
    />

    <Button type="submit" disabled={form.formState.isSubmitting}>
      {form.formState.isSubmitting && <Spinner className="mr-2" />}
      Submit
    </Button>
  </form>
</Form>
```

## Dynamic Form Fields

```typescript
const { fields, append, remove } = useFieldArray({
  control: form.control,
  name: "items",
});

{fields.map((field, index) => (
  <div key={field.id} className="flex gap-2">
    <FormField
      control={form.control}
      name={`items.${index}.name`}
      render={({ field }) => (
        <FormItem>
          <FormControl>
            <Input {...field} placeholder="Name" />
          </FormControl>
          <FormMessage />
        </FormItem>
      )}
    />
    <Button type="button" variant="outline" onClick={() => remove(index)}>
      Remove
    </Button>
  </div>
))}

<Button type="button" onClick={() => append({ name: "", value: "" })}>
  Add Item
</Button>
```

## Conditional Validation

```typescript
const formSchema = z
  .object({
    type: z.enum(["user", "admin"]),
    permissions: z.array(z.string()).optional(),
  })
  .refine(
    (data) => {
      if (data.type === "admin" && (!data.permissions || data.permissions.length === 0)) {
        return false;
      }
      return true;
    },
    {
      message: "Admin users must have at least one permission",
      path: ["permissions"],
    },
  );
```
