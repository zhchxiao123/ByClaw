package com.iwhalecloud.byai.gateway.direct;

import java.io.InputStream;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.iwhalecloud.byai.common.login.auth.CurrentUserHolder;
import com.iwhalecloud.byai.common.storage.ObjectStorage;
import com.iwhalecloud.byai.common.storage.model.StorageLocation;
import com.iwhalecloud.byai.state.application.service.filebrowser.FileBrowserProvider;
import com.iwhalecloud.byai.state.application.service.filebrowser.MinioFileBrowserProvider;

/**
 * Files of Direct sessions. The runtime works in its owner's state mount, so the platform reads
 * and writes a Direct session's attachments, workspace, diffs and artifacts there, on the host
 * side, without waking the runtime. Legacy sessions keep the user's file storage. Only the owner
 * scope reaches a Direct session's files, and links written by the runtime never lead out of the
 * mount.
 */
public class DirectSessionFiles {

    private static final Pattern SESSION_PATH = Pattern.compile("^/(?:by/)?\\.sessions/(\\d+)(?:/.*)?$");
    private static final String NAMESPACE = "workspace";

    private final DirectRouteSelector routes;
    private final ObjectStorage storage;
    private final java.util.function.Predicate<RuntimeOwnerScope> stateHeld;

    /** The users' file storage when the runtime works in it (see {@code userFsHostRoot}); null otherwise. */
    private ObjectStorage userFs;

    public DirectSessionFiles(DirectRouteSelector routes, ObjectStorage storage) {
        this(routes, storage, owner -> true);
    }

    /** The runtime works in the user's own file storage: a Direct session's files are the user's {@code /by}. */
    public DirectSessionFiles withUserFs(ObjectStorage userFs) {
        this.userFs = userFs;
        return this;
    }

    /**
     * @param stateHeld whether the owner's directory still holds its binding's state; a missing,
     *                  emptied or replaced directory is never served as the session's files.
     */
    public DirectSessionFiles(DirectRouteSelector routes, ObjectStorage storage,
                              java.util.function.Predicate<RuntimeOwnerScope> stateHeld) {
        this.routes = routes;
        this.storage = storage;
        this.stateHeld = stateHeld;
    }

    public boolean isDirect(Long sessionId) {
        return sessionId != null && routes.isDirect(sessionId);
    }

    /** Whether the current login owns this Direct session; never throws. */
    public boolean isOwner(Long sessionId) {
        try {
            return space(sessionId).isPresent();
        }
        catch (RuntimeException e) {
            return false;
        }
    }

    /** The owner space of a Direct session for the current login; empty for a Legacy session. */
    public Optional<Space> space(Long sessionId) {
        if (sessionId == null) return Optional.empty();
        RuntimeOwnerScope owner = routes.requireOwnerIfDirect(sessionId, CurrentUserHolder.getLoginInfo());
        if (owner == null) return Optional.empty();
        if (userFs != null) {
            return Optional.of(new Space(userFs,
                com.iwhalecloud.byai.common.storage.util.UserBucketNameResolver.buildUserBucketName(owner.userCode())));
        }
        if (!stateHeld.test(owner)) throw new IllegalStateException(RuntimeFault.STATE_MISSING.message());
        return Optional.of(new Space(storage, owner.directoryName()));
    }

    /** As {@link #space(Long)} for a path inside a session directory ({@code /by/.sessions/<id>/...}). */
    public Optional<Space> spaceOf(String path) {
        Long sessionId = sessionOf(path);
        return sessionId == null ? Optional.empty() : space(sessionId);
    }

    static Long sessionOf(String path) {
        Matcher matcher = SESSION_PATH.matcher(path == null ? "" : path.replace('\\', '/'));
        return matcher.matches() ? Long.valueOf(matcher.group(1)) : null;
    }

    /** One owner's state mount, addressed with the same {@code /by/...} paths the runtime sees. */
    public record Space(ObjectStorage storage, String bucket) {

        public FileBrowserProvider browser() {
            return new MinioFileBrowserProvider(storage, userCode -> bucket);
        }

        public InputStream read(String path) {
            return storage.get(location(path));
        }

        /** Size, type and time of a regular file; null when absent. */
        public com.iwhalecloud.byai.common.storage.model.FileMetadata metadata(String path) {
            return storage.metadata(location(path));
        }

        public boolean exists(String path) {
            return storage.exists(location(path));
        }

        public void write(String path, InputStream content, long size, String contentType) {
            storage.put(location(path), content, size, contentType);
        }

        private StorageLocation location(String path) {
            return StorageLocation.of(NAMESPACE, bucket, key(path));
        }

        /** {@code /by/x} and {@code /x} both name {@code by/x} under the owner directory. */
        static String key(String path) {
            String normalized = path == null ? "" : path.replace('\\', '/');
            while (normalized.startsWith("/")) normalized = normalized.substring(1);
            return normalized.startsWith("by/") ? normalized : "by/" + normalized;
        }
    }
}
