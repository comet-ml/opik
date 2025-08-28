import React from "react";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
import { CheckCircle, XCircle, AlertCircle, Info } from "lucide-react";

export const ThemePreview: React.FC = () => {
  return (
    <Card className="h-full">
      <CardHeader>
        <CardTitle>Theme Preview</CardTitle>
        <CardDescription>
          See how your selected theme will look across the interface
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {/* Buttons */}
        <div className="space-y-2">
          <Label>Buttons</Label>
          <div className="flex flex-wrap gap-2">
            <Button variant="default" size="sm">
              Primary
            </Button>
            <Button variant="secondary" size="sm">
              Secondary
            </Button>
            <Button variant="outline" size="sm">
              Outline
            </Button>
            <Button variant="ghost" size="sm">
              Ghost
            </Button>
            <Button variant="destructive" size="sm">
              Destructive
            </Button>
          </div>
        </div>

        {/* Input */}
        <div className="space-y-2">
          <Label htmlFor="preview-input">Input Field</Label>
          <Input
            id="preview-input"
            placeholder="Sample input text..."
            defaultValue="Example text"
          />
        </div>

        {/* Badges */}
        <div className="space-y-2">
          <Label>Badges & Status</Label>
          <div className="flex flex-wrap gap-2">
            <Badge>Default</Badge>
            <Badge variant="secondary">Secondary</Badge>
            <Badge variant="outline">Outline</Badge>
            <Badge variant="destructive">Destructive</Badge>
          </div>
        </div>

        {/* Alert States */}
        <div className="space-y-2">
          <Label>Alert States</Label>
          <div className="space-y-2">
            <div className="flex items-center gap-2 text-sm">
              <CheckCircle className="size-4 text-success" />
              <span>Success message</span>
            </div>
            <div className="flex items-center gap-2 text-sm">
              <AlertCircle className="size-4 text-warning" />
              <span>Warning message</span>
            </div>
            <div className="flex items-center gap-2 text-sm">
              <XCircle className="size-4 text-destructive" />
              <span>Error message</span>
            </div>
            <div className="flex items-center gap-2 text-sm">
              <Info className="size-4 text-primary" />
              <span>Information message</span>
            </div>
          </div>
        </div>

        {/* Code Block */}
        <div className="space-y-2">
          <Label>Code Block</Label>
          <div className="rounded-md bg-muted p-3">
            <code className="text-sm">{`const theme = useTheme();`}</code>
          </div>
        </div>

        {/* Nested Card */}
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-base">Nested Card Example</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-sm text-muted-foreground">
              This shows how cards and nested content appear in the selected
              theme.
            </p>
          </CardContent>
        </Card>
      </CardContent>
    </Card>
  );
};
