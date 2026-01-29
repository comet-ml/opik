import React, { useState, useRef, useEffect } from "react";
import { Sparkles, Send, X, Bot, User } from "lucide-react";

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ConfigVariable } from "@/api/config/useConfigVariables";

type Message = {
  id: string;
  role: "user" | "assistant";
  content: string;
};

type OptimizeChatDialogProps = {
  open: boolean;
  onClose: () => void;
  selectedVariables: ConfigVariable[];
};

const MOCK_RESPONSES = [
  "I can help you optimize these configuration values. Based on your current settings, I notice a few opportunities for improvement.",
  "Looking at your temperature setting of 0.7, this is a good middle ground. However, if you're seeing inconsistent outputs, you might want to try lowering it to 0.5 for more deterministic responses.",
  "For max_tokens, the current value seems reasonable. Would you like me to analyze your recent traces to see if there's a pattern in token usage that could inform a better default?",
  "I'd recommend setting up an A/B experiment to test these changes. Would you like me to create one with the optimized values?",
];

const OptimizeChatDialog: React.FC<OptimizeChatDialogProps> = ({
  open,
  onClose,
  selectedVariables,
}) => {
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState("");
  const [isTyping, setIsTyping] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const responseIndexRef = useRef(0);

  useEffect(() => {
    if (open && messages.length === 0) {
      const variableNames = selectedVariables.map((v) => v.key).join(", ");
      setMessages([
        {
          id: "1",
          role: "assistant",
          content: `Hi! I'm here to help you optimize your configuration. I see you've selected: **${variableNames}**.\n\nWhat would you like to optimize? I can help with:\n- Analyzing performance patterns\n- Suggesting value improvements\n- Setting up experiments to test changes`,
        },
      ]);
    }
  }, [open, selectedVariables, messages.length]);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  const handleClose = () => {
    setMessages([]);
    setInput("");
    responseIndexRef.current = 0;
    onClose();
  };

  const handleSend = () => {
    if (!input.trim()) return;

    const userMessage: Message = {
      id: Date.now().toString(),
      role: "user",
      content: input,
    };

    setMessages((prev) => [...prev, userMessage]);
    setInput("");
    setIsTyping(true);

    setTimeout(() => {
      const response =
        MOCK_RESPONSES[responseIndexRef.current % MOCK_RESPONSES.length];
      responseIndexRef.current++;

      setMessages((prev) => [
        ...prev,
        {
          id: (Date.now() + 1).toString(),
          role: "assistant",
          content: response,
        },
      ]);
      setIsTyping(false);
    }, 1000 + Math.random() * 1000);
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  return (
    <Dialog open={open} onOpenChange={(isOpen) => !isOpen && handleClose()}>
      <DialogContent className="flex h-[600px] max-w-2xl flex-col p-0">
        <DialogHeader className="border-b px-6 py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <div className="flex size-8 items-center justify-center rounded-lg bg-gradient-to-br from-amber-400 to-orange-500">
                <Sparkles className="size-4 text-white" />
              </div>
              <div>
                <DialogTitle>Optimize Configuration</DialogTitle>
                <p className="text-xs text-muted-slate">
                  {selectedVariables.length} variable
                  {selectedVariables.length > 1 ? "s" : ""} selected
                </p>
              </div>
            </div>
            <Button variant="ghost" size="icon-sm" onClick={handleClose}>
              <X className="size-4" />
            </Button>
          </div>
        </DialogHeader>

        <div className="flex-1 overflow-auto p-4">
          <div className="space-y-4">
            {messages.map((message) => (
              <div
                key={message.id}
                className={`flex gap-3 ${
                  message.role === "user" ? "flex-row-reverse" : ""
                }`}
              >
                <div
                  className={`flex size-8 shrink-0 items-center justify-center rounded-full ${
                    message.role === "assistant"
                      ? "bg-gradient-to-br from-amber-400 to-orange-500"
                      : "bg-muted"
                  }`}
                >
                  {message.role === "assistant" ? (
                    <Bot className="size-4 text-white" />
                  ) : (
                    <User className="size-4" />
                  )}
                </div>
                <div
                  className={`max-w-[80%] rounded-lg px-4 py-2 ${
                    message.role === "assistant"
                      ? "bg-muted/50"
                      : "bg-primary text-primary-foreground"
                  }`}
                >
                  <p className="whitespace-pre-wrap text-sm">{message.content}</p>
                </div>
              </div>
            ))}
            {isTyping && (
              <div className="flex gap-3">
                <div className="flex size-8 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-amber-400 to-orange-500">
                  <Bot className="size-4 text-white" />
                </div>
                <div className="rounded-lg bg-muted/50 px-4 py-2">
                  <div className="flex gap-1">
                    <span className="size-2 animate-bounce rounded-full bg-muted-slate [animation-delay:-0.3s]" />
                    <span className="size-2 animate-bounce rounded-full bg-muted-slate [animation-delay:-0.15s]" />
                    <span className="size-2 animate-bounce rounded-full bg-muted-slate" />
                  </div>
                </div>
              </div>
            )}
            <div ref={messagesEndRef} />
          </div>
        </div>

        <div className="border-t p-4">
          <div className="flex gap-2">
            <Input
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="Ask about optimizing your configuration..."
              className="flex-1"
            />
            <Button onClick={handleSend} disabled={!input.trim() || isTyping}>
              <Send className="size-4" />
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default OptimizeChatDialog;
