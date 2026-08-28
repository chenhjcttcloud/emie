package com.emie.designpm.service;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class SubTaskCommandBoundaryTest {
    @Test
    void defaultImplementationOwnsCommandsWithoutProjectServiceDelegate() {
        assertTrue(Arrays.stream(DefaultSubTaskCommandService.class.getDeclaredFields())
                .map(Field::getType).noneMatch(ProjectService.class::equals));
        Set<String> declared = Arrays.stream(DefaultSubTaskCommandService.class.getDeclaredMethods())
                .map(Method::getName).collect(Collectors.toSet());
        Arrays.stream(SubTaskCommandService.class.getDeclaredMethods())
                .map(Method::getName).forEach(name -> assertTrue(declared.contains(name), name));
    }

    @Test
    void lifecycleImplementationOwnsCommandsWithoutProjectServiceDelegate() {
        assertTrue(Arrays.stream(DefaultProjectLifecycleCommandService.class.getDeclaredFields())
                .map(Field::getType).noneMatch(ProjectService.class::equals));
        Set<String> declared = Arrays.stream(DefaultProjectLifecycleCommandService.class.getDeclaredMethods())
                .map(Method::getName).collect(Collectors.toSet());
        Arrays.stream(ProjectLifecycleCommandService.class.getDeclaredMethods())
                .map(Method::getName).forEach(name -> assertTrue(declared.contains(name), name));
    }
}
