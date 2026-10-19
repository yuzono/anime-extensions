"""Unit tests for update_cherrypick_log.py (stdlib only, no git repository needed).

Run: python3 -m unittest discover -s .claude/skills/cherrypick-log -p 'test_*.py'
"""

import sys
import unittest
from pathlib import Path
from unittest import mock

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

import update_cherrypick_log as m  # noqa: E402

UP = m.Upstream("main", "main", "main", "owner/repo")


def commit(ch: str, message: str, date: str) -> m.Commit:
    return m.Commit(ch * 40, date, "a", "a@x", "a", "a@x", message, repo=UP.name)


def revert_of(ch: str, target: m.Commit, date: str) -> m.Commit:
    return commit(ch, f'Revert "{target.subject}"\n\nThis reverts commit {target.full}.', date)


def render(commits, states=None):
    """Rows as cmd_update builds them: (entry, cells) with status and notes from `states`."""
    states = states or [("", "")] * len(commits)
    return [
        (m.Entry(c, UP, "primary", c.full), [st, "", UP.name, c.date, c.subject, "a", "", notes])
        for c, (st, notes) in zip(commits, states)
    ]


def rerender(rows, extra=()):
    """Next run: release script omissions like cmd_update does, then add new commits."""
    released = [(e, list(m.release_script_flag(c[0], c[-1]))) for e, c in rows]
    return render([e.commit for e, _ in released] + list(extra), [tuple(x) for _, x in released] + [("", "")] * len(extra))


def statuses(rows):
    return [cells[0] for _, cells in rows]


def fake_builder(cfg, logs, changed=None):
    """A Builder reading `logs` (newest first, like git log) and `changed` paths instead of git."""

    class FakeBuilder(m.Builder):
        def log(self, up, since):
            return [x for x in logs[up.name] if not since or x.date >= since]

        def path_excluded(self, up):
            text = "".join(f"\x1e{c.full}\n\n" + "\n".join((changed or {}).get(c.full, [])) for c in logs[up.name])
            with mock.patch.object(m, "git", return_value=text):
                return super().path_excluded(up)

    return FakeBuilder(Path("."), cfg)


def mk(ch, msg, date, repo, time=0):
    return m.Commit(ch * 40, date, "a", "a@x", "a", "a@x", msg, repo=repo, time=time)


class RevertChainTest(unittest.TestCase):
    def setUp(self):
        self.c = commit("c", "Feature", "2026-01-01")
        self.r1 = revert_of("d", self.c, "2026-01-02")
        self.r2 = revert_of("e", self.r1, "2026-01-03")

    def test_pair_is_flagged_with_previous_status(self):
        rows = render([self.c, self.r1], [("?", ""), ("", "")])
        m.flag_revert_chains(rows)
        self.assertEqual(statuses(rows), ["?", "?"])
        self.assertIn("Cancels out (script): reverted by", rows[0][1][-1])
        self.assertIn("[was: ?]", rows[0][1][-1])
        self.assertIn("[was: empty]", rows[1][1][-1])

    def test_extended_chain_is_recomputed(self):
        rows = render([self.c, self.r1])
        m.flag_revert_chains(rows)
        rows = rerender(rows, extra=[self.r2])
        m.flag_revert_chains(rows)
        # odd chain: the original keeps the net change, the revert pair cancels out
        self.assertEqual(statuses(rows), ["", "?", "?"])
        self.assertEqual(rows[0][1][-1], "")

    def test_rerun_is_stable(self):
        rows = render([self.c, self.r1])
        m.flag_revert_chains(rows)
        first = [list(c) for _, c in rows]
        rows = rerender(rows)
        m.flag_revert_chains(rows)
        self.assertEqual([c for _, c in rows], first)

    def test_legacy_x_omission_becomes_flag(self):
        legacy = "Omitted (script): cancels out - reverted by `dddddddddd` [was: empty]"
        rows = render([self.c, self.r1], [("X", legacy), ("X", legacy.replace("reverted by", "reverts"))])
        rows = rerender(rows)
        m.flag_revert_chains(rows)
        self.assertEqual(statuses(rows), ["?", "?"])
        self.assertNotIn("Omitted (script)", rows[0][1][-1])

    def test_manual_x_blocks_chain(self):
        rows = render([self.c, self.r1], [("X", "won't pick"), ("", "")])
        m.flag_revert_chains(rows)
        self.assertEqual(statuses(rows), ["X", ""])
        self.assertEqual(rows[0][1][-1], "won't pick")

    def test_ai_verdict_blocks_chain(self):
        rows = render([self.c, self.r1], [("", "AI checked 2026-10-03: not found - x"), ("", "")])
        m.flag_revert_chains(rows)
        self.assertEqual(statuses(rows), ["", ""])

    def test_picked_side_blocks_chain(self):
        rows = render([self.c, self.r1], [("O", ""), ("", "")])
        m.flag_revert_chains(rows)
        self.assertEqual(statuses(rows), ["O", ""])


class ReleaseTest(unittest.TestCase):
    def test_manual_x_untouched(self):
        self.assertEqual(m.release_script_flag("X", "my reason"), ("X", "my reason"))

    def test_restores_recorded_status_and_keeps_user_notes(self):
        notes = "mine; Cancels out (script): reverts `abc` [was: ?]"
        self.assertEqual(m.release_script_flag("?", notes), ("?", "mine"))
        self.assertEqual(m.release_script_flag("?", "Cancels out (script): reverts `abc` [was: empty]"), ("", ""))

    def test_legacy_x_note_is_released(self):
        self.assertEqual(m.release_script_flag("X", "Omitted (script): cancels out - reverts `abc`"), ("", ""))
        self.assertEqual(m.release_script_flag("X", "Omitted (script): x [was: ?]"), ("?", ""))

    def test_manual_x_on_new_flag_is_kept(self):
        self.assertEqual(m.release_script_flag("X", "Cancels out (script): reverts `abc` [was: empty]"), ("X", ""))

    def test_ai_reviewed_row_untouched(self):
        notes = "Cancels out (script): reverts `abc` [was: empty]; AI checked 2026-10-06: not found - x"
        self.assertEqual(m.release_script_flag("?", notes), ("?", notes))

    def test_user_text_before_mark_is_kept(self):
        notes = "ok, Cancels out (script): reverts `abc` [was: empty];mine"
        self.assertEqual(m.release_script_flag("?", notes), ("", "ok;mine"))
        self.assertEqual(m.release_script_flag("?", "a &lt; Cancels out (script): x [was: empty]"), ("", "a &lt;"))

    def test_bogus_recorded_status_is_ignored(self):
        self.assertEqual(m.release_script_flag("?", "Cancels out (script): x [was: done]"), ("", ""))

    def test_stale_note_removed_when_status_changed_by_hand(self):
        notes = "Cancels out (script): reverts `abc` [was: empty]; picked manually"
        self.assertEqual(m.release_script_flag("O", notes), ("O", "picked manually"))

    def test_escaped_entity_in_note_is_kept(self):
        notes = "use a &lt;b&gt; tag; Cancels out (script): x [was: empty]"
        self.assertEqual(m.release_script_flag("?", notes), ("", "use a &lt;b&gt; tag"))

    def test_user_separators_are_kept(self):
        notes = "a;b; Cancels out (script): x [was: empty]; c"
        self.assertEqual(m.release_script_flag("?", notes), ("", "a;b; c"))


class CarryOverTest(unittest.TestCase):
    def test_manual_status_kept(self):
        self.assertEqual(m.carry_over("O", "", ""), ("O", ""))
        self.assertEqual(m.carry_over("X", "no", "O"), ("X", "no"))

    def test_rechecked_without_ai_note(self):
        self.assertEqual(m.carry_over("?", "", ""), ("", ""))
        self.assertEqual(m.carry_over("", "", "?"), ("?", ""))

    def test_ai_verdict_kept_unless_strong_evidence(self):
        note = "AI checked 2026-10-03: partial - x"
        self.assertEqual(m.carry_over("?", note, ""), ("?", note))
        self.assertEqual(m.carry_over("", note, "?"), ("", note))
        self.assertEqual(m.carry_over("?", note, "O"), ("O", note))

    def test_script_omission_released_then_rechecked(self):
        self.assertEqual(m.carry_over("?", "Cancels out (script): x [was: empty]", ""), ("", ""))
        self.assertEqual(m.carry_over("X", "Omitted (script): x [was: empty]", "?"), ("?", ""))


class ExcludeOnlyPathsTest(unittest.TestCase):
    PREFIXES = ["src", "lib-multisrc"]

    def test_only_under(self):
        self.assertTrue(m.only_under(["src/en/a/A.kt", "lib-multisrc/t/T.kt"], self.PREFIXES))
        self.assertFalse(m.only_under(["src/en/a/A.kt", "lib/x/X.kt"], self.PREFIXES))
        self.assertFalse(m.only_under(["srcfoo/x", "lib-multisrcx/y"], self.PREFIXES))
        self.assertFalse(m.only_under([], self.PREFIXES))  # empty commit is kept
        self.assertTrue(m.only_under(["README.md"], ["README.md"]))

    def test_parse_name_only(self):
        text = "\x1eaaa\n\nsrc/a\nlib/b\n\x1ebbb\n\x1eccc\n\nsrc/c\n"
        self.assertEqual(m.parse_name_only(text), {"aaa": ["src/a", "lib/b"], "bbb": [], "ccc": ["src/c"]})

    def test_module_of(self):
        self.assertEqual(m.module_of("src/en/foo/src/eu/kanade/Foo.kt"), "src/en/foo")
        self.assertEqual(m.module_of("src/en/foo/res/mipmap-hdpi/ic_launcher.png"), "src/en/foo")
        self.assertEqual(m.module_of("src/en/foo/assets/i18n/messages.properties"), "src/en/foo")
        self.assertEqual(m.module_of("src/en/foo/build.gradle"), "src/en/foo")
        self.assertEqual(m.module_of("lib-multisrc/madara/src/eu/kanade/Madara.kt"), "lib-multisrc/madara")
        self.assertEqual(m.module_of("lib-multisrc/madara/build.gradle.kts"), "lib-multisrc/madara")

    def test_config_is_normalized_and_checked(self):
        up = m.Upstream("u", "u", "main", "o/r", ["/src/", "lib-multisrc/", "/"])
        self.assertEqual(up.exclude_only_paths, ["src", "lib-multisrc"])
        self.assertEqual(m.Upstream("u", "u", "main", "o/r").exclude_only_paths, [])
        self.assertEqual(m.Upstream("u", "u", "main", "o/r").repo_wide_min_modules, 0)
        for bad in ({"exclude_only_paths": "src/"}, {"repo_wide_min_modules": "5"}, {"repo_wide_min_modules": -1}):
            with self.assertRaises(SystemExit):
                m.Upstream("u", "u", "main", "o/r", **bad)

    def test_build_drops_path_only_commits(self):
        prim = m.Upstream("prim", "prim", "main", "o/prim", ["src"])
        sec = m.Upstream("sec", "sec", "main", "o/sec", ["src"])
        cfg = m.Config("master", "2026-01-01", "out.md", "", prim, [sec])
        p_src = mk("1", "Fix a source", "2026-01-02", "prim")
        p_lib = mk("2", "Update a lib", "2026-01-03", "prim")
        s_pick = mk("3", f"Fix a source\n\n(cherry picked from commit {p_src.full})", "2026-01-04", "sec")
        s_src = mk("4", "Own source fix", "2026-01-05", "sec")
        changed = {
            p_src.full: ["src/en/a/A.kt"],
            p_lib.full: ["lib/x/X.kt", "src/en/a/A.kt"],
            s_pick.full: ["core/Util.kt"],
            s_src.full: ["src/all/b/B.kt"],
        }
        logs = {"prim": [p_lib, p_src], "sec": [s_src, s_pick]}

        entries = fake_builder(cfg, logs, changed).build()
        self.assertEqual([e.commit.full for e in entries], [p_lib.full, s_pick.full])
        pick = entries[1]
        self.assertEqual(pick.kind, "own")
        self.assertTrue(pick.pre_pick)
        self.assertIn("(not listed: only changes under src)", pick.details[0])

    def test_build_keeps_repo_wide_commits(self):
        prim = m.Upstream("prim", "prim", "main", "o/prim", ["src", "lib-multisrc"], repo_wide_min_modules=3)
        sec = m.Upstream("sec", "sec", "main", "o/sec")
        cfg = m.Config("master", "2026-01-01", "out.md", "", prim, [sec])
        narrow = mk("1", "Fix two sources", "2026-01-02", "prim")
        wide = mk("2", "Refactor UrlActivities", "2026-01-03", "prim")
        changed = {
            narrow.full: ["src/en/a/src/A.kt", "src/en/a/res/icon.png", "src/en/b/build.gradle"],
            wide.full: ["src/en/a/src/A.kt", "src/en/b/src/B.kt", "lib-multisrc/t/src/T.kt"],
        }
        entries = fake_builder(cfg, {"prim": [wide, narrow], "sec": []}, changed).build()
        self.assertEqual([e.commit.full for e in entries], [wide.full])
        self.assertEqual(entries[0].details[0], "repo-wide: 3 modules, only under src, lib-multisrc")

    def test_repo_wide_needs_a_threshold(self):
        prim = m.Upstream("prim", "prim", "main", "o/prim", ["src"])
        cfg = m.Config("master", "2026-01-01", "out.md", "", prim, [m.Upstream("sec", "sec", "main", "o/sec")])
        wide = mk("2", "Refactor everything", "2026-01-03", "prim")
        changed = {wide.full: [f"src/en/s{i}/src/S.kt" for i in range(50)]}
        self.assertEqual(fake_builder(cfg, {"prim": [wide], "sec": []}, changed).build(), [])


class OrderTest(unittest.TestCase):
    def test_parse_log_reads_committer_time(self):
        text = "\x1e".join(["a" * 40 + "\x1f2026-01-02\x1f1767312000\x1fAnn\x1fa@x\x1fBob\x1fb@x\x1fTitle\n\nBody", ""])
        (c,) = m.parse_log(text, repo="up")
        self.assertEqual((c.date, c.time, c.author, c.committer_email, c.subject, c.repo), ("2026-01-02", 1767312000, "Ann", "b@x", "Title", "up"))

    def test_rows_are_ordered_by_time_across_upstreams(self):
        prim = m.Upstream("prim", "prim", "main", "o/prim")
        sec = m.Upstream("sec", "sec", "main", "o/sec")
        cfg = m.Config("master", "2026-01-01", "out.md", "", prim, [sec])
        p_new = mk("1", "Newest main commit", "2026-01-05", "prim", time=500)
        p_old = mk("2", "Oldest main commit", "2026-01-01", "prim", time=100)
        s_tie = mk("3", "Secondary at the same time", "2026-01-05", "sec", time=500)
        s_copy = mk("4", f"Oldest main commit\n\n(cherry picked from commit {p_old.full})", "2026-01-04", "sec", time=400)
        s_mid = mk("5", "Secondary in the middle", "2026-01-03", "sec", time=300)
        logs = {"prim": [p_new, p_old], "sec": [s_tie, s_copy, s_mid]}

        entries = fake_builder(cfg, logs).build()
        self.assertEqual([e.commit for e in entries], [p_new, s_tie, s_copy, s_mid, p_old])
        copy = entries[2]
        self.assertEqual((copy.kind, copy.cluster), ("copy", p_old.full))


if __name__ == "__main__":
    unittest.main()
