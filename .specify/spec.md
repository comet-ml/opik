# Spec: Support RPi4/CM4 Emulation Compatibility for Opik

## Background & Goal
Running the default Opik Docker Compose stack on older ARMv8.0-A hardware (e.g. Raspberry Pi 4 or Raspberry Pi CM4) crashes because the pre-built ClickHouse and JVM backend images require `ARMv8.2-A` instruction sets. 

The goal of this issue is to allow users on older ARMv8.0-A hardware to run the Opik stack via QEMU emulation (emulating `linux/amd64`) by exposing parameterizable `platform` settings.

## User Stories
* As a Raspberry Pi 4 user, I want to run the Opik Docker Compose stack using QEMU emulation by passing platform environment variables so that ClickHouse and the JVM backend don't crash with `Illegal instruction` errors.
* As an x86_64 or Apple Silicon user, I want the default setup to remain unchanged so that my container startup is fully native and fast.

## Acceptance Criteria
- [x] All backend services (`clickhouse`, `backend`, `python-backend`, `guardrails-backend`, `demo-data-generator`) must accept a `platform:` parameter driven by environment variables.
- [x] The default platform value must resolve to an empty string (`""`) so native execution is completely unaffected.
- [x] Instructions on registering the QEMU binfmt handlers on bare metal Linux hosts must be clearly documented.
- [x] Documentation must warn users about the high CPU/memory usage of emulated ClickHouse/JVM on 4GB RPi4 hardware.

## Exclusions & Constraints
* Full native ARMv8.0-A builds are out of scope (requires compiling ClickHouse from source). Emulation is a best-effort workaround.
