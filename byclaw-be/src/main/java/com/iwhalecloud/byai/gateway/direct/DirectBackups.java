package com.iwhalecloud.byai.gateway.direct;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.iwhalecloud.byai.gateway.direct.persistence.RuntimeBinding;

import lombok.extern.slf4j.Slf4j;

/**
 * Consistent backup of one owner's Direct runtime state and its restore. A backup is taken only
 * while the runtime is paused and nothing can wake it, so the state directory is quiescent: DSH
 * logs, admissions, pending interactions, outbox, profile, browser profile and workspace, together
 * with the platform's ID mapping (routes, admissions, projection watermarks). Secrets are never
 * written: tokens stay in the platform and the runtime secret is derived again from the master key.
 * <p>
 * A restore verifies the whole archive (hashes, owner and binding identity) before it touches
 * anything, fences the old instance, swaps the restored directory in atomically and keeps the
 * replaced one aside. A restore that stops midway leaves the binding restore-pending, so no runtime
 * starts over partial state. Backups live beside the owner directories, outside every runtime mount.
 */
@Slf4j
public class DirectBackups {

    static final String FORMAT = "byclaw.direct.backup/v1";
    private static final Pattern BACKUP_ID = Pattern.compile("^\\d{8}T\\d{6}Z-[0-9a-f]{8}$");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    /** Browser singleton markers name a host and process of the instance that wrote them; never restored. */
    private static final Set<String> TRANSIENT = Set.of("SingletonLock", "SingletonSocket", "SingletonCookie");

    /** The binding side: what may be backed up and the restore transitions. */
    public interface Bindings {
        /** Run work while the owner's runtime is paused and cannot be woken; throws IllegalStateException otherwise. */
        <T> T whileQuiesced(String ownerKey, Function<RuntimeBinding, T> work);

        /** Fence the current instance and hold the binding restore-pending; the binding must be this one. */
        RuntimeBinding beginRestore(String ownerKey, long bindingId);

        /** The restored state is in place: the next turn creates a new instance over it. */
        void completeRestore(String ownerKey, long generation);

        /** The restore was undone and the original state put back. */
        void abortRestore(String ownerKey, long generation, String reason);
    }

    /** The platform's records tying the runtime to its sessions. */
    public interface Mapping {
        JSONObject export(String ownerKey);

        void rebase(String ownerKey, long sessionId, String lineage, long throughSeq);
    }

    private final Path stateRoot;
    private final Path backupRoot;
    private final Bindings bindings;
    private final Mapping mapping;
    private final int guestUid;
    private final int guestGid;
    private final Clock clock;

    public DirectBackups(Path stateRoot, Path backupRoot, Bindings bindings, Mapping mapping, int guestUid, int guestGid) {
        this(stateRoot, backupRoot, bindings, mapping, guestUid, guestGid, Clock.systemUTC());
    }

    DirectBackups(Path stateRoot, Path backupRoot, Bindings bindings, Mapping mapping, int guestUid, int guestGid, Clock clock) {
        this.stateRoot = stateRoot;
        this.backupRoot = backupRoot;
        this.bindings = bindings;
        this.mapping = mapping;
        this.guestUid = guestUid;
        this.guestGid = guestGid;
        this.clock = clock;
    }

    /** Back up a paused owner runtime; returns the manifest without its file list. */
    public JSONObject backup(String ownerKey) {
        return bindings.whileQuiesced(ownerKey, binding -> {
            Path source = ownerDirectory(ownerKey);
            if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("state_missing");
            String backupId = STAMP.format(clock.instant()) + "-" + UUID.randomUUID().toString().substring(0, 8);
            Path dir = ownerBackups(ownerKey).resolve(backupId);
            try {
                createPrivateDirectories(dir);
                JSONArray entries = new JSONArray();
                Path archive = dir.resolve("state.tar.gz");
                try (OutputStream out = privateFile(archive);
                     TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(out))) {
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                    tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
                    archiveTree(source, tar, entries);
                }
                JSONObject platform = mapping.export(ownerKey);
                Files.writeString(dir.resolve("mapping.json"), platform.toJSONString(), StandardCharsets.UTF_8);
                restrict(dir.resolve("mapping.json"));
                JSONObject manifest = new JSONObject(true);
                manifest.put("format", FORMAT);
                manifest.put("backupId", backupId);
                manifest.put("createdAt", clock.millis());
                manifest.put("ownerScope", ownerKey);
                manifest.put("bindingId", binding.getBindingId());
                manifest.put("generation", binding.getGeneration());
                manifest.put("templateId", binding.getTemplateId());
                manifest.put("protocol", DirectProtocol.PROTOCOL);
                manifest.put("secrets", "excluded");
                manifest.put("archiveSha256", sha256(archive));
                manifest.put("mappingSha256", sha256(dir.resolve("mapping.json")));
                manifest.put("files", entries.size());
                manifest.put("bytes", entries.stream().mapToLong(e -> ((JSONObject) e).getLongValue("size")).sum());
                manifest.put("outboxLineage", readLineage(source));
                manifest.put("watermarks", watermarks(source, platform));
                manifest.put("entries", entries);
                Files.writeString(dir.resolve("manifest.json"), manifest.toJSONString(), StandardCharsets.UTF_8);
                restrict(dir.resolve("manifest.json"));
                log.info("Direct backup written, owner={}, backupId={}, files={}", ownerKey, backupId, entries.size());
                return summary(manifest);
            }
            catch (IOException | RuntimeException e) {
                deleteTree(dir);
                throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException("backup_failed", e);
            }
        });
    }

    /** The owner's backups, newest first. */
    public List<JSONObject> list(String ownerKey) {
        Path dir = ownerBackups(ownerKey);
        if (!Files.isDirectory(dir)) return List.of();
        List<JSONObject> result = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.sorted(Comparator.reverseOrder()).toList()) {
                Path manifest = child.resolve("manifest.json");
                if (Files.isRegularFile(manifest)) result.add(summary(JSON.parseObject(Files.readString(manifest))));
            }
        }
        catch (IOException e) {
            throw new IllegalStateException("backup_list_failed", e);
        }
        return result;
    }

    /** Restore an owner's runtime state from one of its backups. */
    public JSONObject restore(String ownerKey, String backupId) {
        if (backupId == null || !BACKUP_ID.matcher(backupId).matches()) throw new IllegalArgumentException("backup_missing");
        Path dir = ownerBackups(ownerKey).resolve(backupId);
        Path archive = dir.resolve("state.tar.gz");
        Path manifestFile = dir.resolve("manifest.json");
        if (!Files.isRegularFile(archive) || !Files.isRegularFile(manifestFile)) throw new IllegalStateException("backup_missing");
        Path staging = stateRoot.resolve(".restore-" + RuntimeOwnerScope.directoryNameOf(ownerKey) + "-" + UUID.randomUUID());
        try {
            JSONObject manifest = JSON.parseObject(Files.readString(manifestFile));
            if (!FORMAT.equals(manifest.getString("format"))) throw new IllegalStateException("format_unsupported");
            if (!ownerKey.equals(manifest.getString("ownerScope"))) throw new IllegalStateException("identity_mismatch");
            if (!sha256(archive).equals(manifest.getString("archiveSha256"))) throw new IllegalStateException("archive_corrupt");
            long bindingId = manifest.getLongValue("bindingId");
            // Everything is unpacked and checked against the manifest before the live state is touched.
            extract(archive, staging, manifest.getJSONArray("entries"));
            String marker = Files.readString(staging.resolve(".byclaw-owner"), StandardCharsets.UTF_8).trim();
            if (!marker.equals(ownerKey + ":" + bindingId)) throw new IllegalStateException("identity_mismatch");

            RuntimeBinding fenced = bindings.beginRestore(ownerKey, bindingId);
            Path live = ownerDirectory(ownerKey);
            Path aside = stateRoot.resolve(live.getFileName() + ".pre-restore-" + STAMP.format(clock.instant()));
            boolean moved = false;
            try {
                if (Files.exists(live, LinkOption.NOFOLLOW_LINKS)) {
                    Files.move(live, aside, StandardCopyOption.ATOMIC_MOVE);
                    moved = true;
                }
                Files.move(staging, live, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (IOException | RuntimeException e) {
                if (moved && !Files.exists(live, LinkOption.NOFOLLOW_LINKS)) Files.move(aside, live, StandardCopyOption.ATOMIC_MOVE);
                bindings.abortRestore(ownerKey, fenced.getGeneration(), "restore_failed: " + e.getMessage());
                throw new IllegalStateException("restore_failed", e);
            }
            JSONObject watermarks = manifest.getJSONObject("watermarks");
            String lineage = manifest.getString("outboxLineage");
            if (watermarks != null && lineage != null) {
                for (Map.Entry<String, Object> session : watermarks.entrySet()) {
                    long lastSeq = ((JSONObject) session.getValue()).getLongValue("runtimeLastSeq");
                    mapping.rebase(ownerKey, Long.parseLong(session.getKey()), lineage, lastSeq);
                }
            }
            bindings.completeRestore(ownerKey, fenced.getGeneration());
            log.info("Direct restore completed, owner={}, backupId={}, previousState={}", ownerKey, backupId, aside);
            JSONObject result = summary(manifest);
            result.put("previousState", moved ? aside.toString() : null);
            result.put("generation", fenced.getGeneration());
            return result;
        }
        catch (IOException e) {
            throw new IllegalStateException("archive_corrupt", e);
        }
        finally {
            deleteTree(staging);
        }
    }

    private Path ownerDirectory(String ownerKey) {
        return stateRoot.resolve(RuntimeOwnerScope.directoryNameOf(ownerKey));
    }

    private Path ownerBackups(String ownerKey) {
        return backupRoot.resolve(RuntimeOwnerScope.directoryNameOf(ownerKey));
    }

    private void archiveTree(Path source, TarArchiveOutputStream tar, JSONArray entries) throws IOException {
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(source)) {
            paths = walk.filter(path -> !path.equals(source)).sorted().toList();
        }
        for (Path path : paths) {
            String name = source.relativize(path).toString().replace('\\', '/');
            if (TRANSIENT.contains(path.getFileName().toString())) continue;
            JSONObject entry = new JSONObject(true);
            entry.put("path", name);
            if (Files.isSymbolicLink(path)) {
                Path target = Files.readSymbolicLink(path);
                TarArchiveEntry tarEntry = new TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK);
                tarEntry.setLinkName(target.toString());
                tar.putArchiveEntry(tarEntry);
                tar.closeArchiveEntry();
                entry.put("type", "symlink");
                entry.put("target", target.toString());
            }
            else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                TarArchiveEntry tarEntry = new TarArchiveEntry(name + "/");
                tarEntry.setMode(mode(path));
                tar.putArchiveEntry(tarEntry);
                tar.closeArchiveEntry();
                entry.put("type", "dir");
                entry.put("mode", PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)));
            }
            else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                TarArchiveEntry tarEntry = new TarArchiveEntry(path.toFile(), name);
                tarEntry.setMode(mode(path));
                MessageDigest digest = digest();
                tar.putArchiveEntry(tarEntry);
                try (InputStream in = new DigestInputStream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS), digest)) {
                    in.transferTo(tar);
                }
                tar.closeArchiveEntry();
                entry.put("type", "file");
                entry.put("size", tarEntry.getSize());
                entry.put("mode", PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)));
                entry.put("sha256", HexFormat.of().formatHex(digest.digest()));
            }
            else {
                continue; // sockets and pipes belong to a live process
            }
            entries.add(entry);
        }
    }

    private void extract(Path archive, Path staging, JSONArray expected) throws IOException {
        Map<String, JSONObject> manifest = new LinkedHashMap<>();
        for (Object item : expected) manifest.put(((JSONObject) item).getString("path"), (JSONObject) item);
        createPrivateDirectories(staging);
        Path base = staging.toRealPath();
        int seen = 0;
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new GzipCompressorInputStream(Files.newInputStream(archive)))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                String name = entry.getName().endsWith("/") ? entry.getName().substring(0, entry.getName().length() - 1) : entry.getName();
                JSONObject declared = manifest.get(name);
                Path target = base.resolve(name).normalize();
                if (declared == null || !target.startsWith(base) || target.equals(base)) throw new IllegalStateException("archive_corrupt");
                requireRealParents(base, target);
                if (entry.isSymbolicLink()) {
                    if (!"symlink".equals(declared.getString("type")) || !entry.getLinkName().equals(declared.getString("target"))) {
                        throw new IllegalStateException("archive_corrupt");
                    }
                    Files.createSymbolicLink(target, Path.of(entry.getLinkName()));
                }
                else if (entry.isDirectory()) {
                    if (!"dir".equals(declared.getString("type"))) throw new IllegalStateException("archive_corrupt");
                    Files.createDirectories(target);
                    Files.setPosixFilePermissions(target, PosixFilePermissions.fromString(declared.getString("mode")));
                }
                else if (entry.isFile()) {
                    if (!"file".equals(declared.getString("type"))) throw new IllegalStateException("archive_corrupt");
                    MessageDigest digest = digest();
                    try (OutputStream out = Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                        new DigestInputStream(tar, digest).transferTo(out);
                    }
                    if (!HexFormat.of().formatHex(digest.digest()).equals(declared.getString("sha256"))) {
                        throw new IllegalStateException("archive_corrupt");
                    }
                    Files.setPosixFilePermissions(target, PosixFilePermissions.fromString(declared.getString("mode")));
                }
                else {
                    throw new IllegalStateException("archive_corrupt");
                }
                if (!entry.isSymbolicLink()) {
                    Files.setAttribute(target, "unix:uid", guestUid, LinkOption.NOFOLLOW_LINKS);
                    Files.setAttribute(target, "unix:gid", guestGid, LinkOption.NOFOLLOW_LINKS);
                }
                seen++;
            }
        }
        catch (FileAlreadyExistsException e) {
            throw new IllegalStateException("archive_corrupt", e);
        }
        if (seen != manifest.size()) throw new IllegalStateException("archive_corrupt");
        Files.setPosixFilePermissions(staging, PosixFilePermissions.fromString("rwx------"));
        Files.setAttribute(staging, "unix:uid", guestUid, LinkOption.NOFOLLOW_LINKS);
        Files.setAttribute(staging, "unix:gid", guestGid, LinkOption.NOFOLLOW_LINKS);
    }

    /** No path written during a restore passes through a link created by the archive. */
    private static void requireRealParents(Path base, Path target) {
        for (Path parent = target.getParent(); parent != null && !parent.equals(base); parent = parent.getParent()) {
            if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("archive_corrupt");
            }
        }
    }

    private static String readLineage(Path source) throws IOException {
        Path lineage = source.resolve("direct/outbox/lineage");
        return Files.isRegularFile(lineage, LinkOption.NOFOLLOW_LINKS) ? Files.readString(lineage).trim() : null;
    }

    /** Per session: the runtime outbox's last sequence in this state, and where the platform had projected. */
    private static JSONObject watermarks(Path source, JSONObject platform) throws IOException {
        JSONObject result = new JSONObject(true);
        Path outbox = source.resolve("direct/outbox");
        if (!Files.isDirectory(outbox, LinkOption.NOFOLLOW_LINKS)) return result;
        try (DirectoryStream<Path> metas = Files.newDirectoryStream(outbox, "*.meta.json")) {
            for (Path meta : metas) {
                JSONObject value = JSON.parseObject(Files.readString(meta));
                long lastSeq = value.getLongValue("lastSeq");
                Path log = outbox.resolve(meta.getFileName().toString().replace(".meta.json", ".jsonl"));
                if (Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)) {
                    for (String line : Files.readAllLines(log)) {
                        if (line.isBlank()) continue;
                        try {
                            lastSeq = Math.max(lastSeq, JSON.parseObject(line).getLongValue("seq"));
                        }
                        catch (RuntimeException ignored) {
                            // a torn last line was never acknowledged as durable
                        }
                    }
                }
                JSONObject watermark = new JSONObject(true);
                watermark.put("runtimeLastSeq", lastSeq);
                result.put(value.getString("sessionId"), watermark);
            }
        }
        JSONArray progress = platform.getJSONArray("progress");
        if (progress != null) {
            for (Object item : progress) {
                JSONObject row = (JSONObject) JSON.toJSON(item);
                JSONObject watermark = result.getJSONObject(row.getString("sessionId"));
                if (watermark != null) watermark.put("platformThroughSeq", row.getLongValue("throughSeq"));
            }
        }
        return result;
    }

    private static JSONObject summary(JSONObject manifest) {
        JSONObject summary = new JSONObject(true);
        for (String key : List.of("backupId", "createdAt", "ownerScope", "bindingId", "generation", "templateId", "files",
            "bytes", "archiveSha256", "outboxLineage", "watermarks")) {
            summary.put(key, manifest.get(key));
        }
        return summary;
    }

    private static int mode(Path path) throws IOException {
        int mode = 0;
        for (PosixFilePermission permission : Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)) {
            mode |= 1 << (8 - permission.ordinal());
        }
        return mode;
    }

    private static void createPrivateDirectories(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
    }

    private static OutputStream privateFile(Path file) throws IOException {
        OutputStream out = Files.newOutputStream(file, java.nio.file.StandardOpenOption.CREATE_NEW);
        restrict(file);
        return out;
    }

    private static void restrict(Path file) throws IOException {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
        catch (IOException e) {
            log.warn("Direct backup cleanup failed, path={}", dir);
        }
    }
}
