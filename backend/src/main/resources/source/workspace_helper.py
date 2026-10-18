"""Platform-owned POSIX file operations. No shell, project imports, or project execution.

Paths below an opened directory use dir_fd and O_NOFOLLOW. This is not an OS sandbox.
Protocol errors deliberately omit source contents, paths and interpreter diagnostics.
"""
import base64
from contextlib import ExitStack
import json
import os
import stat
import sys
import uuid

PROTOCOL_LIMIT = 24 << 20
FILE_LIMIT = 2 << 20
TREE_LIMIT = 16 << 20
DIRECTORY_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC


def require(condition):
    if not condition:
        raise ValueError("SOURCE_FILES_UNAVAILABLE")


def identity(info):
    return [info.st_dev, info.st_ino]


def own(info, directory=False):
    require(info.st_uid == os.geteuid())
    if directory:
        require(stat.S_ISDIR(info.st_mode) and stat.S_IMODE(info.st_mode) == 0o700)
    else:
        require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1)


def held(stack, name, flags, parent=None, mode=0o600):
    fd = os.open(name, flags, mode, dir_fd=parent)
    stack.callback(os.close, fd)
    return fd


def linked(parent, name, fd):
    require(identity(os.stat(name, dir_fd=parent, follow_symlinks=False)) == identity(os.fstat(fd)))


def root_fd(stack, request):
    path = request["root"]
    require(isinstance(path, str) and path.startswith("/") and len(path) <= 4096)
    parts = path.split("/")[1:]
    require(len(parts) >= 2 and all(part not in ("", ".", "..") for part in parts))
    fd = held(stack, "/", DIRECTORY_FLAGS)
    for name in parts:
        fd = held(stack, name, DIRECTORY_FLAGS, fd)
    info = os.fstat(fd)
    own(info, True)
    require(identity(info) == request["rootIdentity"])
    return fd


def relative(path):
    require(isinstance(path, str) and len(path.encode("utf-8")) <= 240 and "\\" not in path)
    parts = path.split("/")
    require(1 <= len(parts) <= 16 and all(part not in ("", ".", "..", ".git") for part in parts))
    require(all(ord(char) >= 32 and ord(char) != 127 for char in path))
    return parts


def validated_files(request):
    files = request["files"]
    require(isinstance(files, list) and 1 <= len(files) <= 512)
    result, seen, total = {}, set(), 0
    for file in files:
        parts = relative(file["path"])
        require(file["path"] not in seen and file["mode"] in (0o100644, 0o100755))
        seen.add(file["path"])
        data = base64.b64decode(file["content"], validate=True)
        total += len(data)
        require(len(data) <= FILE_LIMIT and total <= TREE_LIMIT)
        parent = result
        for name in parts[:-1]:
            parent = parent.setdefault(name, {})
            require(isinstance(parent, dict))
        require(parts[-1] not in parent)
        parent[parts[-1]] = (data, 0o700 if file["mode"] == 0o100755 else 0o600)
    return result


def write_file(parent, name, data, mode):
    with ExitStack() as stack:
        fd = held(stack, name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC, parent, mode)
        own(os.fstat(fd))
        remaining = memoryview(data)
        while remaining:
            count = os.write(fd, remaining)
            require(count > 0)
            remaining = remaining[count:]
        os.fsync(fd)
        own(os.fstat(fd))
        linked(parent, name, fd)
        return identity(os.fstat(fd))


def write_tree(directory, entries):
    for name, value in entries.items():
        if isinstance(value, dict):
            os.mkdir(name, 0o700, dir_fd=directory)
            with ExitStack() as children:
                child = held(children, name, DIRECTORY_FLAGS, directory)
                own(os.fstat(child), True)
                write_tree(child, value)
                linked(directory, name, child)
        else:
            write_file(directory, name, *value)
    os.fsync(directory)


def create(stack, root, request, files, marker):
    os.mkdir(request["task"], 0o700, dir_fd=root)
    task = held(stack, request["task"], DIRECTORY_FLAGS, root)
    own(os.fstat(task), True)
    os.mkdir("project", 0o700, dir_fd=task)
    project = held(stack, "project", DIRECTORY_FLAGS, task)
    own(os.fstat(project), True)
    write_tree(project, files)
    marker_id = write_file(task, ".atoms-source-workspace", marker, 0o600)
    linked(root, request["task"], task)
    linked(task, "project", project)
    os.fsync(task)
    os.fsync(root)
    return dict(task=identity(os.fstat(task)), project=identity(os.fstat(project)), marker=marker_id)


def checked(stack, root, request, marker):
    task = held(stack, request["task"], DIRECTORY_FLAGS, root)
    project = held(stack, "project", DIRECTORY_FLAGS, task)
    fd = held(stack, ".atoms-source-workspace", os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC, task)
    for directory in (task, project):
        own(os.fstat(directory), True)
    info = os.fstat(fd)
    own(info)
    require(stat.S_IMODE(info.st_mode) == 0o600 and info.st_size == len(marker))
    require(os.read(fd, len(marker) + 1) == marker)
    result = dict(task=identity(os.fstat(task)), project=identity(os.fstat(project)), marker=identity(info))
    require(result == request["evidence"])
    linked(root, request["task"], task)
    linked(task, "project", project)
    linked(task, ".atoms-source-workspace", fd)
    return result, project


def handle(request):
    require(sys.version_info >= (3, 11) and os.open in os.supports_dir_fd and os.listdir in os.supports_fd)
    require(request["operation"] in ("create", "check"))
    require(str(uuid.UUID(request["task"])) == request["task"])
    marker = request["marker"].encode("utf-8")
    require(1 <= len(marker) <= 4096)
    files = validated_files(request) if request["operation"] == "create" else None
    with ExitStack() as stack:
        root = root_fd(stack, request)
        result = create(stack, root, request, files, marker) if files is not None else checked(stack, root, request, marker)[0]
        # Reopen the absolute chain: a renamed root must not be reported as a successful workspace.
        root_fd(stack, request)
        return result


if __name__ == "__main__":
    try:
        payload = sys.stdin.buffer.read(PROTOCOL_LIMIT + 1)
        require(len(payload) <= PROTOCOL_LIMIT)
        response = json.dumps(handle(json.loads(payload)), separators=(",", ":")).encode("utf-8")
        require(len(response) <= PROTOCOL_LIMIT)
        sys.stdout.buffer.write(response)
    except Exception:
        sys.stderr.write("SOURCE_FILES_UNAVAILABLE\n")
        sys.exit(1)
