import React, { useId, useState } from "react";
import { Input, InputProps } from "@/components/ui/input";
import { Eye, EyeOff } from "lucide-react";
import { cn } from "@/lib/utils";

interface EyeInputProps extends InputProps {}

const EyeInput = (props: EyeInputProps) => {
  const [hidden, setHidden] = useState(true);
  const id = useId();

  const Icon = hidden ? Eye : EyeOff;

  return (
    <div className="relative">
      <Input
        name={id}
        {...props}
        style={
          {
            ...(props?.style || {}),
            WebkitTextSecurity: hidden ? "disc" : "none",
          } as React.CSSProperties
        }
        className={cn(props.className, "pr-8")}
      />
      <div
        className="absolute right-3 top-1/2 -translate-y-1/2 cursor-pointer"
        onClick={(e) => {
          e.preventDefault();
          setHidden((h) => !h);
        }}
      >
        <Icon className="size-4 text-light-slate" />
      </div>
    </div>
  );
};

export default EyeInput;
