# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

@AGENTS.md

Everything about this codebase — commands, architecture, conventions, versioning, CI — lives in the
import above (which itself pulls in `CONTRIBUTING.md`). Claude Code expands `@`-imports, so both are
already in context; don't re-read them. What follows is only guidance specific to working here
through Claude Code's tooling.

## Searching

`build/` and `.history/` are gitignored but still on disk, and both shadow real source files —
`build/spotless-clean/` mirrors every Kotlin file it formats, and `.history/` holds ~3.6M of
timestamped Markdown copies. The Grep tool skips them (ripgrep honors `.gitignore`); `grep -r` run
through Bash does not, and will report a stale duplicate alongside every real hit. When searching
via Bash, pass `--exclude-dir=build --exclude-dir=.history`, or use Grep instead.

## Editing and verifying

- Pick one extension or theme and verify it with its own `:module:compileDebugKotlin`. Gradle
  configures ~400 modules, so an unscoped invocation is a multi-minute no-op for a one-file change.
- Don't reach for the Android Studio workflow `CONTRIBUTING.md` describes — the Gradle task is the
  headless equivalent and is what belongs in a tool call.
- `spotlessApply` rewrites files on disk. Re-read anything you have edited before further edits, or
  run it last.

## Scope discipline

Each extension is an independent published artifact with its own version code, so a change that
looks repo-wide is usually a change to one module. Before editing a second module, confirm it is
actually in scope — a stray edit there means an unwanted version bump and an unrelated APK rebuild
in CI.
