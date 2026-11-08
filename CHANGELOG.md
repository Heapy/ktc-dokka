# Changelog

## 0.5.0 — 2026-11-08

- Upgrade Dokka CLI, base and K2 symbol analysis from 2.2.0 to 2.3.0, including upstream documentation fixes and dependency security updates.
- Align the standalone HTML dependencies with Dokka 2.3.0: kotlinx-html-jvm 0.9.1 and FreeMarker 2.3.35.
- Add `suppressAnnotatedWith` configuration for excluding declarations by fully qualified annotation name across all source sets. The default empty list preserves existing visibility.
- Use distinct Wasm JS and Wasm WASI analysis platforms, with common analysis for sources shared between them.
- Cover Wasm-only KMP documentation, annotation filtering, strict undocumented-API warnings, KDoc links and removal of stale pages from HTML/Javadoc archives.
- Dokka now uses revised KDoc link resolution and diagnostics; consumers using `failOnWarning` should review new KDoc warnings when upgrading. Kotlin Toolchain remains pinned to 0.13.0.

## 0.4.3 — 2026-11-03

- Isolate Kotlin Toolchain 0.13.0 state parsing and nested invocation helpers in `ToolchainModel.kt`.
- Normalize the task output path and reject unsupported layouts before deriving the shared model-build directory.
- Share Windows detection between Dokka and nested Toolchain invocations.
- Add tests for normalized output paths and invalid task layouts.

## 0.4.2 — 2026-11-03

- Reuse nested model compilation in `<outer-build>/dokka-model-build` across documentation modules, while retaining KTC incremental invalidation and isolation between outer build directories.
- Apply `timeoutSeconds` to the Dokka process as well as nested Toolchain commands, forwarding both output streams and terminating timed-out processes.
- Cover sequential and concurrent KMP cache reuse, dependency changes, deleted API pages, custom build directories with spaces, and generated source ownership in integration tests.
- Preserve v0.4.1 generated common/shared sources and v0.4.0 Android source-set and hashed-state lookup support. The model reader still requires Kotlin Toolchain 0.13.0.

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
