package com.iwhalecloud.byai.gateway.direct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.iwhalecloud.byai.common.login.auth.CurrentUserHolder;
import com.iwhalecloud.byai.common.login.bean.LoginInfo;
import com.iwhalecloud.byai.common.storage.impl.LocalStorageService;
import com.iwhalecloud.byai.gateway.direct.mapper.RuntimeSessionRouteMapper;

class DirectSessionFilesTest {

    private static final RuntimeOwnerScope OWNER = new RuntimeOwnerScope(100L, "u1", 1L);

    @TempDir
    Path root;

    private DirectSessionFiles files;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(root.resolve(OWNER.directoryName())); // prepared with the binding
        RuntimeSessionRouteMapper routes = mock(RuntimeSessionRouteMapper.class);
        when(routes.selectBackend(9000L)).thenReturn(DirectRouteSelector.DIRECT);
        when(routes.selectOwner(9000L)).thenReturn(OWNER.key());
        files = new DirectSessionFiles(new DirectRouteSelector(new DirectRuntimeProperties(), routes),
            LocalStorageService.confined(root.toString()));
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

    @Test
    void aDirectSessionsFilesLiveInItsOwnersStateMount() throws Exception {
        DirectSessionFiles.Space space = files.spaceOf("/by/.sessions/9000/report.md").orElseThrow();
        space.write("/.sessions/9000/report.md", new ByteArrayInputStream("hi".getBytes(StandardCharsets.UTF_8)), 2,
            "text/markdown");
        Path stored = root.resolve(OWNER.directoryName()).resolve("by/.sessions/9000/report.md");
        assertThat(Files.readString(stored)).isEqualTo("hi");
        assertThat(new String(space.read("/by/.sessions/9000/report.md").readAllBytes(), StandardCharsets.UTF_8))
            .isEqualTo("hi");
        assertThat(files.spaceOf("/by/.sessions/1234/x")).as("a Legacy session keeps its storage").isEmpty();
        assertThat(files.spaceOf("/by/projects/x")).isEmpty();
    }

    @Test
    void anotherLoginCannotReachTheOwnersFiles() {
        LoginInfo other = new LoginInfo();
        other.setUserId(200L);
        other.setUserCode("u2");
        other.setEnterpriseId(1L);
        CurrentUserHolder.setLoginInfo(other);
        assertThatThrownBy(() -> files.spaceOf("/by/.sessions/9000/report.md"))
            .isInstanceOf(com.iwhalecloud.byai.state.common.exception.BdpRuntimeException.class);
    }

    @Test
    void aLinkWrittenInsideTheRuntimeNeverLeadsOutOfTheMount() throws Exception {
        Path session = Files.createDirectories(root.resolve(OWNER.directoryName()).resolve("by/.sessions/9000"));
        Path outside = Files.writeString(root.resolve("host-secret.txt"), "host");
        Files.createSymbolicLink(session.resolve("leak.txt"), outside);
        DirectSessionFiles.Space space = files.space(9000L).orElseThrow();
        assertThatThrownBy(() -> space.read("/by/.sessions/9000/leak.txt")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEmptiedOrReplacedOwnerDirectoryIsNeverServedAsTheSessionsFiles() {
        DirectSessionFiles lost = new DirectSessionFiles(new DirectRouteSelector(new DirectRuntimeProperties(), routes()),
            LocalStorageService.confined(root.toString()), owner -> false);
        assertThatThrownBy(() -> lost.spaceOf("/by/.sessions/9000/report.md")).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("数据不可用");
    }

    private static RuntimeSessionRouteMapper routes() {
        RuntimeSessionRouteMapper routes = mock(RuntimeSessionRouteMapper.class);
        when(routes.selectBackend(9000L)).thenReturn(DirectRouteSelector.DIRECT);
        when(routes.selectOwner(9000L)).thenReturn(OWNER.key());
        return routes;
    }
}
