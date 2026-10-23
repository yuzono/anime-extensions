---
name: cherry-pick
description: Cherry-pick one or more upstream commits (yuzono/main, the manga extensions repo, or @secozzi/master) into the current branch, adapt them for this anime repo following AGENTS.md "Porting upstream changes", refuse (and argue strongly against) any pick that would break the Anikku app unless the user explicitly confirms, mark them O in cherrypick_log.md in the same commit, verify the result, and report later upstream commits that fix bugs in the picked commit, any bug that has no upstream fix (without fixing it), and whether the pick belongs in the current PR, a separate PR, or a PR that must wait for earlier commits to be published. Use when asked to cherry-pick, pick, port or bring over an upstream commit by hash.
argument-hint: <upstream-commit> [<upstream-commit>...]
---

# Cherry-pick an upstream commit

Input: one or more upstream commit hashes (`$ARGUMENTS`). Pick several in the order given, taking each through every
step below before starting the next, and give one combined report at the end.

The adaptation rules live in AGENTS.md, section **Porting upstream changes**. They win over anything here; this file
is the procedure. The upstreams and their remotes are in `.claude/skills/cherrypick-log/config.json`:
`yuzono` (`yuzono/main`, GitHub `yuzono/tachiyomi-extensions`, a copy of `keiyoushi/extensions-source` with its own
hashes) and `@secozzi` (`@secozzi/master`, GitHub `Secozzi/aniyomi-extensions`).

Never pick onto `master`, never push, and never fix a bug you find while picking: report it (step 7) and let the user
decide. Write temporary files (messages, test data, worktrees) to the scratchpad directory, not the repo. When you
name a commit to the user, give its short hash and then its subject in parentheses, shortened and without a trailing
`(#N)` (AGENTS.md "Referring to commits").

## 0. Preflight

1. The current branch is not `master`. Otherwise stop and ask.
2. Resolve the hash: `git rev-parse --verify '<hash>^{commit}'`, then `git branch -r --contains <hash>` must list
   `yuzono/main` or `@secozzi/master`. If it isn't found, `git fetch yuzono` / `git fetch @secozzi` and retry. A
   keiyoushi hash is not in either branch: find yuzono's copy with `git log yuzono/main --format=%H --grep=<hash>`.
3. Look up its row in the working tree's `cherrypick_log.md` (`grep "commit/<full-hash>" cherrypick_log.md`), so
   notes that aren't committed yet count too.
   - `O`: already picked. Stop and say where (`git log --oneline -i --grep=<first 7 characters of the hash>`; a
     citation can use a short hash, and a 7-character prefix can also match another commit, so check each hit).
   - `?` with `partial:` in its Details: part of it is already here (same `git log`). Say which part, and pick only
     the rest.
   - Any other `?`: the script matched only the title or PR number of the commit its Details names, or step 4 marked
     a partial pick by hand (a `Partial …` note). Compare this commit's shared files with ours
     (`git diff <hash> HEAD -- <files>`; lines it adds that show as removed aren't here yet), say what is already
     here, and ask before picking.
   - `X`: show the note and ask before picking.
   - No row: say so (the log's filters drop yuzono commits that only change `src/` or `lib-multisrc/`) and go on.
4. Read the row's **Notes** whatever its status. They can hold a decision made earlier for this commit, such as
   "fold in `mtime=0` from …" or "don't pick just for …". Follow it as part of this pick and say that you did. If a
   note contradicts the request, ask.
5. Not already on this branch: `git log --oneline --grep=<full-hash> master..HEAD` is empty.
6. The pick commit holds only this upstream change (AGENTS.md, Porting upstream changes). If tracked files already
   have uncommitted changes, staged or not, list them and stash them with
   `git stash push -m "unrelated to <short-hash>"`. Pop the stash after step 4's commit, or as soon as you stop
   without committing (a "don't pick" decision, or after `git cherry-pick --abort` if the pick is in progress, since
   `git stash pop` fails while it has unresolved conflicts). Untracked files can stay, unless the pick adds a file at
   the same path; then ask.

When the user decides something about a commit that isn't being picked now (pick it later with a change, skip it,
fold part of it into another pick), record it in that commit's row, not only in chat or private memory:
`python3 .claude/skills/cherrypick-log/update_cherrypick_log.py mark <hash> --note 'Plan <YYYY-MM-DD>: …'`. Don't
start the note with `AI checked`, which is reserved for review verdicts.

## 1. Read it before picking

- `git show --stat <hash>`, then the whole `git show <hash>`. Note the subject's `(#N)`, and whether the upstream
  message itself has a `(cherry picked from commit …)` line, which means yuzono copied it from keiyoushi.
- Sort the touched files:
  - **Shared and present here** (`core/`, `lib/`, `compiler/`, `common/`, `gradle/`, `.github/`, `CONTRIBUTING.md`,
    root build files): adapt them.
  - **Manga-only** (a `src/<lang>/<name>` or `lib-multisrc/<name>` module that doesn't exist here): drop it.
  - **New shared files**: keep them and check them for manga identifiers (step 3).
- For each shared file, see how ours already differs from upstream's parent: `git diff <hash>~1 HEAD -- <file>`.
  Every difference is either an anime adaptation (keep it), something this repo added (keep it, e.g. torrent, the
  `>=` libVersion check that `src/pt/animeito` relies on), or something upstream has and this repo dropped on purpose
  (keep it dropped, e.g. KMK). `git log --oneline -5 -- <file>` shows why a line is ours.
- **Prerequisites.** Do this before picking, for every part you would keep:
  - **Context:** if the diff's context refers to code that doesn't exist here, find the earlier upstream commits that
    added it (`git log --oneline <hash>~20..<hash>~ -- <file>` on the upstream branch).
  - **What it adds:** for every key, config entry, resource, string, DSL option or call the commit adds, find what reads
    or provides it upstream at that commit (`git grep -n '<name>' <hash> -- ':!src' ':!lib-multisrc'`). Then check
    that the same reader or provider exists here (`git grep -n '<name>' HEAD`). Example: the `pt-BR` entry from
    `9b3dc82e45` (AnimeXNovel: Migrate to 1.6) carries `filterFetchHint`, which only the compiler field from
    `453e52b243` (Add support for ext-lib 1.6 based extensions) can parse. Without that field, code generation fails for
    every mirror or custom-URL source.
  - **Don't rely on a prerequisite's status.** The log shows a partial pick as `?` with `partial: N of M files not
    picked` in its Details: `274251e580` (CacheControlInterceptor) brought only the network code of `453e52b243`. But
    it compares file names, not contents, and skips rows already marked `O`. For each prerequisite, compare its shared
    files with ours (`git diff <prerequisite> HEAD -- <those files>`).
  - **If anything is missing, stop before picking.** Tell the user which part needs which commit and what happens
    without it, testing that where you can. Then ask: pick the prerequisite first, pick this commit without that
    part, or wait, recording the plan in the Notes (step 0). Never drop, rewrite or stub out a part on your own to
    make the pick fit, and don't pull in extra commits without asking.
- Check whether this repo already has its net effect. A yuzono commit that only swaps upstream's values for
  yuzono's manga-repo ones, like `00762b714e` (Fix new index configuration for Yūzōnō), is usually covered by the
  adaptation of an earlier pick. If, after the step 3 adaptation, nothing would change, don't make an empty commit.
  Abort the pick (`git cherry-pick --abort`; once git has committed a clean pick, `git reset --hard HEAD~1`, which
  loses nothing when `git diff --quiet HEAD~1` is clean) and propose marking the row `X`, naming the commit that
  covers it:
  `mark <hash> --status X --note 'covered by <hash> (<subject>)'`. Ask first.

## 2. Will it break Anikku?

This repo's main client is Anikku (`komikku-app/anikku`). Upstream targets Mihon, and @secozzi targets newer Aniyomi,
so a change that is right for them can break Anikku. Check the commit against what Anikku actually reads, from
`app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionLoader.kt`,
`app/src/main/java/eu/kanade/tachiyomi/extension/api/ExtensionApi.kt` and
`domain/src/main/java/mihon/domain/extensionrepo/service/ExtensionRepoService.kt`. Read them with
`gh api repos/komikku-app/anikku/contents/<path> -H 'Accept: application/vnd.github.raw'`, because the contract below
may have changed since this was written.

**Repo files.** Anikku reads the following from the published repo:
- **`index.min.json` at the repo root:** each entry has `name`, `pkg`, `apk`, `lang`, `code`, `version`, `nsfw` (an
  integer, required), `torrent` (an integer, 0 by default) and `sources` (`id`, `lang`, `name`, `baseUrl`).
- **`repo.json`:** its signing-key fingerprint decides whether extensions are trusted.
- **Downloads:** APKs come from `apk/<apk>` and icons from `icon/<pkg>.png`.

It breaks Anikku to stop writing `index.min.json` or `repo.json`, to rename or drop one of those fields, to change
the `apk/`/`icon/` layout or the `apk` file name, or to mark MIXED extensions `nsfw: 0`. The APK's `nsfw` meta-data is
1 for MIXED, and Anikku won't load an NSFW extension while NSFW sources are hidden.

**APKs.** Each APK needs:
- the `uses-feature` `tachiyomi.animeextension`;
- the meta-data `tachiyomi.animeextension.class` (`;`-separated, and a leading `.` means relative to the package),
  `tachiyomi.animeextension.nsfw` and `tachiyomi.animeextension.torrent`;
- a `versionName` of `<lib>.<code>` with a lib version between `LIB_VERSION_MIN` and `LIB_VERSION_MAX` (12 to 16
  today). Anything outside that range is hidden from the store and refused at load time.

Updates are found only through a higher `versionCode` or lib version. A new package name counts as a different
extension, and a different signing key makes it untrusted.

**Runtime.** Extensions compile against extensions-lib 16, but they run against the Anikku installed on the device.
These break at runtime:
- using a class or method that Anikku's runtime doesn't have (ext-lib 17 or later, a new `AnimeHttpSource` hook);
- raising a `compileOnly` dependency past what the latest stable Anikku ships (`gradle/libs.versions.toml`, see
  CONTRIBUTING "Additional dependencies");
- dropping the keep rules that the legacy video API needs in `common/proguard-rules.pro`.

**Source IDs.** Changing a source's ID disconnects users' libraries, so it needs the old `id` kept, as AGENTS.md says.

If the commit would break Anikku, even when it is correct for Mihon or Aniyomi, **stop before picking**. Run
`git cherry-pick --abort` if the pick has already started.
- Tell the user plainly that you recommend against it: what breaks, for whom (all Anikku users, or only users of some
  extensions), when (at the next publish, on update, or at runtime), and the evidence (the Anikku code that reads it).
- Offer the alternatives: pick it with an adaptation that keeps Anikku working (say exactly which), take only the
  harmless parts, or mark it `X` with a note.
- Continue only if the user explicitly confirms. Even then, prefer the Anikku-safe adaptation.

If you can't tell, say so, and treat it as a risk to raise before picking, not a reason to stay quiet.

## 3. Pick and adapt

Run `git cherry-pick -x <hash>`, which keeps upstream's `(cherry picked from commit …)` line and appends ours. If it
applies cleanly, git commits it at once and step 4 amends that commit. Otherwise the pick stays in progress.

Resolve conflicts by keeping upstream's code and layout, formatting included, even where Spotless would change it.
CI doesn't run Spotless, and matching upstream keeps later picks applying. Change only what AGENTS.md lists:

- **Manga → anime:**
  - API types: `eu.kanade.tachiyomi.source.*` → `eu.kanade.tachiyomi.animesource.*` (`ConfigurableSource` →
    `ConfigurableAnimeSource`, `SourceFactory` → `AnimeSourceFactory`, `Source` → `AnimeSource`, `HttpSource` →
    `AnimeHttpSource`, `SManga`/`SChapter` → `SAnime`/`SEpisode`, `fetchSearchManga` → `getSearchAnime`).
  - Packages and namespaces: `eu.kanade.tachiyomi.extension` → `eu.kanade.tachiyomi.animeextension`.
  - Manifest: `uses-feature` `tachiyomi.extension` → `tachiyomi.animeextension`, and the `tachiyomi.extension.*`
    meta-data → `tachiyomi.animeextension.*`. The `tachiyomix.*` keys stay as they are.
  - Deeplinks: the intent action `eu.kanade.tachiyomi.SEARCH` → `eu.kanade.tachiyomi.ANIMESEARCH`, which
    `core/src/main/kotlin/keiyoushi/source/UrlActivity.kt` sends. Anikku ignores the manga action, so every deeplink
    would stop working.
  - Naming: the `Tachiyomi:` label → `Aniyomi:`, and the `tachiyomi-` APK prefix → `aniyomi-`.
- **Branding:** keiyoushi or manga-repo URLs, the store name, badge, signing key and contact → this repo's. Copy them
  from the current files; never type them from memory.
- **DSL and versions:** the block is `yuzono { }`, `libVersion` is `"14"`/`"16"`, and KMK blocks and fields are dropped.
- **Index schema:** keep the Aniyomi field numbers listed in AGENTS.md.
- **CI:** mirror into `build_pull_request.yml`'s publish job any dependency or argument change made to `build_push.yml`.
- **Publish target and branch:** `build_push.yml` publishes to `yuzono/aniyomi-repo`, and that repo's `sync.yml`
  copies it to `yuzono/anime-repo`. The PR check in `build_pull_request.yml` reads `yuzono/anime-repo`, and this
  repo's default branch is `master`. Keep all of these when a pick changes repository targets or branch names, as
  `a31e0c9ddc` (Moving repo & rename to `main`) does.
- **Torrent:** keep it, and carry it into any rewritten path: manifest meta-data, index `isTorrent`, and the legacy
  `torrent` field in `index.min.json`.
- **Manga-only modules:** remove their files with `git rm`, including modify/delete conflicts. These and the
  adaptations above are the only things you change without asking. Leaving out any other part, for example a key whose
  reader isn't here, needs the user's OK first (step 1, Prerequisites).

Files that merged without a conflict can still carry manga names, for example a manifest moved into generated code.
Check everything the pick brings, not just the conflicts. `<base>` is `HEAD` while the pick is in progress and
`HEAD~1` once git has committed it; `git diff --cached HEAD~1` covers the commit plus the adaptations staged on top:

```bash
git diff --cached <base> | grep -n -E 'eu\.kanade\.tachiyomi\.(source|extension)\b|tachiyomi\.extension\b|tachiyomi\.SEARCH|Tachiyomi: |"tachiyomi-|keiyoushi/extensions\b|manga-repo|\bHttpSource\b|SManga|SChapter|fetchSearchManga|KMK'
```

If the adapted result changes nothing (`git diff --cached --quiet <base>`), go back to the "net effect" check in
step 1.

## 4. Message, log row, commit

- **Subject:** qualify a trailing `(#N)` so it doesn't link to this repo's PRs.
  - A yuzono commit whose message has a `(cherry picked from commit …)` line → `(keiyoushi/extensions-source#N)`.
  - A yuzono commit without one may be yuzono's own PR. Compare `gh pr view N -R keiyoushi/extensions-source` with
    `gh pr view N -R yuzono/tachiyomi-extensions` and use the repo whose PR title matches.
  - A @secozzi commit → `(Secozzi/aniyomi-extensions#N)`.
  - No number: leave the subject unchanged.
- **Body:** keep upstream's body and every `(cherry picked from commit …)` line. If the pick folds in a change from
  another upstream commit (a Notes plan, or something the user approved), add one line to the end of the body, before
  the `(cherry picked from commit …)` lines, naming that commit by hash and PR, e.g.
  `Also includes mtime=0 from upstream c2ad96ef7e (keiyoushi/extensions-source#18360).` Don't hard-wrap it.
- **Log row**, in the same commit: run
  `python3 .claude/skills/cherrypick-log/update_cherrypick_log.py mark <full-hash> --status O`, then
  `git add cherrypick_log.md`. `mark` rewrites only that row; the summary counts refresh on the next `update`. Never
  edit the table by hand, except to resolve a stash-pop conflict on the row (below). If there is no row, skip this
  and say so. If the pick leaves out a part that applies here
  (not manga-only code), use `--status '?' --note 'Partial <YYYY-MM-DD>: picked without <part> (<why>)'` instead, so
  the row doesn't claim the whole commit.
- **Commit:** stage only the pick's own files and the log row, with `git add <path>` for each one. Never use
  `git add -A`, `git add -u` or `git commit -a`.
  - After a conflict: write the message to a scratchpad file (`.git/MERGE_MSG` without its `# Conflicts:` block), then
    `git commit -F <file>`.
  - After a clean pick: add the log row, then `git commit --amend -F <file>`.
  - Check that the commit holds only the expected files: `git show --stat HEAD`.
  - Then, if step 0 stashed anything, run `git stash pop`. If it conflicts, keep both sides, and say so. On
    `cherrypick_log.md` a conflict usually means an uncommitted note and the new `O` mark are on the same row.
  - To resolve that: edit the row to keep status `O` and the note. Then run `git reset -q`: a conflicted pop leaves
    every other popped file staged, and the stashed changes, the note included, must come back unstaged. Then
    `git stash drop`, since a conflicted pop keeps the stash.
- **Signature:** commits are GPG-signed automatically, so `git log -1 --format='%h %G?'` must show `G`. Rewrite
  history only with rebase or amend, never `filter-branch`, which drops signatures.

## 5. Verify

Keep Gradle scoped (AGENTS.md: never run an unscoped task).

- **One extension or theme:** run its `compileDebugKotlin`.
- **`core/`, `compiler/`, `common/` or `gradle/build-logic`:**
  - Build a small set in a separate `git worktree` with a narrowed `settings.gradle.kts`; never narrow the main tree's
    settings. Cover a plain extension, a themed one, a torrent one, a lib 16 extension on a lib 14 theme
    (`src/pt/animeito`), and one with deeplinks.
  - For build-logic changes, run `:<module>:processReleaseMainManifest` and `:<module>:generateReleaseKeepRules` in
    that worktree and on the parent commit in a second one, then compare the merged manifests
    (`build/intermediates/merged_manifest/release/**/AndroidManifest.xml`) and `extClass.keep`. `compileDebugKotlin`
    writes neither.
- **A new DSL or code-generation feature:** add throwaway test modules in the worktree only, including cases that use
  it in ways upstream's extensions might not.
- **Anything that changes what gets published:** run the extensions inspector CI uses (latest release of
  `komikku-app/aniyomi-extensions-inspector`) on release APKs. A crash there stops the publish for every extension.
- **`.github/scripts`:**
  - Run the scripts against copies of the live repo data, in a scratchpad venv with Python ≥ 3.12 and
    `pip install protobuf`. The live data is `index.json` and `index.min.json` from
    `raw.githubusercontent.com/yuzono/anime-repo/repo/`, the public copy of the private `yuzono/aniyomi-repo`.
  - Compare the output with what the previous script version writes for the same input.

Check step 2's Anikku contract on the result too:
- run `aapt dump --include-meta-data badging` on one built APK and confirm the feature, the meta-data and the lib
  version range;
- when the index scripts changed, confirm that the generated `index.min.json` keeps every field and value Anikku
  reads.

If anything Anikku needs is broken, the commit already exists. Say so plainly, recommend against keeping it, and ask
before dropping it (`git reset --hard HEAD~1` discards it, so get confirmation first).

Remove the worktrees afterwards. Report what ran, what passed and what wasn't run.

The Bash tool runs zsh:
- **Arguments:** an unquoted `$var` isn't word-split, so pass several arguments as an array (`"${T[@]}"`).
- **Colons:** `$var:x` is a history modifier, so write `${var}` before a `:`.
- **Globs:** quote them, as in `--include='*.kt'` and `--status '?'`.

## 6. Later upstream fixes for this commit

Find later upstream commits that fix something this commit introduced:

- **Candidates:**
  `git log --reverse --format='%h %ad %s' --date=short <hash>..<upstream-branch> -- <files it touched, including new ones>`.
- **References:** commits that mention it, found with `git log <hash>..<upstream-branch>` and `--grep='#<N>'`,
  `--grep=<short-hash>` or `--grep='This reverts commit <full-hash>'`.
- **Read each candidate's diff:** does it change lines this commit added, and is the change a fix (wrong behaviour,
  crash, timing or ordering, wrong value) rather than a feature or a refactor? Titles help ("fix", "timing", "eager",
  "revert"), but decide from the code. Where you can, reproduce the bug with the step 5 setup.

For each fix, give its hash, date and title, the bug it fixes, its status in `cherrypick_log.md`, and whether it
applies on its own or needs earlier commits (name them). Don't pick them; recommend an order.

- **The pick doesn't work without a later fix.** If the pick fails step 5 on its own and a later upstream commit fixes
  that, say so first in the report. That happened with `d239a86987` (small cleanup in build logic): themed extensions
  stopped configuring until `4e7ac4581e` (fix theme config timing). Ask whether to pick the fix right after, as its
  own commit.
- **The fix is buried in a big commit.** If the fix, or any other useful change, is a small part of a large commit
  that is otherwise unrelated or can't be picked, don't recommend picking that commit. The example is `mtime=0`
  inside `c2ad96ef7e` (CI: skip already uploaded assets and retry…), which belongs to the GitHub Releases work.
  Recommend folding that change into a related pick instead (step 4, Body), and record the plan in both rows' Notes
  (step 0). Ask first.

## 7. Bugs with no upstream fix

List the bugs found while reading, resolving and verifying that no later upstream commit fixes. Include bugs the
adaptation could introduce, such as a manga name left in generated code or a dropped anime-only path. For each, give a
concrete failure scenario and the evidence (test output, file and line). **Don't fix them.** Ask whether to fix one in
a separate commit or leave it.

## 8. Which PR it belongs in

Decide whether the pick can ship in the same PR as the rest of the branch (`git log --oneline master..HEAD`), should
go in a PR of its own, or has to wait until earlier commits are merged and published. Give the verdict and the reason.

**Needs earlier commits published first.** This applies when the commit reads something that only a publish creates:
the files on the `repo` branch of `yuzono/anime-repo` (`index.json`, `index.min.json`, `index.pb`, the `apk/` and
`icon/` layout, copied there from `yuzono/aniyomi-repo` by its `sync.yml`), or anything else CI reads back from a
previous run.
1. Fetch the live file from `raw.githubusercontent.com/yuzono/anime-repo/repo/` and check that it is already in the
   format the commit expects.
2. If only an unpublished commit (one not on `master`, or on `master` before its `build_push.yml` run finished) writes
   that format, the PR checks and the publish both fail until it is published. For example, the pick of upstream
   `00a1b13893` (CI: use new index format for merging index files) reads the new-format `index.json`. Only this
   repo's `fb83cf789a` (Create index file in new format) writes it, so the pick had to wait for a publish that
   included `fb83cf789a`.
3. Name the split point, i.e. the last commit of the first PR, and how to confirm its publish ran (for example
   `curl -fsSL https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.json | head -c 20`). Suggest a branch
   for the first PR at that point, named after the current branch: `git branch <branch>-prepare <split-commit>`, as
   with `store-prepare` and `store`.
4. Note that the later PR's "Check extension repo" job must be re-run once that publish reaches `yuzono/anime-repo`.

Prefer that ordering to compatibility code. Don't add a fallback, shim or transition path that upstream doesn't have
just so a pick works before its prerequisite is published. Propose one only when ordering can't work, for example
when clients need both formats at once, and ask.

Check the other direction too: if the commit changes what the published repo serves, say who reads it (Anikku reads
`index.min.json`, Aniyomi reads `index.pb`). Also say whether the switch needs a transition step.

**Better in its own PR.** Check these:
- **Independent:** the commit neither depends on the branch's other commits nor is needed by them. Test it with a
  throwaway worktree from `master`: if the pick applies cleanly there and passes step 5's checks, it can ship alone,
  and it shouldn't wait behind a branch that is held up, for example by a staged publish.
- **Different area:** it touches an unrelated area, such as one @secozzi extension on a branch about CI or build logic.
  A separate PR keeps review and reverts simple.
- **Version bump:** it bumps the `versionCode` or `baseVersionCode` of a module that the branch already bumps
  (`git diff master...HEAD -- <module>/build.gradle.kts`). AGENTS.md allows one bump per module per PR, so either give
  it its own PR or drop the second bump. Ask which.

**Same PR.** Keep it here when it builds on, fixes, or is needed by the branch's commits, as with an upstream follow-up
fix for an earlier pick. Group commits that change `core/`, `compiler/`, `common/`, `gradle/`, `.github/scripts` or
the root build files: each of those PRs makes CI rebuild and republish every extension (`CORE_FILES_REGEX` in
`generate-build-matrices.py`).

**Open PRs in this repo.** List the open PRs that touch the same files:

```bash
gh pr list -R yuzono/anime-extensions --state open --limit 200 --json number,title,files \
  --jq '.[] | select([.files[].path] | any(. == "<file>")) | "#\(.number) \(.title)"'
```

For each one, say what it changes that the branch doesn't, whether the pick supersedes it (so it can be closed once
the branch is merged, carrying over anything still useful), and whether they would conflict.

## Report

Name every commit as `<hash>` (<subject>), as in AGENTS.md "Referring to commits". For each picked commit, report:
- the Anikku verdict from step 2: safe, safe after the adaptation (say which), or would break it (not picked, or kept
  only after the user confirmed);
- any Notes plan from step 0 that you carried out;
- the new hash and subject, and whether it is signed;
- every line that differs from upstream's patch and why (`git range-diff <hash>~1..<hash> <new>~1..<new>` shows them);
- the log row marked `O`;
- the verification results;
- the later fixes, from step 6;
- the bugs with no upstream fix, from step 7;
- which PR it belongs in, from step 8: same PR, its own PR, or after named commits are published, with the reason,
  plus any open PR it overlaps.

Nothing is pushed.
