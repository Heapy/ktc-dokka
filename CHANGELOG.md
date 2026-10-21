# Changelog

## 0.2.0 — 2026-10-21

- Forward nested Kotlin Toolchain stdout and stderr through the parent task logger.
- Drain both streams concurrently and preserve final output, failures and timeouts.
- Keep subprocess log files for model parsing and failure diagnostics.
- Include JVM and KMP documentation support, local dependency resolution, source-set aliases and expect/actual handling merged since 0.1.0.
