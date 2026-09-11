import React, { useId, useState } from "react";
import { Input, InputProps } from "@/ui/input";
import { Eye, EyeOff } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/ui/button";

interface EyeInputProps extends InputProps {
  revealable?: boolean;
}

const EyeInput = ({ revealable = true, ...props }: EyeInputProps) => {
  const [hidden, setHidden] = useState(true);
  const id = useId();

  const isHidden = hidden || !revealable;
  const Icon = isHidden ? Eye : EyeOff;

  return (
    <div className="relative">
      <Input
        name={id}
        {...props}
        style={
          {
            ...(props?.style || {}),
            WebkitTextSecurity: isHidden ? "disc" : "none",
          } as React.CSSProperties
        }
        className={cn(props.className, revealable && "pr-8")}
      />
      {revealable && (
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="absolute right-0 top-1/2 -translate-y-1/2"
          onClick={() => setHidden((h) => !h)}
          disabled={props.disabled}
        >
          <Icon className="size-4 text-light-slate" />
        </Button>
      )}
    </div>
  );
};

export default EyeInput;
