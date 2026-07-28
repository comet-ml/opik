import { useMutation } from "@tanstack/react-query";
import { useToast } from "@/ui/use-toast";
import usePluginsStore from "@/store/PluginsStore";

const useSendOnboardingEmailMutation = () => {
  const sendOnboardingEmail = usePluginsStore((s) => s.sendOnboardingEmail);
  const { toast } = useToast();

  const mutation = useMutation({
    mutationFn: (email: string) => sendOnboardingEmail!(email),
    onError: () => {
      toast({
        title: "Failed to send email",
        description: "Please try again.",
        variant: "destructive",
      });
    },
  });

  return { ...mutation, isAvailable: sendOnboardingEmail !== null };
};

export default useSendOnboardingEmailMutation;
