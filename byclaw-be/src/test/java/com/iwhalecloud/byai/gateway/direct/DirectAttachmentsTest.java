package com.iwhalecloud.byai.gateway.direct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.iwhalecloud.byai.common.login.auth.CurrentUserHolder;
import com.iwhalecloud.byai.common.login.bean.LoginInfo;
import com.iwhalecloud.byai.common.storage.impl.LocalStorageService;
import com.iwhalecloud.byai.gateway.direct.mapper.RuntimeSessionRouteMapper;
import com.iwhalecloud.byai.state.domain.chat.dto.AssistantChatDto;
import com.iwhalecloud.byai.state.domain.chat.model.MessageFileDto;
import com.iwhalecloud.byai.state.domain.chat.service.ChatProcessContext;

class DirectAttachmentsTest {

    private static final RuntimeOwnerScope OWNER = new RuntimeOwnerScope(100L, "u1", 1L);

    @TempDir
    Path root;

    private final Map<String, String> uploads = new HashMap<>();
    private boolean vision;
    private DirectAttachments attachments;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(root.resolve(OWNER.directoryName()));
        RuntimeSessionRouteMapper routes = mock(RuntimeSessionRouteMapper.class);
        when(routes.selectBackend(9000L)).thenReturn(DirectRouteSelector.DIRECT);
        when(routes.selectOwner(9000L)).thenReturn(OWNER.key());
        DirectSessionFiles files = new DirectSessionFiles(new DirectRouteSelector(new DirectRuntimeProperties(), routes),
            LocalStorageService.confined(root.toString()));
        attachments = new DirectAttachments(files, path -> uploads.containsKey(path)
            ? new ByteArrayInputStream(uploads.get(path).getBytes(StandardCharsets.UTF_8)) : null, dto -> vision);
        LoginInfo login = new LoginInfo();
        login.setUserId(100L);
        login.setUserCode("u1");
        login.setEnterpriseId(1L);
        CurrentUserHolder.setLoginInfo(login);
    }

    @AfterEach
    void tearDown() {
        CurrentUserHolder.setLoginInfo(null);
    }

    private static ChatProcessContext turn(MessageFileDto... files) {
        AssistantChatDto dto = new AssistantChatDto();
        dto.setFiles(new java.util.ArrayList<>(List.of(files)));
        ChatProcessContext ctx = new ChatProcessContext(new ByteArrayOutputStream(), dto);
        ctx.setSessionId(9000L);
        return ctx;
    }

    private static MessageFileDto file(String path, String type) {
        MessageFileDto file = new MessageFileDto();
        file.setFilePath(path);
        file.setFileName(path.substring(path.lastIndexOf('/') + 1));
        file.setFileType(type);
        return file;
    }

    @Test
    void anOwnUploadIsCopiedIntoTheOwnersMountAndNamedByItsRuntimePath() throws Exception {
        uploads.put("/.sessions/9000/notes.txt", "attachment body");
        MessageFileDto notes = file("/.sessions/9000/notes.txt", "file");
        assertThat(attachments.admit(turn(notes))).isNull();
        assertThat(notes.getFilePath()).isEqualTo("/by/.sessions/9000/notes.txt");
        assertThat(Files.readString(root.resolve(OWNER.directoryName()).resolve("by/.sessions/9000/notes.txt")))
            .isEqualTo("attachment body");
    }

    @Test
    void anythingButThisSessionsOwnUploadIsRefused() {
        assertThat(attachments.admit(turn(file("/.sessions/9001/notes.txt", "file")))).as("another session's receipt")
            .contains("不属于本会话");
        assertThat(attachments.admit(turn(file("/etc/passwd", "file")))).contains("不属于本会话");
        assertThat(attachments.admit(turn(file("/.sessions/9000/../9001/x", "file")))).contains("不属于本会话");
        assertThat(attachments.admit(turn(file("/.sessions/9000/never-uploaded.txt", "file")))).contains("不存在");
    }

    @Test
    void imagesNeedAModelThatAcceptsThem() {
        uploads.put("/.sessions/9000/chart.png", "png");
        assertThat(attachments.admit(turn(file("/.sessions/9000/chart.png", "image")))).contains("不支持图片");
        vision = true;
        assertThat(attachments.admit(turn(file("/.sessions/9000/chart.png", "image")))).isNull();
    }
}
