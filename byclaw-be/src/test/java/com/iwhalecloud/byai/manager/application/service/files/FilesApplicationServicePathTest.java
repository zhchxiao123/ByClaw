package com.iwhalecloud.byai.manager.application.service.files;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FilesApplicationServicePathTest {

    @Test
    void previewPathsInsideASessionNameThatSession() {
        assertThat(FilesApplicationService.sessionOfPath("/.sessions/9000/a.png")).isEqualTo(9000L);
        assertThat(FilesApplicationService.sessionOfPath("/by/.sessions/9000/dir/a.png")).isEqualTo(9000L);
        assertThat(FilesApplicationService.sessionOfPath("byclaw-u1/by/.sessions/9000/a.png")).isEqualTo(9000L);
        assertThat(FilesApplicationService.sessionOfPath("/by/projects/a.png")).isNull();
        assertThat(FilesApplicationService.sessionOfPath("/.sessions/9000/")).isNull();
        assertThat(FilesApplicationService.sessionOfPath(null)).isNull();
    }
}
