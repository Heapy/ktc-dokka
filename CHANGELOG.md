# Changelog

## 0.4.1 — 2026-11-03

- Restore generated public API in metadata-compiled common and shared KMP source sets, including plugin outputs outside the default source directories.
- Keep those generated roots in their owning source set when leaf platforms are compiled, without duplicating them as platform-specific API.
- Add integration coverage for generated-only common/shared API and a subsequent JVM compilation, checking HTML, archive contents, and source-set ownership.
- Update kotlinx-serialization-json to 1.12.0.

## 0.2.0 — 2026-10-21

- Forward nested Kotlin Toolchain stdout and stderr through the parent task logger.
- Drain both streams concurrently and preserve final output, failures and timeouts.
- Keep subprocess log files for model parsing and failure diagnostics.
- Include JVM and KMP documentation support, local dependency resolution, source-set aliases and expect/actual handling merged since 0.1.0.
