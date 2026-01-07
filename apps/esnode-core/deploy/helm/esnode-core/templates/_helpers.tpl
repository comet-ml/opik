{{- define "esnode-core.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "esnode-core.fullname" -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- $fullname := printf "%s-%s" .Release.Name $name -}}
{{- $fullname | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "esnode-core.labels" -}}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
app.kubernetes.io/name: {{ include "esnode-core.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "esnode-core.selectorLabels" -}}
app.kubernetes.io/name: {{ include "esnode-core.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
