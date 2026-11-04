---
name: cherrypick-log
description: Update and review cherrypick_log.md, the todo list of upstream commits not yet cherry-picked into this repo's master. The upstreams are set in config.json (main yuzono/main, the manga extensions repo; additional @secozzi/master, Secozzi's anime extensions). Use when asked to refresh/update the cherry-pick log, list new upstream commits, resolve '?' (uncertain) entries by checking whether an upstream change is already in master, or sort the repo-wide yuzono commits that are kept although they only change src/ or lib-multisrc/.
---

# Cherry-pick log: update + AI review

Everything for this tool lives in this folder (`.claude/skills/cherrypick-log/`):

| File | Role |
|------|------|
| `SKILL.md` | This file: the procedure an AI agent follows |
| `update_cherrypick_log.py` | Deterministic generator: fetch, filter, pair, order, detect, keep manual marks |
| `config.json` | Main upstream + additional upstreams, target branch, start date (`since`, per repo), output path, excluded identities, and per upstream the excluded paths and the repo-wide threshold |
| `README.md` | Human documentation: rules, columns, commands, porting to another project |

The upstreams:

- **yuzono** (`yuzono/main`, GitHub `yuzono/tachiyomi-extensions`): the manga extensions repo, a copy of
  `keiyoushi/extensions-source` with its own commit hashes. Its `(#N)` title refs are keiyoushi PR numbers. This repo
  takes its shared code (`core/`, `lib/`, `gradle/`, `.github/`, `CONTRIBUTING.md`, build files). Its commits that
  only change `src/` or `lib-multisrc/` (manga sources and themes) are left out, unless they touch at least
  `repo_wide_min_modules` modules (section 2).
- **@secozzi** (`@secozzi/master`, GitHub `Secozzi/aniyomi-extensions`): anime extensions this repo also ships
  (`src/all/jellyfin`, `src/all/stremio`, `src/all/torbox`). All of its commits are listed.

The output is `cherrypick_log.md` in the repo root. **The script is the source of truth** for
the list, its order and statuses found by hash or PR. It also flags commits that cancel each other out:
a commit and its revert (or a longer revert chain) that this repo has none of get **?** with a
`Cancels out (script):` note on every `update`. These flags are recomputed on every run until the row gets
an `AI checked` note or a status set by hand (see README "Revert chains"). The AI's job is narrow:
- sort the repo-wide rows: keep the real repo-wide changes, mark the rest X (section 2);
- decide the rows the script could not (`?`, and empty rows only if the user asks) by comparing the code itself (sections 3-5).

## 1. Update the list

```bash
P=.claude/skills/cherrypick-log/update_cherrypick_log.py
python3 $P                     # fetch + regenerate
python3 $P update --no-fetch   # offline
python3 $P --target merge update   # check against another branch than config.json's target_branch (master)
```

When the user names a branch (e.g. "on branch merge"), pass `--target <branch>` to every `update` of the run. The
header line `checked against` shows which branch the statuses came from. `O` and `X` stay when a later run checks
`master` again.

Report the summary line (commits / new / status changes / gone), the summary table at the top
of `cherrypick_log.md`, and how many `repo-wide:` rows are not reviewed yet (the row count of the section 2 listing).
If the user only asked to update, stop here.

## 2. Sort the repo-wide rows

A yuzono commit that only changes `src/` / `lib-multisrc/` is kept when it touches at least `repo_wide_min_modules`
modules (an extension `src/<lang>/<name>` or a theme `lib-multisrc/<theme>`). Its Details start with
`repo-wide: N modules, only under src, lib-multisrc`. One edit made across many sources can be a repo-wide change that
this repo needs too (`refactor UrlActivities`, `Use our ratelimit implementation`, `Remove usage of okhttp internal
variables`), but most such commits are bulk changes to yuzono's own manga sources. Run this step whenever you review the
log, before section 3, and when the user asks for it.

1. List the rows not reviewed yet:
   ```bash
   python3 $P uncertain --json --include-empty --details '^repo-wide:'
   ```
   The title often settles it. Otherwise look at `git show --stat --format= H` and one or two of the per-source diffs.
2. Decide whether it is **repo-wide**: the same change made to every source it applies to, for a reason that applies
   to this repo's extensions too.
   - **Repo-wide:** moving to a shared helper or API (`keiyoushi.utils` / `keiyoushi.network` helpers, rate limit,
     preferences, `network.client` / `cloudflareClient`), removing an unsafe or deprecated usage (OkHttp internals,
     regex named groups), Kotlin or lint cleanups (`TimeUnit` to `Duration`), or a `build.gradle.kts` convention that this
     repo's extensions also use (check with `git grep -n '<field>' master -- 'src/*/*/build.gradle.kts' | head`).
   - **Not repo-wide:** changes to particular manga sources or themes, however many: removing dead or broken sources,
     adding sources, domain or package updates, moving sources between themes, migrating a theme or a batch of
     sources to a new ext-lib or build DSL. The shared part of such a migration (`gradle/`, `core/`, `lib/`) is its own
     listed commit, so review that commit instead.
   - When unsure, treat it as repo-wide and say so in the note.
3. Record it:
   - Not repo-wide:
     `python3 $P mark H --status X --note "AI checked YYYY-MM-DD: omitted - not repo-wide (<what it changes>)"`.
   - Repo-wide: review it like any other row (section 4) and record the verdict (section 5) with a note that starts
     `AI checked YYYY-MM-DD: repo-wide - `.

## 3. Get the rows to review

```bash
python3 $P uncertain --json                      # '?' rows not yet AI-reviewed
python3 $P uncertain --json --repo yuzono --limit 20
python3 $P uncertain --json --include-empty --repo yuzono --limit 30   # only when the user asks
```

Rows whose Notes already contain `AI checked` are skipped (use `--include-reviewed` to redo them).
Rows are newest first by commit time, yuzono and @secozzi interleaved. A `↳ @secozzi` row is @secozzi's copy of the
yuzono commit named in its Details (`pick of yuzono …`), listed at its own time. Both share one status, so
review the yuzono commit once and mark both rows. The two upstreams share no commits so far, so such rows are rare.

## 4. Review each row

A row with a `Cancels out (script):` note is part of a revert pair or chain: review all of its rows together
(the hashes in the notes) and give them consistent verdicts. Leave them `?` with an `AI checked` note when the
pair cancels out and this repo has neither side, since dropping the pair (X) is the maintainer's decision.

For upstream commit `H` (already fetched locally):

1. `git show --stat --format=fuller H` and then the diff (`git show H -- <path>`) for the main files.
2. If Details names target commits (``master `abc` [title]``, `[pr only …]`), inspect them:
   `git show --stat abc`, then compare the hunks with the upstream change.
   - `hash of … (partial: N of M files not picked)`: commits here cite `H`, but left out some of its files (README
     "Partial picks"). Compare `git show --name-status --format= H` with the files of every commit citing it
     (`git log master --format=%h -i --grep=<first 7 characters of H>`: a citation can use a short hash, and the
     prefix can also match another commit, so check each hit), then check each left-out file as in step 3. **O**
     when the left-out changes reached `master` another way, or don't apply here (manga-only code, a dependency this
     repo doesn't use; say which in the note). Otherwise keep **?** with a note naming what is missing.
3. Otherwise, search `master` for the change itself:
   - A yuzono `(#N)` is a keiyoushi PR. `master` often records picks as `keiyoushi/extensions-source#N`, sometimes
     inside a squashed commit: `git log master --oneline --grep='extensions-source#<N>'`.
   - `git grep -n -F '<distinctive added line>' master -- <path>`. Shared code keeps yuzono's paths (`core/`, `lib/`,
     `gradle/`, `.github/`), but code that deals with sources is adapted to anime: `SManga` / `SChapter` /
     `MangasPage` / `HttpSource` become `SAnime` / `SEpisode` / `AnimesPage` / `AnimeHttpSource`, chapters become
     episodes, and the package `eu.kanade.tachiyomi.extension` becomes `eu.kanade.tachiyomi.animeextension`.
     Search for the adapted line when the literal one is not there.
   - A @secozzi change lives under the same extension folder here (`src/all/jellyfin`, `src/all/stremio`,
     `src/all/torbox`).
   - `git log master --oneline -S'<distinctive snippet>' --since=<upstream date - 6 months>`.
   - For removals, check that the removed code is gone from `master`.
4. Decide:
   - **O**: the change is in `master` (cherry-picked, squashed into another commit, or re-implemented).
   - **?**: partly present, conflicts with a rework in this repo, or you can't tell.
   - **empty**: not present.
   Never set **X** here. That is the maintainer's "won't pick" decision. The only exception is
   section 2 (repo-wide rows that turn out not to be repo-wide).

## 5. Record the verdict (never hand-edit the table)

```bash
python3 $P mark <hash> --status O    --note "AI checked YYYY-MM-DD: present in master <hash or file> - <why>"
python3 $P mark <hash> --status '?'  --note "AI checked YYYY-MM-DD: partial - <what is missing>"
python3 $P mark <hash> --status none --note "AI checked YYYY-MM-DD: not found - <what you searched>"
```

- Every AI note **must start with `AI checked <date>:`**. The script uses this marker to keep an AI
  "not found" verdict and to leave reviewed rows out of `uncertain`.
- `mark` refuses to change `O` / `X` rows. Do not pass `--force` unless the user explicitly asks.
- Keep notes to one line. Mention the evidence (target commit hash, file, or search done).

## 6. Finish

1. `python3 $P update --no-fetch` to make sure the file still parses and to refresh the summary. This also
   applies the script's revert-chain flags.
2. Report to the user how many rows you reviewed, how many became O / stayed ? / empty / X (and how many
   repo-wide rows were kept or marked X), and any rows that need a human decision (big reworks, likely X candidates).
3. Do not commit unless asked. If asked, follow the git rules in `AGENTS.md` and `CLAUDE.md` (feature branch, never `master`).

## Large batches

For more than ~20 rows, split them into batches (e.g. by `--repo` and `--limit`) and give each batch
to a subagent, which only *reports* `hash → verdict + one-line evidence`. The main agent then runs all
`mark` commands, so only one process writes the file. The first section 2 pass has about 200 rows; batch it the same way.

## Changing the rules

Matching and ordering rules are in `update_cherrypick_log.py`: the module docstring, the constants at
the top, `Builder.path_excluded` (excluded paths and repo-wide rows), `module_of`, and `Builder.pair` /
`Builder.detect`. They are explained in `README.md`. Update both when you change behavior.
