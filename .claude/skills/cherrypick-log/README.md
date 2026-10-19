# Upstream cherry-pick log

Tracks which upstream commits still need to be cherry-picked into this repo (`yuzono/anime-extensions`).

- **Output:** [`cherrypick_log.md`](../../../cherrypick_log.md) in the repo root, a Markdown table with one row per upstream commit.
- **Generator:** `update_cherrypick_log.py`. This is deterministic Python 3.9+ that needs only the standard library and `git`.
- **AI review:** `SKILL.md`, a Claude Code skill (`/cherrypick-log`). It runs the generator, sorts the
  [repo-wide rows](#repo-wide-commits), and resolves the rows the generator couldn't decide (`?`) by comparing code.
- **Settings:** `config.json`.

The upstreams:

| Name | Branch | GitHub | What this repo takes from it |
|------|--------|--------|------------------------------|
| `yuzono` (main) | `yuzono/main` | `yuzono/tachiyomi-extensions` | The manga extensions repo, a copy of `keiyoushi/extensions-source` with its own commit hashes. Shared code only: `core/`, `lib/`, `gradle/`, `.github/`, `CONTRIBUTING.md`, build files. Its commits that only change `src/` or `lib-multisrc/` are left out unless they are [repo-wide](#repo-wide-commits) |
| `@secozzi` (additional) | `@secozzi/master` | `Secozzi/aniyomi-extensions` | Anime extensions this repo also ships (`src/all/jellyfin`, `src/all/stremio`, `src/all/torbox`). Every commit is listed |

The script decides everything that has a mechanical answer: the list itself, its order, the filters, matching
by hash or PR, and keeping manual marks. The AI handles only the judgment calls, and it writes through the
script's `mark` command, so the table can't be corrupted.

## Quick start

```bash
# 1. Fetch upstreams and regenerate cherrypick_log.md (safe to run any time)
python3 .claude/skills/cherrypick-log/update_cherrypick_log.py

# 2. In Claude Code, let the AI sort the repo-wide rows and review the uncertain rows
/cherrypick-log review the log

# 3. Pick commits, then mark them yourself (or edit the Status / Notes cells by hand)
python3 .claude/skills/cherrypick-log/update_cherrypick_log.py mark 1a2b3c4d --status O
python3 .claude/skills/cherrypick-log/update_cherrypick_log.py mark 5e6f7a8b --status X --note "manga-only change"
```

The upstream remotes named in `config.json` must exist:
`git remote add yuzono https://github.com/yuzono/tachiyomi-extensions` and
`git remote add @secozzi https://github.com/Secozzi/aniyomi-extensions`. The script fetches them itself
unless you pass `--no-fetch`.

## Commands

| Command | What it does |
|---------|--------------|
| `update` (default) | Fetch upstreams, rebuild the list, keep manual marks and Notes. Options: `--no-fetch`, `--dry-run`, `--verbose` |
| `uncertain` | Print `?` rows that haven't been AI-reviewed yet. Options: `--include-empty`, `--include-reviewed`, `--repo NAME`, `--grep REGEX` (title), `--details REGEX` (Details, e.g. `'^repo-wide:'`), `--limit N`, `--json` |
| `mark HASH` | Set one row's status (`--status O\|?\|X\|none`) and/or append a note (`--note`, `--replace-note`). Refuses to change `O`/`X` rows unless you pass `--force` |

All commands accept `--config PATH` (default: `config.json` next to the script), `--since YYYY-MM-DD`
(overrides the config's start date for this run, e.g. `... --since 2024-01-01 update`) and `--target BRANCH` (overrides
`target_branch` for this run, e.g. `... --target merge update` to check a branch that is not merged into `master` yet).
They go before the command. Quote `?` in zsh (`--status '?'`).

## Columns and statuses

| Column | Meaning |
|--------|---------|
| Status | `O` cherry-picked into the target branch · `?` maybe, needs review (including commits that cancel each other out, see [Revert chains](#revert-chains-flagged-)) · empty (written as one space so the raw table stays aligned) = not found · `X` won't pick (set by hand, or a repo-wide row the AI review found not to apply, see [Omitted commits](#omitted-commits-x)) |
| Commit | Short hash, linked to the full GitHub commit. The script reads the full hash back from this link, so don't edit it |
| Upstream | `yuzono`, `@secozzi`, or `↳ @secozzi` for @secozzi's copy of a yuzono commit (Details names it: `pick of yuzono …`) |
| Date | Committer date, i.e. when the commit landed on that upstream branch (ISO) |
| Title / Author | First line of the commit message and its author |
| Details | Generated on every run: why the row is listed (`repo-wide: …`), how it was paired and how its status was found (see below) |
| Notes | Yours. Kept across runs. AI notes start with `AI checked <date>:` |

Only **Status** and **Notes** are yours to edit. Every other cell, the order of the rows and the header are rewritten on each run.

## Rules

### Which commits are listed

- Non-merge commits on each upstream branch whose committer date is on or after `since`.
- A commit is excluded if its author, committer or any `Co-authored-by` matches `exclude_identity_regex` (Renovate).
  A @secozzi copy of an excluded yuzono commit is excluded too.
- A commit is excluded if every file it changes is under one of its upstream's `exclude_only_paths`: here yuzono's
  `src/` and `lib-multisrc/`, its manga extensions and themes. Renames count as a delete plus an add, so a file moved
  out of those paths keeps the commit. A commit that changes no files is kept. This is set per upstream: a secondary
  copy of an excluded primary commit is judged by its own files and its own upstream's setting.
- Such a commit is kept anyway when it is [repo-wide](#repo-wide-commits).

### Repo-wide commits

One edit made across many sources can be a change this repo needs too, even though it only touches yuzono's
`src/` and `lib-multisrc/` (`refactor UrlActivities`, `Use our ratelimit implementation`, `Remove usage of okhttp
internal variables`). So a commit dropped by `exclude_only_paths` is kept when it touches at least the upstream's
`repo_wide_min_modules` modules (here 5). Its Details start with `repo-wide: N modules, only under src, lib-multisrc`.

- A file's module is the directory before its first nested `src/`, `res/` or `assets/` folder (Gradle / Android
  layout), else the file's directory. So `src/en/foo/src/…/Foo.kt` and `src/en/foo/build.gradle` are both `src/en/foo`,
  and `lib-multisrc/madara/src/…` is `lib-multisrc/madara`.
- Most commits that touch 5+ sources are bulk changes to yuzono's own sources: dead sources removed, domains updated,
  a theme migrated. The AI review (SKILL.md section 2) sorts them. It marks the ones that are not repo-wide `X`
  (note `AI checked <date>: omitted - not repo-wide (…)`), and reviews the others like any row. Since 2025-01-01 that
  is about 200 rows for the first pass. Later updates add about 10 a month, more during migration waves (44 in
  2026-07, the ext-lib 1.6 migration).
- Raise `repo_wide_min_modules` for fewer rows to sort, at the risk of missing small refactors. At 10 there are 87 rows,
  but `Remove usage of okhttp internal variables` (5 modules) is missed. `0` (or no key) turns this off.

### Pairing @secozzi commits with yuzono commits

`main_upstream` in `config.json` is the **primary** one (yuzono here). Each entry of `additional_upstreams`
is a **secondary** (@secozzi here), and each secondary is paired with the primary on its own.
A secondary commit is a copy of a primary commit if one of these applies, checked in this order (shown as Details `pick of yuzono … [method]`):

1. `marker`: its message has `cherry picked from commit <primary hash>`.
2. `pr+title`: its title has the same `(#N)` as a primary commit, and the titles are similar.
3. `title+author`: the normalized title is identical, matches only one primary commit, and has the same author. The date window is one-sided: the secondary copy may land
   any time after the primary commit, but at most 30 days before it (`PAIR_DATE_SLACK_DAYS`; a copy can be picked from
   the PR before upstream merges it).

yuzono and @secozzi share no commits so far, so every @secozzi row is currently an own commit. The rules only come into
play if @secozzi copies a yuzono commit.

If a @secozzi commit copies a yuzono commit dated before `since`, it is listed as a @secozzi-only commit, with `(before <since>)` in Details.
If it copies a yuzono commit dropped by `exclude_only_paths` (and is not dropped itself), Details say
`(not listed: only changes under <paths>)` instead.

### Order (newest first)

All rows are sorted by committer time (when the commit landed on its upstream branch), newest first, whichever
upstream they come from. So yuzono and @secozzi commits are interleaved by time.

- Commits with the same committer time keep their branch order, yuzono before @secozzi. This matters for yuzono:
  about 90% of its commits share their timestamp with other commits.
- Within one branch, time order and branch order agree (committer time never goes back along either branch), so the
  sort never reorders one upstream's own commits.
- A @secozzi copy of a yuzono commit is listed at its own time, not next to its original. Its Details name the
  original (`pick of yuzono …`), and the two rows share one status.

### Status detection (shown as Details ``master `abc` [method]``)

A yuzono commit and its @secozzi copies share one status, and evidence for any of them counts for all of them.

| Method | Status | Meaning |
|--------|--------|---------|
| `in history as …` | O | The upstream commit is reachable from the target branch |
| `hash of …` | O | A target commit's message has `cherry picked from commit <hash>` (or a `github.com/…/commit/<hash>` link) |
| `pr+title owner/repo#N` | O | A target commit line has `owner/repo#N` (or `…/pull/N`) and a similar title. Refs right after `Fixes`, `Closes`, `See` and similar words are ignored |
| `title+author` | O | A target commit line has the same normalized title and the same author |
| `pr only owner/repo#N` | ? | The PR is referenced but the title differs |
| `title` | ? | Same title, different author |

Title matches only count if the title is unique among the listed upstream commits, has at least 3 words,
and the target commit is at most 180 days older than the upstream commit.
The title is normalized before comparing: lowercased, conventional-commit prefix removed, `(#N)` refs and quotes removed.

### Revert chains (flagged `?`)

On every `update`, the script flags commits that cancel each other out as `?`, with a
`Cancels out (script): reverts / reverted by <hash> [was: …]` note. You (or the AI review) decide what to do with them,
e.g. mark both `X`.

- They are found by following `This reverts commit <hash>` links between listed commits. A yuzono commit and its
  @secozzi copies count as one.
- A chain is only flagged when it is linear, every row in it is still empty or `?` (this repo has none of them), and no
  row has an AI verdict.
- An even-length chain cancels out completely, so every commit in it is flagged. In an odd-length chain (`C`,
  `Revert C`, `Revert "Revert C"`) the oldest commit carries the net change and is not flagged.
- If this repo already has one side of a revert pair, the pair is not flagged, because the other side may still matter.

Older versions marked these rows `X` with an `Omitted (script):` note. Those rows are turned into `?` flags on the next run.

### Omitted commits (X)

`X` means "won't pick". Apart from your own marks, the AI skill marks one kind of row `X`: a [repo-wide](#repo-wide-commits)
row that turns out to be a bulk change to yuzono's own manga sources, not a change this repo needs. Its note is
`AI checked <date>: omitted - not repo-wide (<what it changes>)`. See SKILL.md section 2.

### Rerunning

- Rows with a status other than empty or `?` (e.g. `O`, a manual `X`) are **never changed**.
- A revert-chain flag (a `?` with a `Cancels out (script):` note, or an older `X` with `Omitted (script):`) is released on
  every run, back to the status recorded in its `[was: …]` part, and the chains are recomputed. So when a later revert
  extends a chain, the odd/even rule applies to the whole chain again. A flagged row is left alone once it has an
  `AI checked` note, or once you change its status (e.g. to `X`). Only the stale script note is removed then.
- Empty and `?` rows are checked again. An empty or `?` row whose Notes contain `AI checked` keeps that AI verdict
  unless new `O` evidence appears.
- Notes are always kept, and new upstream commits are added in their place in the order.
- If a commit is no longer reachable from its upstream branch (force-push), it moves to a **Gone from upstream** section
  and keeps its status and notes. Delete it by hand once you no longer need it.
- Rows dated **before `since`** that are already in the file stay at the end of the list (also sorted by time), with
  their cells unchanged. They come from a one-off pass over older history, which this repo hasn't had so far. An empty or `?` row there
  only changes to `O`, when new `O` evidence appears. You may delete such rows by hand; they are not re-added, and the
  summary simply counts the rows that are in the file.
- If a commit is still upstream and dated on or after `since`, but no longer matches the filters (e.g. you changed
  `exclude_identity_regex`, `exclude_only_paths` or `repo_wide_min_modules`), it is dropped. That run checks each
  dropped row with one `git` call, so it can take a minute when thousands of rows go.

## Config (`config.json`)

| Key | Meaning |
|-----|---------|
| `target_branch` | Branch to check for already-picked commits (`master`). The local branch is read, so keep it up to date; `--target` overrides it |
| `since` | First commit date to include, `YYYY-MM-DD` (committer date). Required, and specific to each repo (here: `2025-01-01`); `--since` overrides it |
| `output` | Output Markdown path, relative to the repo root (or absolute) |
| `exclude_identity_regex` | Case-insensitive regex matched against author, committer and co-authors (here: `renovate`). Empty = exclude nothing |
| `main_upstream` | `{name, remote, branch, github}` of the main upstream, plus the optional keys below. Copies in the other upstreams are paired with its commits. Required |
| `additional_upstreams` | List of one or more `{name, remote, branch, github}`, each with the optional keys below. Their commits are paired with the main upstream's. Names must be unique |
| `exclude_only_paths` | Per upstream, optional. List of repo-relative directories or files (leading / trailing `/` ignored). That upstream's commits that only change files under them are not listed. Here: `["src/", "lib-multisrc/"]` on yuzono only, because @secozzi's `src/` holds anime extensions this repo ships |
| `repo_wide_min_modules` | Per upstream, optional, whole number. A commit dropped by `exclude_only_paths` is kept as [repo-wide](#repo-wide-commits) when it touches at least this many modules. `0` or absent = off. Here: `5` on yuzono |

Tuning constants (similarity threshold, date limits, minimum title words, module folders) are at the top of the script.

## Using this in another project

1. Copy the whole `.claude/skills/cherrypick-log/` folder.
2. Edit `config.json`: the `main_upstream`, one or more `additional_upstreams` (remote, branch, GitHub `owner/repo`),
   target branch, start date (`since`, specific to that repo), excluded identities, excluded paths, the repo-wide
   threshold and output.
3. Add the remotes (`git remote add <name> <url>`) and run the script.
4. Search `README.md` and `SKILL.md` for project-specific wording (yuzono, @secozzi, keiyoushi, manga / anime,
   `src/` and `lib-multisrc/`, the extension names, `AGENTS.md`) and adjust it.

The only hard requirement is that the fork records upstream picks with `git cherry-pick -x`
(`cherry picked from commit …`) or `owner/repo#N` references in commit titles.

## Tests

```bash
python3 -m unittest discover -s .claude/skills/cherrypick-log -p 'test_*.py'
```

The tests in `test_update_cherrypick_log.py` cover revert-chain flags (including when a chain gets longer and the older `X` omissions), releasing
the script's own flags (including notes with escaped characters and user text), manual `X` pins, the rerun status rules
(`carry_over`: manual status kept, AI verdict kept unless `O` evidence appears), and the path filter (path matching,
modules, config checks, and `Builder.build` dropping path-only commits, keeping repo-wide ones and keeping a copy of a
dropped one), and the time order (reading committer time, interleaving upstreams, ties, copies at their own time).
They need no git repository.

## Limitations

- These are heuristics. A reworded, split or hand-ported change can show as empty or `?`. That's what the AI review is for.
- yuzono's `(#N)` refs are `keiyoushi/extensions-source` PR numbers, and its hashes differ from keiyoushi's. A pick that
  `master` records as `keiyoushi/extensions-source#N`, or with a keiyoushi hash, is only found through its title, so it can
  show `?` (e.g. `Docs: URL intent filter (#14330)`). Most such picks are still found through a yuzono hash or the title.
- Repo-wide is a module count. A refactor that touches fewer than `repo_wide_min_modules` modules under the excluded
  paths is dropped (e.g. `fix gradle lint errors`, 3 modules), and many bulk source changes are kept for the AI to sort.
- Reverts are only linked through git's default `This reverts commit <hash>` message, and only to commits that are in
  the list. A reworded revert, or a revert of a commit outside the list (before `since`, excluded, or deleted by hand),
  stays an ordinary row.
- Revert chains are only flagged when they are linear, this repo has none of their commits and no row in them has an AI
  verdict (`AI checked`). Branching chains (two reverts of one commit) and pairs where this repo already has one side
  are not flagged.
- One @secozzi commit that squashes several yuzono commits is a single row that shares its status with all of them
  (its Details name them).
