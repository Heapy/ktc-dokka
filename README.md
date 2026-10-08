# ktc-dokka

A local Kotlin Toolchain **0.13.0** plugin that generates JVM API documentation with
[Dokka 2.2.0](https://github.com/Kotlin/dokka/releases/tag/v2.2.0).
It runs the standalone Dokka CLI and symbol analysis engine in an isolated JVM.

```sh
./kotlin do dokkaHtml -m example
```

Open `build/tasks/_example_dokkaHtml@dokka/html/index.html`. The command prints the full path.
It also leaves `dokka.json` beside the `html/` directory for inspecting the engine configuration.
Documentation is an explicit command and is not generated during an ordinary build or check.

## Install in another project

From your consumer project, use the [ktc-plugins installer](https://github.com/Heapy/ktc-plugins):

```sh
./ktc-plugins add Heapy/ktc-dokka --branch main --enable-in library
```

Replace `library` with your consumer module path. The root [`ktc-plugin.yaml`](ktc-plugin.yaml)
declares selector `dokka`, module `plugins/dokka`, and `LICENSE`; the installer registers
and enables the plugin automatically. Commit the generated manifest, lockfile, and vendored
sources. The lockfile pins the resolved commit; use `--commit <full-40-character-SHA>`
instead of `--branch main` to select a specific revision. Templates are copied separately.

For manual installation:

Copy `plugins/dokka/` into your project's `plugins/dokka/`, preserving this repository's `LICENSE`.
The plugin contains its own literal dependency pins and needs no local helper module or version catalog.

```yaml
# project.yaml
modules:
  - library
  - plugins/dokka
plugins:
  - //plugins/dokka
```

```yaml
# library/module.yaml
product: jvm/lib
plugins:
  dokka: enabled
```

The root `ktc-plugin.yaml` exposes selector `dokka` for compatible local-plugin installers.
The optional `templates/dokka.module-template.yaml` can be copied separately to enable the plugin
from a shared template. Registration still belongs in `project.yaml`.

## Configuration

```yaml
plugins:
  dokka:
    enabled: true
    jdkVersion: 17
    documentedVisibilities: [public, protected]
    reportUndocumented: true
    failOnWarning: true
    skipDeprecated: false
    suppressInheritedMembers: false
    offlineMode: true
    includes: [./Module.md]
```

`jdkVersion` selects the version of Java API documentation used for links; it does not change
the build JDK. It defaults to 17. Visibility defaults to `[public]`; supported values are `public`,
`protected`, `internal`, `private`, and `package`. Boolean settings default to `false` except
`offlineMode`, which defaults to `true`. Includes default to an empty list and accept Markdown
module/package descriptions in [Dokka's include format](https://kotlinlang.org/docs/dokka-module-and-package-docs.html).

Offline mode prevents Dokka from downloading external documentation package lists, so links to
external APIs may be unavailable. Dependency resolution still needs a populated cache or network.
Use `offlineMode: false` when generating published documentation with external links.

The plugin obtains source directories and the compile classpath from the consumer module.
Files supplied through `includes` participate in task input tracking. HTML from the previous
run is removed before regeneration so removed declarations do not leave stale pages.

## Supported scope and versions

- One HTML site per JVM main compilation. Kotlin KDoc and Java Javadoc are handled by Dokka.
- Tested with a Kotlin `jvm/lib` consumer, Kotlin 2.4.20, and JVM release 17 on Toolchain 0.13.0.
- Dokka CLI, base, and **analysis-kotlin-symbols 2.2.0**, with the upstream CLI's HTML dependencies
  (`kotlinx-html-jvm 0.8.0`, `freemarker 2.3.31`). The descriptor engine is intentionally avoided.
- No aggregated multi-module site, multiplatform source-set hierarchy, custom Dokka plugins,
  or publication/deployment command in this initial adapter.
- [Dokkatoo](https://github.com/adamko-dev/dokkatoo) is a Gradle integration. This repository uses
  Dokka directly so consumers need only the Kotlin Toolchain.

Local plugins are source modules in this Toolchain release; there is no Maven plugin publication.
The Toolchain wrapper is pinned independently from Dokka's engine version.

## Verify and maintain

```sh
./kotlin build
./kotlin check
kotlinr scripts/integration.main.kts
```

Integration installs the plugin into a temporary consumer under `build/`, generates actual HTML,
checks the class and KDoc content and default visibility, then adds undocumented API and verifies
that `reportUndocumented` plus `failOnWarning` fails the command. CI runs it on Linux.
Keep all Dokka artifacts at the same version and run integration after engine updates.

Upstream references: [Dokka CLI configuration and artifact list](https://kotlinlang.org/docs/dokka-cli.html),
[Toolchain's upstream Dokka integration](https://github.com/JetBrains/kotlin-toolchain/tree/v0.13.0/build-sources/dokka).

Licensed under [Apache-2.0](LICENSE).

## Real-project validation: Kotgent

On 2026-10-05 this plugin was copied into a detached Kotgent worktree at
`ac1f35a21af210c0579b3536f326da96dace0ebb`, registered in `project.yaml`, and enabled on its
existing `build-info` and `sqldelight-gen` modules. Both consumers are real
`jvm/amper-plugin` modules, using their existing sources, dependencies, and Kotlin 2.4.20
settings. The run used Toolchain 0.13.0 and Dokka 2.2.0 on macOS ARM64.

The recorded invocation was:

```sh
kotgent mutex run kotlin-build --session <session-id> -- \
  ./kotlin do dokkaHtml -m build-info -m sqldelight-gen
```

Use the current Kotgent session ID when repeating it.
Every invocation held the shared `kotlin-build` mutex and released it before the next probe.

- Generated 15 HTML pages for `build-info` and 23 for `sqldelight-gen`, under
  `build/tasks/_build-info_dokkaHtml@dokka/html/index.html` and
  `build/tasks/_sqldelight-gen_dokkaHtml@dokka/html/index.html`.
- Verified real declarations including `kotlinStringLiteral`, `generatedBuildInfoSource`,
  `SqlDelightEnvironment`, and `generateSqlDelightFiles`; existing KDoc rendered and the
  internal `writeBuildInfo` function was excluded by default.
- Checked 92 and 176 local navigation links respectively, with no missing target files.
- Repeating generation left every HTML hash unchanged. Temporarily making the real
  `kotlinStringLiteral` function internal removed its page, and an edit to existing KDoc
  appeared in regenerated HTML. Another repeat kept that removed page absent. Restoring
  the source brought the public API page back.
- Enabling `reportUndocumented` and `failOnWarning` on `build-info` produced eight warnings
  for its existing public functions and failed with exit code 1. Restoring defaults returned
  to successful generation. All temporary source and strict-setting edits were restored.

No plugin implementation changes were needed. The worktree retains the installed plugin,
consumer configuration, generated sites, and `trial-evidence/` containing command logs,
`validate.py`, HTML hashes, and `result.json`. The local trial lives at
`ktc-plugin-trials/2026-10-05/kotgent-dokka`; these maintainer-local artifacts are not
part of this repository.

The initial sandboxed run failed before Dokka during Toolchain plugin schema setup because
macOS `sysctl` was unavailable; the authorized run outside the sandbox passed. Toolchain's
Zulu JRE 25.0.2 also emitted upstream `sun.misc.Unsafe` deprecation notices from Dokka's
analysis engine. Normal generation reported no unresolved-reference or Dokka error diagnostics.
This validates JVM documentation; Kotgent's Native application modules remain outside this
adapter's supported scope. External documentation links were not validated in offline mode.

## Running verification scripts

The `.main.kts` scripts require JDK 25 and Kotlin 2.4.21+ (`kotlinr` on `PATH`).
Run them with `kotlinr scripts/<name>.main.kts` from the repository root.
The Kotlin Toolchain `./kotlin` command is a separate executable. CI installs the script runner
through [Heapy/setup-main-kts](https://github.com/Heapy/setup-main-kts), pinned to v1.0.1's
commit SHA. The action caches the compiler, Maven dependencies, and compiled scripts between
eligible CI runs. The first script run compiles the script and resolves any pinned Maven
dependencies; later runs reuse the script cache.
