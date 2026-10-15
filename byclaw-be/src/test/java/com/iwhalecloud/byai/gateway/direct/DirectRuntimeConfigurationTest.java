package com.iwhalecloud.byai.gateway.direct;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
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
}
