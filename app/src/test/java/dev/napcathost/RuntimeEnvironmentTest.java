package dev.napcathost;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class RuntimeEnvironmentTest {
    @Test
    public void safeResolveAcceptsPathInsideRoot() throws Exception {
        File root = new File("build/test-root").getCanonicalFile();
        File resolved = RuntimeEnvironment.safeResolve(root, "./usr/bin/bash");
        assertEquals(new File(root, "usr/bin/bash").getCanonicalFile(), resolved);
    }

    @Test
    public void safeResolveRejectsParentTraversal() throws Exception {
        File root = new File("build/test-root").getCanonicalFile();
        assertThrows(IOException.class,
                () -> RuntimeEnvironment.safeResolve(root, "../../outside"));
    }

    @Test
    public void safeResolveRejectsBackslashTraversal() throws Exception {
        File root = new File("build/test-root").getCanonicalFile();
        assertThrows(IOException.class,
                () -> RuntimeEnvironment.safeResolve(root, "..\\outside"));
    }
}
