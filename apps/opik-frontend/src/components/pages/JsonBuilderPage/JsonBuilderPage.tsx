import React, { useState, useCallback, useMemo } from "react";
import { Braces, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  JsonTreePopover,
  JsonObject,
  JsonValue,
} from "@/components/shared/JsonTreePopover";

// Type for selected items
interface SelectedItem {
  path: string;
  value: JsonValue;
}

// Example JSON objects
const JSON_EXAMPLES = [
  {
    name: "User Profile",
    json: `{
  "user": {
    "id": "12345",
    "name": "John Doe",
    "email": "john@example.com",
    "profile": {
      "age": 30,
      "location": {
        "city": "San Francisco",
        "country": "USA",
        "coordinates": {
          "lat": 37.7749,
          "lng": -122.4194
        }
      },
      "preferences": {
        "theme": "dark",
        "notifications": true
      }
    }
  },
  "metadata": {
    "created_at": "2024-01-15T10:30:00Z",
    "updated_at": "2024-01-20T15:45:00Z",
    "version": 2
  },
  "tags": ["premium", "verified", "active"]
}`,
  },
  {
    name: "LLM Response",
    json: `{
  "input": {
    "messages": [
      {"role": "system", "content": "You are a helpful assistant."},
      {"role": "user", "content": "What is the capital of France?"}
    ],
    "model": "gpt-4",
    "temperature": 0.7
  },
  "output": {
    "choices": [
      {
        "message": {
          "role": "assistant",
          "content": "The capital of France is Paris."
        },
        "finish_reason": "stop"
      }
    ],
    "usage": {
      "prompt_tokens": 25,
      "completion_tokens": 10,
      "total_tokens": 35
    }
  },
  "metadata": {
    "latency_ms": 450,
    "trace_id": "abc123"
  }
}`,
  },
  {
    name: "E-commerce Order",
    json: `{
  "order": {
    "id": "ORD-2024-001",
    "status": "shipped",
    "customer": {
      "name": "Alice Smith",
      "email": "alice@example.com",
      "address": {
        "street": "123 Main St",
        "city": "New York",
        "zip": "10001"
      }
    },
    "items": [
      {"sku": "PROD-A", "name": "Widget", "quantity": 2, "price": 29.99},
      {"sku": "PROD-B", "name": "Gadget", "quantity": 1, "price": 49.99}
    ],
    "totals": {
      "subtotal": 109.97,
      "tax": 9.90,
      "shipping": 5.99,
      "total": 125.86
    }
  },
  "tracking": {
    "carrier": "FedEx",
    "number": "1234567890",
    "estimated_delivery": "2024-01-25"
  }
}`,
  },
  {
    name: "API Error",
    json: `{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Invalid request parameters",
    "details": [
      {"field": "email", "issue": "Invalid email format"},
      {"field": "age", "issue": "Must be a positive number"}
    ]
  },
  "request_id": "req_abc123",
  "timestamp": "2024-01-20T10:30:00Z"
}`,
  },
  {
    name: "Deep Nested",
    json: `{
  "company": {
    "name": "TechCorp Inc.",
    "departments": {
      "engineering": {
        "teams": {
          "frontend": {
            "lead": {
              "name": "Sarah Chen",
              "contact": {
                "email": "sarah@techcorp.com",
                "phone": {
                  "office": "+1-555-0100",
                  "mobile": "+1-555-0101"
                }
              }
            },
            "projects": [
              {
                "name": "Dashboard Redesign",
                "status": {
                  "phase": "development",
                  "progress": {
                    "completed": 65,
                    "remaining": 35,
                    "milestones": {
                      "design": {"done": true, "date": "2024-01-10"},
                      "development": {"done": false, "eta": "2024-02-15"},
                      "testing": {"done": false, "eta": "2024-03-01"}
                    }
                  }
                },
                "resources": {
                  "budget": {
                    "allocated": 50000,
                    "spent": 32500,
                    "breakdown": {
                      "personnel": 25000,
                      "tools": 5000,
                      "infrastructure": 2500
                    }
                  }
                }
              }
            ]
          },
          "backend": {
            "lead": {
              "name": "Mike Johnson",
              "contact": {
                "email": "mike@techcorp.com"
              }
            }
          }
        },
        "metrics": {
          "performance": {
            "velocity": {
              "current": 42,
              "average": 38,
              "trend": {
                "direction": "up",
                "percentage": 10.5
              }
            }
          }
        }
      }
    }
  }
}`,
  },
  {
    name: "Long Keys & Values",
    json: `{
  "user_authentication_and_authorization_settings": {
    "multi_factor_authentication_configuration": {
      "primary_authentication_method_identifier": "time_based_one_time_password_totp",
      "backup_authentication_recovery_codes_enabled": true,
      "session_expiration_timeout_in_milliseconds": 3600000,
      "remember_device_for_trusted_authentication_days": 30
    },
    "role_based_access_control_permissions": {
      "administrator_full_system_access_permission": {
        "permission_identifier_string": "admin.full.access.all.resources",
        "permission_description_for_documentation": "Grants complete administrative access to all system resources, configurations, and user management capabilities",
        "is_permission_currently_active_and_enabled": true
      },
      "standard_user_limited_access_permission": {
        "permission_identifier_string": "user.standard.limited.read.write",
        "permission_description_for_documentation": "Provides standard user access with read and write capabilities for assigned resources only",
        "allowed_resource_types_for_this_permission": [
          "documents_and_files_storage",
          "user_profile_information",
          "team_collaboration_spaces"
        ]
      }
    }
  },
  "application_configuration_and_feature_flags": {
    "experimental_features_beta_testing_enabled": {
      "new_dashboard_redesign_with_analytics": true,
      "artificial_intelligence_powered_suggestions": false,
      "real_time_collaboration_editing_feature": true
    },
    "performance_optimization_settings_configuration": {
      "enable_lazy_loading_for_images_and_media": true,
      "cache_duration_for_static_assets_seconds": 86400,
      "maximum_concurrent_api_requests_allowed": 10,
      "request_timeout_before_automatic_retry_ms": 30000
    }
  },
  "detailed_error_message_with_stack_trace_information": "TypeError: Cannot read properties of undefined (reading 'user_authentication_and_authorization_settings') at AuthenticationService.validateUserCredentials (auth-service.js:142:23) at async LoginController.handleUserLogin (login-controller.js:89:15)"
}`,
  },
];

// Main JSON Builder component
const JsonBuilderPage: React.FC = () => {
  const [jsonInput, setJsonInput] = useState<string>(JSON_EXAMPLES[0].json);
  const [selectedItems, setSelectedItems] = useState<SelectedItem[]>([]);
  const [jsonError, setJsonError] = useState<string | null>(null);

  // Parse JSON input
  const parsedJson = useMemo<JsonObject | null>(() => {
    try {
      const parsed = JSON.parse(jsonInput);
      if (typeof parsed === "object" && parsed !== null) {
        setJsonError(null);
        return parsed as JsonObject;
      }
      setJsonError("Input must be a JSON object or array");
      return null;
    } catch (e) {
      setJsonError(e instanceof Error ? e.message : "Invalid JSON");
      return null;
    }
  }, [jsonInput]);

  const handlePathSelect = useCallback((path: string, value: JsonValue) => {
    setSelectedItems((prev) => {
      // Don't add duplicates
      if (prev.some((item) => item.path === path)) {
        return prev;
      }
      return [...prev, { path, value }];
    });
  }, []);

  const handleRemoveItem = useCallback((path: string) => {
    setSelectedItems((prev) => prev.filter((item) => item.path !== path));
  }, []);

  const handleClearAll = useCallback(() => {
    setSelectedItems([]);
  }, []);

  return (
    <div className="flex flex-col gap-6 p-6 max-w-4xl mx-auto">
      <div>
        <h1 className="comet-title-l mb-2">JsonTreePopover Demo</h1>
        <p className="text-muted-foreground">
          Enter a JSON object below and use the button to explore its structure
          in a popup.
        </p>
      </div>

      {/* Example Buttons */}
      <Card>
        <CardHeader>
          <CardTitle>Example JSON Objects</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex flex-wrap gap-2">
            {JSON_EXAMPLES.map((example) => (
              <Button
                key={example.name}
                variant="outline"
                size="sm"
                onClick={() => {
                  setJsonInput(example.json);
                  setSelectedItems([]);
                }}
              >
                {example.name}
              </Button>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* JSON Input */}
      <Card>
        <CardHeader>
          <CardTitle>JSON Input</CardTitle>
        </CardHeader>
        <CardContent>
          <textarea
            value={jsonInput}
            onChange={(e) => setJsonInput(e.target.value)}
            className="w-full h-64 p-3 border rounded-md bg-background font-mono text-sm resize-none focus:outline-none focus:ring-2 focus:ring-ring"
            placeholder="Enter your JSON object here..."
          />
          {jsonError && (
            <p className="text-destructive text-sm mt-2">{jsonError}</p>
          )}
        </CardContent>
      </Card>

      {/* Popover Trigger */}
      <Card>
        <CardHeader>
          <CardTitle>Explore JSON Structure</CardTitle>
        </CardHeader>
        <CardContent>
          <JsonTreePopover
            data={parsedJson || {}}
            onSelect={handlePathSelect}
            trigger={
              <Button disabled={!parsedJson}>
                <Braces className="size-4 mr-2" />
                Open JSON Tree
              </Button>
            }
          />
        </CardContent>
      </Card>

      {/* Selected Items */}
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle>Selected Paths ({selectedItems.length})</CardTitle>
          {selectedItems.length > 0 && (
            <Button variant="ghost" size="sm" onClick={handleClearAll}>
              Clear all
            </Button>
          )}
        </CardHeader>
        <CardContent>
          {selectedItems.length === 0 ? (
            <p className="text-muted-foreground text-sm">
              Click on items in the JSON tree to select them
            </p>
          ) : (
            <div className="flex flex-col gap-3">
              {selectedItems.map((item) => (
                <div
                  key={item.path}
                  className="flex items-start gap-3 p-3 border rounded-md bg-muted/30"
                >
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 mb-1">
                      <code className="font-mono text-sm text-[var(--color-green)]">
                        {`{{${item.path}}}`}
                      </code>
                    </div>
                    <div className="text-xs text-muted-foreground truncate">
                      Value:{" "}
                      <span className="font-mono">
                        {typeof item.value === "object"
                          ? JSON.stringify(item.value)
                          : String(item.value)}
                      </span>
                    </div>
                  </div>
                  <Button
                    variant="ghost"
                    size="icon-xs"
                    onClick={() => handleRemoveItem(item.path)}
                  >
                    <X className="size-4" />
                  </Button>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
};

export default JsonBuilderPage;
