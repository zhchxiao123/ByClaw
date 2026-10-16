package com.iwhalecloud.byai.gateway.direct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.iwhalecloud.byai.gateway.direct.persistence.RuntimeBinding;

class DirectBackupsTest {

    private static final String OWNER = "u100.e0";

    @TempDir Path root;
    private Path ownerDir;
    private final List<String> calls = new ArrayList<>();
    private final List<String> rebased = new ArrayList<>();
    private String quiescedError;
    private DirectBackups backups;

    @BeforeEach
    void setUp() throws Exception {
        ownerDir = root.resolve(RuntimeOwnerScope.directoryNameOf(OWNER));
        Files.createDirectories(ownerDir.resolve("dsh-home/sessions/s1"));
        Files.createDirectories(ownerDir.resolve("direct/outbox"));
        Files.createDirectories(ownerDir.resolve("by/.sessions/9000"));
        Files.createDirectories(ownerDir.resolve("dsh-home/browser/user-data"));
        Files.writeString(ownerDir.resolve(".byclaw-owner"), OWNER + ":7");
        Files.writeString(ownerDir.resolve("dsh-home/sessions/s1/session.v3.jsonl.zstd"), "log-v1");
        Files.writeString(ownerDir.resolve("direct/outbox/lineage"), "L1");
        Files.writeString(ownerDir.resolve("direct/outbox/abc.meta.json"), "{\"sessionId\":\"9000\",\"lastSeq\":40}");
        Files.writeString(ownerDir.resolve("direct/outbox/abc.jsonl"), "{\"seq\":41,\"event\":{}}\n{\"seq\":42,\"event\":{}}\n");
        Files.writeString(ownerDir.resolve("by/.sessions/9000/result.md"), "result v1");
        Files.createSymbolicLink(ownerDir.resolve("dsh-home/browser/user-data/SingletonLock"), Path.of("host-123"));
        int uid = (Integer) Files.getAttribute(root, "unix:uid");
        int gid = (Integer) Files.getAttribute(root, "unix:gid");
        backups = new DirectBackups(root, root.resolve(".backups"), bindings(), mapping(), uid, gid);
    }

    private DirectBackups.Bindings bindings() {
        return new DirectBackups.Bindings() {
            @Override
            public <T> T whileQuiesced(String ownerKey, Function<RuntimeBinding, T> work) {
                if (quiescedError != null) throw new IllegalStateException(quiescedError);
                calls.add("quiesced");
                return work.apply(binding(7L));
            }

            @Override
            public RuntimeBinding beginRestore(String ownerKey, long bindingId) {
                calls.add("begin:" + bindingId);
                return binding(bindingId);
            }

            @Override
            public void completeRestore(String ownerKey, long generation) {
                calls.add("complete:" + generation);
            }

            @Override
            public void abortRestore(String ownerKey, long generation, String reason) {
                calls.add("abort:" + reason);
            }
        };
    }

    private static RuntimeBinding binding(long bindingId) {
        RuntimeBinding binding = new RuntimeBinding();
        binding.setBindingId(bindingId);
        binding.setOwnerScope(OWNER);
        binding.setGeneration(5L);
        binding.setState("paused");
        binding.setTemplateId("tpl-1");
        binding.setSandboxId("sbx-1");
        binding.setTrafficToken("traffic-secret");
        binding.setEnvdToken("envd-secret");
        return binding;
    }

    private DirectBackups.Mapping mapping() {
        return new DirectBackups.Mapping() {
            @Override
            public JSONObject export(String ownerKey) {
                JSONObject mapping = new JSONObject();
                mapping.put("routes", List.of(JSONObject.parseObject("{\"sessionId\":9000,\"backend\":\"direct\"}")));
                mapping.put("progress", List.of(JSONObject.parseObject("{\"sessionId\":9000,\"throughSeq\":42,\"lineage\":\"L1\"}")));
                return mapping;
            }

            @Override
            public void rebase(String ownerKey, long sessionId, String lineage, long throughSeq) {
                rebased.add(sessionId + ":" + lineage + ":" + throughSeq);
            }
        };
    }

    @Test
    void aBackupOfAQuiescedRuntimeHoldsItsStateMappingAndWatermarksButNoSecrets() throws Exception {
        JSONObject manifest = backups.backup(OWNER);

        Path dir = root.resolve(".backups").resolve(RuntimeOwnerScope.directoryNameOf(OWNER)).resolve(manifest.getString("backupId"));
        assertThat(dir.resolve("state.tar.gz")).exists();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("state.tar.gz")))).isEqualTo("rw-------");
        JSONObject stored = JSON.parseObject(Files.readString(dir.resolve("manifest.json")));
        assertThat(stored.getString("ownerScope")).isEqualTo(OWNER);
        assertThat(stored.getLong("bindingId")).isEqualTo(7L);
        assertThat(stored.getLong("generation")).isEqualTo(5L);
        assertThat(stored.getJSONArray("entries").toJavaList(JSONObject.class)).extracting(e -> e.getString("path"))
            .contains(".byclaw-owner", "dsh-home/sessions/s1/session.v3.jsonl.zstd", "by/.sessions/9000/result.md")
            .doesNotContain("dsh-home/browser/user-data/SingletonLock");
        assertThat(stored.getJSONObject("watermarks").getJSONObject("9000").getLong("runtimeLastSeq")).isEqualTo(42L);
        assertThat(stored.getJSONObject("watermarks").getJSONObject("9000").getLong("platformThroughSeq")).isEqualTo(42L);
        assertThat(stored.getString("outboxLineage")).isEqualTo("L1");
        String everything = Files.readString(dir.resolve("manifest.json")) + Files.readString(dir.resolve("mapping.json"));
        assertThat(everything).doesNotContain("traffic-secret").doesNotContain("envd-secret");
        assertThat(calls).containsExactly("quiesced");
    }

    @Test
    void aRuntimeThatIsNotQuiescedIsNotBackedUp() {
        quiescedError = "runtime_running";
        assertThatThrownBy(() -> backups.backup(OWNER)).hasMessageContaining("runtime_running");
        assertThat(backups.list(OWNER)).isEmpty();
    }

    @Test
    void restoreReplacesTheStateKeepsTheOriginalAsideFencesAndRebasesWatermarks() throws Exception {
        String backupId = backups.backup(OWNER).getString("backupId");
        Files.writeString(ownerDir.resolve("by/.sessions/9000/result.md"), "result v2 after the backup");
        Files.writeString(ownerDir.resolve("by/.sessions/9000/later.md"), "made after the backup");

        JSONObject result = backups.restore(OWNER, backupId);

        assertThat(Files.readString(ownerDir.resolve("by/.sessions/9000/result.md"))).isEqualTo("result v1");
        assertThat(ownerDir.resolve("by/.sessions/9000/later.md")).doesNotExist();
        assertThat(Files.readString(ownerDir.resolve(".byclaw-owner"))).isEqualTo(OWNER + ":7");
        Path aside = Path.of(result.getString("previousState"));
        assertThat(Files.readString(aside.resolve("by/.sessions/9000/later.md"))).as("the original is kept, not overwritten")
            .isEqualTo("made after the backup");
        assertThat(calls).containsExactly("quiesced", "begin:7", "complete:5");
        assertThat(rebased).containsExactly("9000:L1:42");
    }

    @Test
    void aCorruptArchiveIsRefusedBeforeAnythingIsTouched() throws Exception {
        String backupId = backups.backup(OWNER).getString("backupId");
        Path archive = root.resolve(".backups").resolve(RuntimeOwnerScope.directoryNameOf(OWNER)).resolve(backupId).resolve("state.tar.gz");
        byte[] bytes = Files.readAllBytes(archive);
        bytes[bytes.length / 2] ^= 0x5a;
        Files.write(archive, bytes);

        assertThatThrownBy(() -> backups.restore(OWNER, backupId)).hasMessageContaining("archive_corrupt");
        assertThat(calls).containsExactly("quiesced");
        assertThat(Files.readString(ownerDir.resolve("by/.sessions/9000/result.md"))).isEqualTo("result v1");
    }

    @Test
    void aMissingArchiveOrAnotherOwnersBackupIsRefused() throws Exception {
        assertThatThrownBy(() -> backups.restore(OWNER, "20260101T000000Z-missing")).hasMessageContaining("backup_missing");
        String backupId = backups.backup(OWNER).getString("backupId");
        Path dir = root.resolve(".backups").resolve(RuntimeOwnerScope.directoryNameOf(OWNER)).resolve(backupId);
        JSONObject manifest = JSON.parseObject(Files.readString(dir.resolve("manifest.json")));
        manifest.put("ownerScope", "u200.e0");
        Files.writeString(dir.resolve("manifest.json"), manifest.toJSONString());

        assertThatThrownBy(() -> backups.restore(OWNER, backupId)).hasMessageContaining("identity_mismatch");
        assertThat(calls).containsExactly("quiesced");
    }

    @Test
    void anArchiveEntryLeavingTheOwnerDirectoryIsRefused() throws Exception {
        String backupId = backups.backup(OWNER).getString("backupId");
        Path dir = root.resolve(".backups").resolve(RuntimeOwnerScope.directoryNameOf(OWNER)).resolve(backupId);
        try (OutputStream out = Files.newOutputStream(dir.resolve("state.tar.gz"));
             TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(out))) {
            byte[] evil = "x".getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry entry = new TarArchiveEntry("../evil.txt");
            entry.setSize(evil.length);
            tar.putArchiveEntry(entry);
            tar.write(evil);
            tar.closeArchiveEntry();
        }
        JSONObject manifest = JSON.parseObject(Files.readString(dir.resolve("manifest.json")));
        manifest.put("archiveSha256", DirectProtocol.sha256Hex(Files.readAllBytes(dir.resolve("state.tar.gz"))));
        Files.writeString(dir.resolve("manifest.json"), manifest.toJSONString());

        assertThatThrownBy(() -> backups.restore(OWNER, backupId)).hasMessageContaining("archive_corrupt");
        assertThat(root.resolve("evil.txt")).doesNotExist();
        assertThat(calls).containsExactly("quiesced");
        assertThat(Files.exists(ownerDir, LinkOption.NOFOLLOW_LINKS)).isTrue();
    }
}
