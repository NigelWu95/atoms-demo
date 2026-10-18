"""Run with python3 -I -S -B backend/src/test/python/test_workspace_helper.py."""
import base64
import importlib.util
import json
import os
import resource
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import uuid

MODULE = Path(__file__).resolve().parents[2] / "main/resources/source/workspace_helper.py"


class WorkspaceHelperTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("workspace_helper", MODULE)
        self.helper = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.helper)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        info = self.root.stat()
        self.request = dict(operation="create", root=str(self.root), rootIdentity=[info.st_dev, info.st_ino],
                            task=str(uuid.uuid4()), marker="owned task\n", files=[self.file("src/main.txt", b"one")])

    @staticmethod
    def file(path, content, mode=0o100644):
        return dict(path=path, content=base64.b64encode(content).decode("ascii"), mode=mode)

    def create(self):
        result = self.helper.handle(self.request)
        self.request["evidence"] = result
        self.request["operation"] = "check"
        return self.root / self.request["task"] / "project"

    def test_creates_plain_private_tree_and_checks_identity(self):
        self.request["files"].append(self.file("backend/mvnw", b"#!/bin/sh\n", 0o100755))
        project = self.create()
        self.assertEqual(b"one", (project / "src/main.txt").read_bytes())
        self.assertEqual(0o600, (project / "src/main.txt").stat().st_mode & 0o777)
        self.assertEqual(0o700, (project / "backend/mvnw").stat().st_mode & 0o777)
        self.assertEqual(self.request["evidence"], self.helper.handle(self.request))
        self.assertFalse((project / ".git").exists())

    def test_never_adopts_an_existing_task(self):
        project = self.create()
        self.request["operation"] = "create"
        with self.assertRaises((OSError, ValueError)):
            self.helper.handle(self.request)
        self.assertEqual(b"one", (project / "src/main.txt").read_bytes())

    def test_directory_count_does_not_accumulate_open_descriptors(self):
        self.request["files"] = [self.file(f"dir{index}/file.txt", b"one") for index in range(128)]
        previous = resource.getrlimit(resource.RLIMIT_NOFILE)
        try:
            resource.setrlimit(resource.RLIMIT_NOFILE, (64, previous[1]))
            project = self.create()
        finally:
            resource.setrlimit(resource.RLIMIT_NOFILE, previous)
        self.assertEqual(128, len(list(project.iterdir())))

    def test_rejects_unsafe_paths_and_oversized_files_before_writing(self):
        for path in ["../outside", "/outside", "src/../outside", "src//file", "src\\file", ".git/config"]:
            with self.subTest(path=path):
                self.request["files"] = [self.file(path, b"bad")]
                with self.assertRaises(ValueError):
                    self.helper.handle(self.request)
                self.assertFalse((self.root / self.request["task"]).exists())
        self.request["files"] = [self.file("huge.txt", b"x" * ((2 << 20) + 1))]
        with self.assertRaises(ValueError):
            self.helper.handle(self.request)

    def test_rejects_symlink_root_replacement_and_wrong_identity(self):
        alias = self.root / "alias"
        alias.symlink_to(self.root, target_is_directory=True)
        self.request["root"] = str(alias)
        with self.assertRaises((OSError, ValueError)):
            self.helper.handle(self.request)
        self.request["root"] = str(self.root)
        self.request["rootIdentity"][1] += 1
        with self.assertRaises(ValueError):
            self.helper.handle(self.request)

    def test_rejects_replaced_project_and_hardlinked_marker(self):
        project = self.create()
        project.rename(project.with_name("old"))
        project.symlink_to(project.with_name("old"), target_is_directory=True)
        with self.assertRaises((OSError, ValueError)):
            self.helper.handle(self.request)
        project.unlink()
        project.with_name("old").rename(project)
        os.link(project.parent / ".atoms-source-workspace", project.parent / "alias")
        with self.assertRaises(ValueError):
            self.helper.handle(self.request)

    def scan(self):
        self.request["operation"] = "scan"
        return self.helper.handle(self.request)["files"]

    def test_scans_current_files_including_additions_and_deletions(self):
        project = self.create()
        (project / "src/main.txt").unlink()
        (project / "src/second.txt").write_text("two", encoding="utf-8")
        self.assertEqual([self.file("src/second.txt", b"two")], self.scan())

    def test_scan_rejects_symlinks_hardlinks_fifo_and_growth_over_limit(self):
        project = self.create()
        target = project / "src/main.txt"
        for kind in ("symlink", "hardlink", "fifo", "oversize"):
            with self.subTest(kind=kind):
                extra = project / "extra"
                if kind == "symlink":
                    extra.symlink_to(target)
                elif kind == "hardlink":
                    os.link(target, extra)
                elif kind == "fifo":
                    os.mkfifo(extra)
                else:
                    extra.write_bytes(b"x" * ((2 << 20) + 1))
                try:
                    with self.assertRaises((OSError, ValueError)):
                        self.scan()
                finally:
                    extra.unlink()

    def test_scan_rejects_a_file_replaced_or_grown_during_its_read(self):
        project = self.create()
        target = project / "src/main.txt"
        original_read = os.read
        for kind in ("replace", "grow"):
            inode = target.stat().st_ino
            changed = False

            def mutate(fd, count):
                nonlocal changed
                data = original_read(fd, count)
                if os.fstat(fd).st_ino == inode and not changed:
                    changed = True
                    if kind == "replace":
                        target.rename(target.with_name("old"))
                        target.symlink_to(target.with_name("old"))
                    else:
                        with target.open("ab") as output:
                            output.write(b"changed during scan")
                return data

            with patch.object(self.helper.os, "read", side_effect=mutate):
                with self.assertRaises((OSError, ValueError)):
                    self.scan()
            self.assertTrue(changed)
            if kind == "replace":
                target.unlink()
                target.with_name("old").rename(target)


if __name__ == "__main__":
    unittest.main()
