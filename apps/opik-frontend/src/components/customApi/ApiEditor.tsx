import React, { useState, useEffect } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Alert, AlertDescription } from '@/components/ui/alert';
// Badge component not available, will use inline styling
import { Code, Save, X, AlertTriangle, CheckCircle } from 'lucide-react';
import customApiService from '@/services/customApiService';
import { CustomApiInstance } from '@/types/customApi';

interface ApiEditorProps {
  mode: 'create' | 'edit';
  initialData?: CustomApiInstance;
  onSave: (instanceName: string, code: string, description?: string) => void;
  onCancel: () => void;
}

const ApiEditor: React.FC<ApiEditorProps> = ({
  mode,
  initialData,
  onSave,
  onCancel,
}) => {
  const [instanceName, setInstanceName] = useState(initialData?.instance_name || '');
  const [code, setCode] = useState(initialData?.code || getDefaultCode());
  const [description, setDescription] = useState(initialData?.description || '');
  const [errors, setErrors] = useState<{ name?: string; code?: string }>({});
  const [isValidating, setIsValidating] = useState(false);

  // Clear errors when values change
  useEffect(() => {
    setErrors(prev => ({ ...prev, name: undefined }));
  }, [instanceName]);

  useEffect(() => {
    setErrors(prev => ({ ...prev, code: undefined }));
  }, [code]);

  const validateForm = () => {
    const newErrors: { name?: string; code?: string } = {};

    // Validate instance name
    const nameValidation = customApiService.validateInstanceName(instanceName);
    if (!nameValidation.valid) {
      newErrors.name = nameValidation.error;
    }

    // Validate Python code
    const codeValidation = customApiService.validatePythonCode(code);
    if (!codeValidation.valid) {
      newErrors.code = codeValidation.error;
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSave = () => {
    if (validateForm()) {
      onSave(instanceName, code, description);
    }
  };

  const handleCodeChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setCode(e.target.value);
  };

  const insertCodeExample = (example: string) => {
    setCode(example);
  };

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="instanceName">
              API Instance Name <span className="text-red-500">*</span>
            </Label>
            <Input
              id="instanceName"
              value={instanceName}
              onChange={(e) => setInstanceName(e.target.value)}
              placeholder="my-custom-api"
              disabled={mode === 'edit'}
              className={errors.name ? 'border-red-500' : ''}
            />
            {errors.name && (
              <p className="text-sm text-red-500 flex items-center gap-1">
                <AlertTriangle className="h-3 w-3" />
                {errors.name}
              </p>
            )}
            {mode === 'edit' && (
              <p className="text-xs text-muted-foreground">
                Instance name cannot be changed when editing
              </p>
            )}
          </div>

          <div className="space-y-2">
            <Label htmlFor="description">Description</Label>
            <Input
              id="description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Optional description for your API"
            />
          </div>

          <div className="space-y-2">
            <Label>Quick Examples</Label>
            <div className="flex flex-wrap gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => insertCodeExample(getSimpleDataExample())}
              >
                Simple Data
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => insertCodeExample(getCalculationExample())}
              >
                Calculation
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => insertCodeExample(getDatabaseExample())}
              >
                Database Query
              </Button>
            </div>
          </div>

          {instanceName && !errors.name && (
            <Alert>
              <CheckCircle className="h-4 w-4" />
              <AlertDescription>
                Your API will be available at: <br />
                <span className="inline-block mt-1 px-2 py-1 bg-secondary text-secondary-foreground rounded-md font-mono text-xs">
                  {customApiService.getApiEndpoint(instanceName)}
                </span>
              </AlertDescription>
            </Alert>
          )}
        </div>

        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="code">
              Python Code <span className="text-red-500">*</span>
            </Label>
            <Textarea
              id="code"
              value={code}
              onChange={handleCodeChange}
              placeholder="Enter your Python code here..."
              className={`font-mono text-sm h-80 ${errors.code ? 'border-red-500' : ''}`}
              style={{ fontFamily: 'JetBrains Mono, Consolas, Monaco, monospace' }}
            />
            {errors.code && (
              <p className="text-sm text-red-500 flex items-center gap-1">
                <AlertTriangle className="h-3 w-3" />
                {errors.code}
              </p>
            )}
          </div>

          <Alert>
            <Code className="h-4 w-4" />
            <AlertDescription>
              <strong>Tips:</strong>
              <ul className="list-disc list-inside mt-2 space-y-1 text-sm">
                <li>Assign your final result to the <code>result</code> variable</li>
                <li>Return JSON-serializable data (dict, list, string, number)</li>
                <li>Use <code>import</code> statements for any required libraries</li>
                <li>The code execution is sandboxed for security</li>
              </ul>
            </AlertDescription>
          </Alert>
        </div>
      </div>

      <div className="flex items-center justify-end space-x-2 pt-4 border-t">
        <Button variant="outline" onClick={onCancel}>
          <X className="h-4 w-4 mr-2" />
          Cancel
        </Button>
        <Button onClick={handleSave} disabled={isValidating}>
          <Save className="h-4 w-4 mr-2" />
          {mode === 'create' ? 'Create API' : 'Update API'}
        </Button>
      </div>
    </div>
  );
};

function getDefaultCode(): string {
  return `# Example: Simple data API
import datetime

result = {
    "message": "Hello from your custom API!",
    "timestamp": datetime.datetime.now().isoformat(),
    "data": [1, 2, 3, 4, 5]
}`;
}

function getSimpleDataExample(): string {
  return `# Simple data response
result = {
    "users": ["Alice", "Bob", "Charlie"],
    "count": 3,
    "status": "active"
}`;
}

function getCalculationExample(): string {
  return `# Mathematical calculation
import math

numbers = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
result = {
    "sum": sum(numbers),
    "average": sum(numbers) / len(numbers),
    "squares": [x**2 for x in numbers],
    "sqrt_sum": math.sqrt(sum(numbers))
}`;
}

function getDatabaseExample(): string {
  return `# Database-like query simulation
data = [
    {"id": 1, "name": "Product A", "price": 100, "category": "electronics"},
    {"id": 2, "name": "Product B", "price": 50, "category": "books"},
    {"id": 3, "name": "Product C", "price": 200, "category": "electronics"},
]

# Filter products by category
electronics = [p for p in data if p["category"] == "electronics"]

result = {
    "total_products": len(data),
    "electronics": electronics,
    "avg_price": sum(p["price"] for p in data) / len(data)
}`;
}

export default ApiEditor; 