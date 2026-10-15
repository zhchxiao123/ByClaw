package com.iwhalecloud.byai.gateway.direct;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;

class DirectRuntimeConfigurationTest {

    /** TransactionAdviceConfig wraps beans named *Service/*ServiceImpl/*Runner/*Dao in REQUIRED transactions. */
    @Test
    void lifecycleBeansCommitEachTransitionIndependently() {
        String[] proxied = Arrays.stream(DirectRuntimeConfiguration.class.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(Bean.class))
            .map(Method::getName)
            .filter(name -> name.endsWith("Service") || name.endsWith("ServiceImpl") || name.endsWith("Runner")
                || name.endsWith("Dao"))
            .toArray(String[]::new);
        assertThat(proxied).isEmpty();
    }

    @Test
    void ownerStateIsHeldOnlyByItsMarkedNonEmptyDirectory(@TempDir Path root) throws Exception {
        DirectRuntimeProperties.Runtime runtime = new DirectRuntimeProperties().getRuntime();
        runtime.setLocalMountRoot(root.toString());
        var directories = new DirectRuntimeConfiguration.OwnerStateDirectories(runtime);
        RuntimeOwnerScope owner = new RuntimeOwnerScope(100L, "u1", 1L);
        Path dir = root.resolve(owner.directoryName());
        assertThat(directories.holds(owner, 7L)).as("missing directory").isFalse();
        assertThat(Files.exists(dir)).as("checking never creates it").isFalse();
        Files.createDirectories(dir);
        assertThat(directories.holds(owner, 7L)).as("empty directory").isFalse();
        Files.writeString(dir.resolve("state.md"), "legacy");
        assertThat(directories.holds(owner, 7L)).as("legacy state is adopted once").isTrue();
        assertThat(Files.readString(dir.resolve(".byclaw-owner"))).isEqualTo("u100.e1:7");
        assertThat(directories.holds(owner, 8L)).as("claimed by another binding").isFalse();
    }
}
