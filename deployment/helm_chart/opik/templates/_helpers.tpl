{{/*
Expand the name of the chart.
*/}}
{{- define "opik.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "opik.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "opik.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "opik.labels" -}}
{{ include "opik.selectorLabels" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "opik.clickhouse.labels" -}}
{{ include "opik.clickhouse.selectorLabels" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}
{{/*
Selector labels
*/}}
{{- define "opik.selectorLabels" -}}
app.kubernetes.io/name: {{ include "opik.name" $ }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
{{- define "opik.clickhouse.selectorLabels" -}}
app.kubernetes.io/name: {{ include "opik.name" $ }}
app.kubernetes.io/instance: {{ .Release.Name }}
component: clickhouse
# app.kubernetes.io/component: clickhouse
{{- end }}

{{/*
{{- end }}
{{/*
Create the name of the service account to use
*/}}
{{- define "opik.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "opik.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}
{{/*
Create the name of the service account to use
*/}}
{{- define "clickhouse.serviceAccountName" -}}
{{- if .Values.clickhouse.serviceAccount.create }}
{{- default (include "opik.fullname" .) .Values.clickhouse.serviceAccount.name }}
{{- else }}
{{- default "default" (include "opik.serviceAccountName" .) }}
{{- end }}
{{- end }}
{{/*
Create the name of the service account to use
*/}}
{{- define "clickhouse.backup.serviceAccountName" -}}
{{- if .Values.clickhouse.backup.serviceAccount.create }}
{{- default (include "clickhouse.serviceAccountName" .) .Values.clickhouse.backup.serviceAccount.name }}
{{- else }}
{{- default "default" (include "clickhouse.serviceAccountName" .) }}
{{- end }}
{{- end }}
{{/*
Create the name of the service account to use
*/}}
{{- define "service.serviceAccountName" -}}
{{- if .serviceAccount.create }}
{{- default ( cat .serviceName "-sa" | nospace ) .serviceAccount.name }}
{{- else }}
{{- default "default" .serviceAccount.name }}
{{- end }}
{{- end }}
