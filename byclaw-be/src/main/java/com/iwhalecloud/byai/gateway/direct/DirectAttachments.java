package com.iwhalecloud.byai.gateway.direct;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

import com.iwhalecloud.byai.state.domain.chat.dto.AssistantChatDto;
import com.iwhalecloud.byai.state.domain.chat.model.MessageFileDto;
import com.iwhalecloud.byai.state.domain.chat.service.ChatProcessContext;

import lombok.extern.slf4j.Slf4j;

/**
 * Attachments of a Direct turn. An attachment must be the current user's own upload into this
 * session (the upload is its receipt); it is copied from the user's file storage into the owner's
 * state mount, and the turn names the path the runtime sees. Images need a model that accepts
 * images. Anything else is refused before the turn runs.
 */
@Slf4j
public class DirectAttachments {

    private static final Pattern RECEIPT = Pattern.compile("^/(?:by/)?\\.sessions/(\\d+)/([^/]+)$");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp");

    /** The current user's uploaded file at {@code /.sessions/<session>/<name>}; null when absent. */
    @FunctionalInterface
    public interface Uploads {
        InputStream open(String path);
    }

    private final DirectSessionFiles files;
    private final Uploads uploads;
    private final Predicate<AssistantChatDto> acceptsImages;

    public DirectAttachments(DirectSessionFiles files, Uploads uploads, Predicate<AssistantChatDto> acceptsImages) {
        this.files = files;
        this.uploads = uploads;
        this.acceptsImages = acceptsImages;
    }

    /** @return null when every attachment is admitted (and now at its runtime path), otherwise why not. */
    public String admit(ChatProcessContext ctx) {
        AssistantChatDto dto = ctx.getAssistantChatDto();
        List<MessageFileDto> attached = dto == null ? null : dto.getFiles();
        if (attached == null || attached.isEmpty()) return null;
        DirectSessionFiles.Space space = files.space(ctx.sessionId).orElse(null);
        if (space == null) return "附件不属于本会话，已拒绝";
        for (MessageFileDto file : attached) {
            Matcher receipt = RECEIPT.matcher(StringUtils.defaultString(file.getFilePath()).replace('\\', '/'));
            if (!receipt.matches() || !String.valueOf(ctx.sessionId).equals(receipt.group(1))
                || receipt.group(2).equals("..") || receipt.group(2).equals(".")) {
                log.warn("Direct attachment refused, not an upload of this session, sessionId={}", ctx.sessionId);
                return "附件不属于本会话，已拒绝";
            }
            if (isImage(file) && !acceptsImages.test(dto)) return "当前模型不支持图片输入，请切换支持图片的模型后重试";
            String name = receipt.group(2);
            String runtimePath = "/by/.sessions/" + ctx.sessionId + "/" + name;
            if (!space.exists(runtimePath)) {
                try (InputStream upload = uploads.open("/.sessions/" + ctx.sessionId + "/" + name)) {
                    if (upload == null) return "附件不存在或已失效，请重新上传";
                    byte[] bytes = upload.readAllBytes();
                    space.write(runtimePath, new java.io.ByteArrayInputStream(bytes), bytes.length,
                        StringUtils.defaultIfBlank(file.getFileType(), "application/octet-stream"));
                }
                catch (IOException | RuntimeException e) {
                    log.warn("Direct attachment unavailable, sessionId={}, errorType={}", ctx.sessionId,
                        e.getClass().getSimpleName());
                    return "附件不存在或已失效，请重新上传";
                }
            }
            file.setFilePath(runtimePath);
        }
        return null;
    }

    private static boolean isImage(MessageFileDto file) {
        String type = StringUtils.defaultString(file.getFileType()).toLowerCase(Locale.ROOT);
        String extension = FilenameUtils.getExtension(StringUtils.defaultString(file.getFileName(), file.getFilePath()))
            .toLowerCase(Locale.ROOT);
        return type.equals("image") || type.startsWith("image/") || IMAGE_EXTENSIONS.contains(extension);
    }
}
