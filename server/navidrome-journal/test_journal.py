import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path
from unittest.mock import patch

from journal import Journal, reset_reason


class TrackJournalTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.source = Path(directory.name) / "navidrome.db"
        self.state = Path(directory.name) / "journal.db"
        self.change("CREATE TABLE media_file (id TEXT PRIMARY KEY, library_id INTEGER, missing INTEGER, title TEXT)")
        self.change("INSERT INTO media_file VALUES ('keep',1,0,'Old title'), ('remove',1,0,'Remove'), ('private',2,0,'Private')")
        self.journal = Journal(self.source, self.state, 0)

    def change(self, statement):
        with closing(sqlite3.connect(self.source)) as db, db:
            db.execute(statement)

    def test_add_update_and_delete_survive_restart_and_respect_library_scope(self):
        cursor, reset, more, events = self.journal.page("listener", [1], None)
        self.assertEqual((False, False, []), (reset, more, events))
        self.change("INSERT INTO media_file VALUES ('new',1,0,'New')")
        self.change("UPDATE media_file SET title = 'New title' WHERE id = 'keep'")
        self.change("UPDATE media_file SET missing = 1 WHERE id = 'remove'")
        self.change("DELETE FROM media_file WHERE id = 'private'")

        with patch("journal.PAGE_SIZE", 2):
            checkpoint, reset, more, events = self.journal.page("listener", [1], cursor)
            first = dict(events)
            self.assertFalse(reset)
            self.assertTrue(more)
            reopened = Journal(self.source, self.state, 0)
            end, reset, more, events = reopened.page("listener", [1], checkpoint)
            self.assertFalse(reset)
            self.assertFalse(more)
            self.assertEqual({"remove": 1, "keep": 0, "new": 0}, first | dict(events))
            self.assertEqual((end, False, False, {}), normalize(reopened.page("listener", [1], end)))
        new_scope, reset, _, _ = reopened.page("listener", [1, 2], end)
        self.assertTrue(reset)
        self.assertEqual("scope", reset_reason(end, new_scope))

    def test_moving_a_track_removes_it_for_the_old_library_and_adds_it_for_the_new_one(self):
        old = self.journal.page("old", [1], None)[0]
        new = self.journal.page("new", [2], None)[0]
        both = self.journal.page("both", [1, 2], None)[0]
        self.change("UPDATE media_file SET library_id = 2 WHERE id = 'keep'")
        self.assertEqual({"keep": 1}, dict(self.journal.page("old", [1], old)[3]))
        self.assertEqual({"keep": 0}, dict(self.journal.page("new", [2], new)[3]))
        self.assertEqual({"keep": 0}, dict(self.journal.page("both", [1, 2], both)[3]))

    def test_expired_cursor_requires_a_new_baseline_and_unchanged_rows_keep_the_cursor(self):
        cursor = self.journal.page("listener", [1], None)[0]
        self.assertEqual((cursor, False, False, {}), normalize(self.journal.page("listener", [1], cursor)))
        self.change("DELETE FROM media_file WHERE library_id = 1")
        with patch("journal.RETAIN", 1):
            head, reset, more, events = self.journal.page("listener", [1], cursor)
        self.assertTrue(reset)
        self.assertNotEqual(cursor, head)
        self.assertEqual("history", reset_reason(cursor, head))
        self.assertFalse(more)
        self.assertEqual([], events)

    def test_an_unrelated_library_change_advances_the_checkpoint_without_visible_changes(self):
        before = self.journal.page("listener", [1], None)[0]
        self.change("UPDATE media_file SET title = 'New private title' WHERE id = 'private'")
        after = self.journal.page("listener", [1], None)[0]
        self.assertNotEqual(before, after)
        self.assertEqual((after, False, False, {}), normalize(self.journal.page("listener", [1], before)))


def normalize(page):
    return (*page[:3], dict(page[3]))


if __name__ == "__main__":
    unittest.main()
