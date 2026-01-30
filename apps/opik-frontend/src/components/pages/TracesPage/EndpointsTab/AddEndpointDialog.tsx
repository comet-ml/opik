import React, { useState, useEffect } from "react";
import { Upload } from "lucide-react";

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import type { Endpoint } from "@/api/endpoints/useEndpoints";

type EndpointFormData = {
  name: string;
  url: string;
  secret: string;
  schemaJson: string | null;
};

type AddEndpointDialogProps = {
  open: boolean;
  onClose: () => void;
  onSubmit: (endpoint: EndpointFormData) => void;
  endpoint?: Endpoint | null;
  isLoading?: boolean;
};

const AddEndpointDialog: React.FC<AddEndpointDialogProps> = ({
  open,
  onClose,
  onSubmit,
  endpoint,
  isLoading = false,
}) => {
  const [name, setName] = useState("");
  const [url, setUrl] = useState("");
  const [secret, setSecret] = useState("");
  const [schemaJson, setSchemaJson] = useState("");
  const [schemaError, setSchemaError] = useState<string | null>(null);

  const isEditing = Boolean(endpoint);

  useEffect(() => {
    if (open) {
      if (endpoint) {
        setName(endpoint.name);
        setUrl(endpoint.url);
        setSecret(endpoint.secret || "");
        setSchemaJson(endpoint.schema_json || "");
      } else {
        setName("");
        setUrl("");
        setSecret("");
        setSchemaJson("");
      }
      setSchemaError(null);
    }
  }, [open, endpoint]);

  const validateSchema = (json: string): boolean => {
    if (!json.trim()) {
      setSchemaError(null);
      return true;
    }
    try {
      JSON.parse(json);
      setSchemaError(null);
      return true;
    } catch {
      setSchemaError("Invalid JSON");
      return false;
    }
  };

  const handleSchemaChange = (value: string) => {
    setSchemaJson(value);
    validateSchema(value);
  };

  const handleFileUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = (event) => {
      const content = event.target?.result as string;
      setSchemaJson(content);
      validateSchema(content);
    };
    reader.readAsText(file);
  };

  const handleSubmit = () => {
    if (!name.trim() || !url.trim() || !secret.trim()) return;
    if (schemaJson && !validateSchema(schemaJson)) return;

    onSubmit({
      name: name.trim(),
      url: url.trim(),
      secret: secret.trim(),
      schemaJson: schemaJson.trim() || null,
    });
  };

  const isValid =
    name.trim() && url.trim() && secret.trim() && !schemaError;

  return (
    <Dialog open={open} onOpenChange={(isOpen) => !isOpen && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>
            {isEditing ? "Edit Endpoint" : "Add Endpoint"}
          </DialogTitle>
        </DialogHeader>

        <div className="space-y-4 py-4">
          <div className="space-y-2">
            <Label htmlFor="name">Name</Label>
            <Input
              id="name"
              placeholder="My Agent"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="url">URL</Label>
            <Input
              id="url"
              type="url"
              placeholder="https://api.example.com/agent"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
            />
            <p className="text-xs text-muted-slate">
              The endpoint URL where your agent receives requests
            </p>
          </div>

          <div className="space-y-2">
            <Label htmlFor="secret">Secret / API Key</Label>
            <Input
              id="secret"
              type="password"
              placeholder="sk-..."
              value={secret}
              onChange={(e) => setSecret(e.target.value)}
            />
            <p className="text-xs text-muted-slate">
              Authentication token sent in the Authorization header
            </p>
          </div>

          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <Label htmlFor="schema">Request Schema (optional)</Label>
              <label className="cursor-pointer">
                <input
                  type="file"
                  accept=".json"
                  className="hidden"
                  onChange={handleFileUpload}
                />
                <Button variant="ghost" size="sm" asChild>
                  <span>
                    <Upload className="mr-1.5 size-3.5" />
                    Upload JSON
                  </span>
                </Button>
              </label>
            </div>
            <Textarea
              id="schema"
              placeholder='{"type": "object", "properties": {...}}'
              value={schemaJson}
              onChange={(e) => handleSchemaChange(e.target.value)}
              className="min-h-[120px] font-mono text-sm"
            />
            {schemaError && (
              <p className="text-xs text-destructive">{schemaError}</p>
            )}
            <p className="text-xs text-muted-slate">
              JSON Schema describing the expected request format
            </p>
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={isLoading}>
            Cancel
          </Button>
          <Button onClick={handleSubmit} disabled={!isValid || isLoading}>
            {isLoading
              ? "Saving..."
              : isEditing
                ? "Save Changes"
                : "Add Endpoint"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEndpointDialog;
