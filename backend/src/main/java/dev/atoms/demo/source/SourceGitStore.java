package dev.atoms.demo.source;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Immutable local objects. A ref is a candidate, not publication: PostgreSQL owns visibility. */
final class SourceGitStore {
    record Stored(UUID versionId, String commitId, String treeId, String treeSha256, String parentCommitId) {}
    private static final String CONFIG = "[core]\n\trepositoryformatversion = 0\n\tfilemode = true\n\tbare = true\n";
    private static final byte[] EMPTY = new byte[0];
    private final ProjectSourcePaths paths;
    private final SourceGitProcess git;
    private final ProjectSourcePolicy policy;

    SourceGitStore(ProjectSourcePaths paths, SourceGitProcess git, ProjectSourcePolicy policy) {
        this.paths = paths; this.git = git; this.policy = policy;
    }

    synchronized void initialize(UUID projectId) throws IOException {
        Path project = paths.createProject(projectId);
        Path repository = Files.createDirectory(project.resolve("repository.git"),
                PosixFilePermissions.asFileAttribute(ProjectSourcePaths.DIRECTORY_MODE));
        run(project, List.of("init", "--bare", "--template=", "--object-format=sha1", "repository.git"), EMPTY, 4096);
        Files.writeString(repository.resolve("config"), CONFIG, StandardOpenOption.TRUNCATE_EXISTING);
        Files.setPosixFilePermissions(repository.resolve("config"), ProjectSourcePaths.FILE_MODE);
        try (var channel = java.nio.channels.FileChannel.open(project.resolve(".atoms-source-project"),
                java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(ProjectSourcePaths.FILE_MODE))) {
            var marker = ByteBuffer.wrap(marker(projectId));
            while (marker.hasRemaining()) channel.write(marker);
            channel.force(true);
        }
        repository(projectId);
    }

    synchronized Stored save(UUID projectId, UUID versionId, UUID parentVersionId, SourceTree input) throws IOException {
        SourceTree tree = policy.validate(input.files());
        if (!tree.sha256().equals(input.sha256()) || tree.totalBytes() != input.totalBytes()) throw failure("SOURCE_INVALID_TREE");
        Path repo = repository(projectId);
        String parent = parentVersionId == null ? null : requiredReference(repo, parentVersionId);
        String existing = reference(repo, versionId);
        if (existing != null) {
            Stored saved = inspect(repo, versionId, existing);
            if (!saved.treeSha256().equals(tree.sha256()) || !java.util.Objects.equals(parent, saved.parentCommitId()))
                throw failure("SOURCE_VERSION_CONFLICT");
            if (!read(projectId, versionId).sha256().equals(tree.sha256())) throw failure("SOURCE_STORAGE_UNAVAILABLE");
            return saved;
        }
        Node root = new Node();
        for (var file : tree.files()) {
            String[] names = file.path().split("/");
            Node node = root;
            for (int i = 0; i < names.length - 1; i++) node = node.directories.computeIfAbsent(names[i], unused -> new Node());
            String blob = oid(run(repo, List.of("hash-object", "-w", "--stdin", "--no-filters"), file.content(), 128));
            node.entries.put(names[names.length - 1], new Entry(file.mode(), "blob", blob));
        }
        String treeId = writeTree(repo, root);
        var arguments = new ArrayList<>(List.of("commit-tree", treeId));
        if (parent != null) arguments.addAll(List.of("-p", parent));
        String commit = oid(run(repo, arguments, message(versionId, tree.sha256()).getBytes(StandardCharsets.UTF_8), 128));
        // A zero old value creates only; no ref may ever be moved to different content.
        run(repo, List.of("update-ref", "--no-deref", ref(versionId), commit, "0".repeat(40)), EMPTY, 4096);
        Stored stored = inspect(repo, versionId, commit);
        if (!read(projectId, versionId).sha256().equals(tree.sha256())) throw failure("SOURCE_STORAGE_UNAVAILABLE");
        return stored;
    }

    synchronized SourceTree read(UUID projectId, UUID versionId) throws IOException {
        Path repo = repository(projectId);
        Stored version = inspect(repo, versionId, requiredReference(repo, versionId));
        var files = new ArrayList<SourceTree.File>();
        long[] total = {0};
        readTree(repo, version.treeId(), "", 0, files, total);
        final SourceTree tree;
        try { tree = policy.validate(files); }
        catch (IllegalArgumentException error) { throw failure("SOURCE_STORAGE_UNAVAILABLE"); }
        if (!tree.sha256().equals(version.treeSha256())) throw failure("SOURCE_STORAGE_UNAVAILABLE");
        return tree;
    }

    synchronized String reference(UUID projectId, UUID versionId) throws IOException { return reference(repository(projectId), versionId); }

    private String writeTree(Path repo, Node node) throws IOException {
        var entries = new TreeMap<>(node.entries);
        for (var child : node.directories.entrySet()) entries.put(child.getKey(), new Entry(0040000, "tree", writeTree(repo, child.getValue())));
        var input = new ByteArrayOutputStream();
        for (var entry : entries.entrySet()) {
            Entry value = entry.getValue();
            input.write((Integer.toOctalString(value.mode()) + " " + value.type() + " " + value.oid() + "\t" + entry.getKey() + "\0")
                    .getBytes(StandardCharsets.UTF_8));
        }
        return oid(run(repo, List.of("mktree", "-z"), input.toByteArray(), 128));
    }

    private void readTree(Path repo, String objectId, String prefix, int depth, List<SourceTree.File> files, long[] total) throws IOException {
        if (depth >= ProjectSourcePolicy.DEFAULTS.depth()) throw failure("SOURCE_STORAGE_UNAVAILABLE");
        byte[] bytes = object(repo, "tree", objectId, 256 << 10);
        int cursor = 0;
        while (cursor < bytes.length) {
            int space = index(bytes, cursor, (byte) ' '), nul = index(bytes, space + 1, (byte) 0);
            if (nul + 21 > bytes.length) throw failure("SOURCE_STORAGE_UNAVAILABLE");
            String mode = text(Arrays.copyOfRange(bytes, cursor, space));
            String name = text(Arrays.copyOfRange(bytes, space + 1, nul));
            String path = prefix + name;
            if (name.contains("/")) throw failure("SOURCE_STORAGE_UNAVAILABLE");
            try { policy.validatePath(path); }
            catch (IllegalArgumentException error) { throw failure("SOURCE_STORAGE_UNAVAILABLE"); }
            String id = HexFormat.of().formatHex(bytes, nul + 1, nul + 21);
            cursor = nul + 21;
            if (mode.equals("40000")) readTree(repo, id, path + "/", depth + 1, files, total);
            else {
                if ((!mode.equals("100644") && !mode.equals("100755")) || files.size() >= ProjectSourcePolicy.DEFAULTS.files())
                    throw failure("SOURCE_STORAGE_UNAVAILABLE");
                byte[] content = object(repo, "blob", id, (int) ProjectSourcePolicy.DEFAULTS.fileBytes());
                total[0] += content.length;
                if (total[0] > ProjectSourcePolicy.DEFAULTS.treeBytes()) throw failure("SOURCE_STORAGE_UNAVAILABLE");
                files.add(new SourceTree.File(path, content, Integer.parseInt(mode, 8)));
            }
        }
    }

    private Stored inspect(Path repo, UUID versionId, String commitId) throws IOException {
        String commit = text(object(repo, "commit", commitId, 8192));
        int split = commit.indexOf("\n\n");
        if (split < 0) throw failure("SOURCE_STORAGE_UNAVAILABLE");
        String[] header = commit.substring(0, split).split("\n");
        String[] metadata = commit.substring(split + 2).split("\n", -1);
        if (metadata.length != 4 || !metadata[0].equals("atoms-source-v1") || !metadata[1].equals(versionId.toString())
                || !metadata[2].matches("[0-9a-f]{64}") || !metadata[3].isEmpty() || !header[0].startsWith("tree "))
            throw failure("SOURCE_STORAGE_UNAVAILABLE");
        String parent = null;
        for (int i = 1; i < header.length; i++) {
            if (header[i].startsWith("parent ")) {
                if (parent != null) throw failure("SOURCE_STORAGE_UNAVAILABLE");
                parent = validOid(header[i].substring(7));
            } else if (!header[i].startsWith("author ") && !header[i].startsWith("committer ")) throw failure("SOURCE_STORAGE_UNAVAILABLE");
        }
        return new Stored(versionId, commitId, validOid(header[0].substring(5)), metadata[2], parent);
    }

    private byte[] object(Path repo, String type, String id, int limit) throws IOException {
        String length = text(run(repo, List.of("cat-file", "-s", validOid(id)), EMPTY, 32)).strip();
        final int size;
        try { size = Integer.parseInt(length); }
        catch (NumberFormatException error) { throw failure("SOURCE_STORAGE_UNAVAILABLE"); }
        if (size < 0 || size > limit) throw failure("SOURCE_STORAGE_UNAVAILABLE");
        byte[] bytes = run(repo, List.of("cat-file", type, id), EMPTY, Math.max(1, limit));
        if (bytes.length != size || !gitHash(type, bytes).equals(id)) throw failure("SOURCE_STORAGE_UNAVAILABLE");
        return bytes;
    }

    private String requiredReference(Path repo, UUID versionId) throws IOException {
        String value = reference(repo, versionId);
        if (value == null) throw failure("SOURCE_VERSION_MISSING");
        return value;
    }
    private String reference(Path repo, UUID versionId) throws IOException {
        String output = text(run(repo, List.of("for-each-ref", "--format=%(refname) %(objectname) %(symref)", ref(versionId)), EMPTY, 4096));
        if (output.isEmpty()) return null;
        String expectedPrefix = ref(versionId) + " ";
        if (!output.startsWith(expectedPrefix) || !output.endsWith(" \n")) throw failure("SOURCE_STORAGE_UNAVAILABLE");
        return validOid(output.substring(expectedPrefix.length(), output.length() - 2));
    }

    private Path repository(UUID projectId) throws IOException {
        Path project = paths.root().resolve(projectId.toString()), repo = project.resolve("repository.git");
        ProjectSourcePaths.noLinks(repo);
        for (Path directory : List.of(project, repo, repo.resolve("objects"), repo.resolve("refs"))) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) throw failure("SOURCE_STORAGE_UNAVAILABLE");
        }
        if (!Files.getPosixFilePermissions(project).equals(ProjectSourcePaths.DIRECTORY_MODE)
                || !Files.getOwner(project).equals(Files.getOwner(paths.root()))
                || !Arrays.equals(readPrivate(project.resolve(".atoms-source-project"), 128), marker(projectId))
                || !text(readPrivate(repo.resolve("config"), 512)).equals(CONFIG)
                || Files.exists(repo.resolve("objects/info/alternates"), LinkOption.NOFOLLOW_LINKS)) throw failure("SOURCE_STORAGE_UNAVAILABLE");
        return repo;
    }
    private byte[] run(Path directory, List<String> arguments, byte[] input, int limit) throws IOException {
        paths.verify();
        return git.run(directory, arguments, input, limit);
    }
    private byte[] readPrivate(Path path, int limit) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit
                || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(ProjectSourcePaths.FILE_MODE)
                || !Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(Files.getOwner(paths.root()))
                || ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1)
            throw failure("SOURCE_STORAGE_UNAVAILABLE");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] value = input.readNBytes(limit + 1);
            if (value.length > limit) throw failure("SOURCE_STORAGE_UNAVAILABLE");
            return value;
        }
    }
    private static int index(byte[] bytes, int start, byte expected) throws IOException {
        for (int i = start; i < bytes.length; i++) if (bytes[i] == expected) return i;
        throw failure("SOURCE_STORAGE_UNAVAILABLE");
    }
    private static String text(byte[] value) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();
    }
    private static String oid(byte[] value) throws IOException { return validOid(text(value).strip()); }
    private static String validOid(String value) throws IOException {
        if (!value.matches("[0-9a-f]{40}")) throw failure("SOURCE_STORAGE_UNAVAILABLE");
        return value;
    }
    private static String gitHash(String type, byte[] bytes) {
        try {
            var digest = MessageDigest.getInstance("SHA-1");
            digest.update((type + " " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException("Git object digest unavailable", error); }
    }
    private static byte[] marker(UUID project) { return ("atoms-source-project-v1\n" + project + "\n").getBytes(StandardCharsets.UTF_8); }
    private static String ref(UUID version) { return "refs/atoms/versions/" + version; }
    private static String message(UUID version, String digest) { return "atoms-source-v1\n" + version + "\n" + digest + "\n"; }
    private static IOException failure(String code) { return new IOException(code); }
    private record Entry(int mode, String type, String oid) {}
    private static final class Node {
        final Map<String, Node> directories = new TreeMap<>();
        final Map<String, Entry> entries = new TreeMap<>();
    }
}
