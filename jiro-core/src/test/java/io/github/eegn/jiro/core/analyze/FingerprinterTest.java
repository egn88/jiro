package io.github.eegn.jiro.core.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eegn.jiro.core.compile.CompilationResult;
import io.github.eegn.jiro.core.compile.IncrementalCompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FingerprinterTest {

    @TempDir
    Path workspace;

    private Path sources;
    private Path classes;
    private Fingerprinter fingerprinter;

    @BeforeEach
    void setUp() throws IOException {
        sources = Files.createDirectories(workspace.resolve("src/t"));
        classes = Files.createDirectories(workspace.resolve("classes"));
        fingerprinter = new Fingerprinter();

        // An annotation carrying an enum and an array attribute. ASM models the enum value as a
        // String[], which is what made the digest non-deterministic; keeping it in every fixture
        // means the regression cannot come back unnoticed.
        write("Kind.java", "package t; public enum Kind { A, B }");
        write("Mode.java", """
                package t;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                public @interface Mode { Kind value(); String[] tags() default {}; int weight() default 1; }
                """);
    }

    @Test
    void scanningTheSameClassesTwiceProducesNoDifference() throws IOException {
        write("Sample.java", sample("return a + b;"));
        compile();

        FingerprintStore store = new FingerprintStore();
        store.update(fingerprinter.scan(classes));
        ChangeSet secondScan = store.update(fingerprinter.scan(classes));

        assertTrue(secondScan.isEmpty(),
                "an unchanged output directory must diff clean, but reported: " + secondScan.describe());
    }

    @Test
    void commentsAndFormattingDoNotChangeTheFingerprint() throws IOException {
        write("Sample.java", sample("return a + b;"));
        compile();
        FingerprintStore store = new FingerprintStore();
        store.update(fingerprinter.scan(classes));

        write("Sample.java", sample("""
                // a comment that changes nothing

                    return a + b;"""));
        compile();

        ChangeSet changes = store.update(fingerprinter.scan(classes));
        assertTrue(changes.isEmpty(),
                "reformatting must select nothing, but reported: " + changes.describe());
    }

    @Test
    void changingAMethodBodyChangesExactlyThatMethod() throws IOException {
        write("Sample.java", sample("return a + b;"));
        compile();
        FingerprintStore store = new FingerprintStore();
        store.update(fingerprinter.scan(classes));

        write("Sample.java", sample("return a - b;"));
        compile();

        ChangeSet changes = store.update(fingerprinter.scan(classes));
        assertEquals(List.of("t/Sample#add(II)I"), List.copyOf(changes.changedMethods()));
        assertTrue(changes.abiChangedClasses().isEmpty(), "a body edit is not an ABI change");
    }

    @Test
    void changingAMethodSignatureIsAnAbiChange() throws IOException {
        write("Sample.java", sample("return a + b;"));
        compile();
        FingerprintStore store = new FingerprintStore();
        store.update(fingerprinter.scan(classes));

        write("Sample.java", sample("return a + b;").replace("int add(int a, int b)", "long add(int a, int b)")
                .replace("return a + b;", "return (long) a + b;"));
        compile();

        ChangeSet changes = store.update(fingerprinter.scan(classes));
        assertTrue(changes.abiChangedClasses().contains("t/Sample"),
                "a changed return type must trip the firewall");
    }

    @Test
    void changingAnAnnotationValueChangesTheMethod() throws IOException {
        write("Sample.java", sample("return a + b;"));
        compile();
        FingerprintStore store = new FingerprintStore();
        store.update(fingerprinter.scan(classes));

        // Same bytecode, different annotation: @Transactional(readOnly = true) is the real-world
        // case this stands in for.
        write("Sample.java", sample("return a + b;").replace("@Mode(value = Kind.A", "@Mode(value = Kind.B"));
        compile();

        ChangeSet changes = store.update(fingerprinter.scan(classes));
        assertFalse(changes.isEmpty(), "an annotation change must not be invisible");
    }

    private String sample(String body) {
        return """
                package t;
                @Mode(value = Kind.A, tags = {"x", "y"}, weight = 3)
                public class Sample {
                    @Mode(value = Kind.B, tags = {"m"})
                    public int add(int a, int b) {
                        %s
                    }
                    public int untouched() { return 7; }
                }
                """.formatted(body);
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(sources.resolve(name), content);
    }

    private void compile() throws IOException {
        try (var files = Files.list(sources)) {
            CompilationResult result = new IncrementalCompiler(List.of(classes), classes, List.of())
                    .compile(files.toList());
            assertTrue(result.successful(), "fixture must compile: " + result.errors());
        }
    }

    @Test
    void scanningAnEmptyDirectoryYieldsNothing() throws IOException {
        Map<String, ClassFingerprint> fingerprints = fingerprinter.scan(workspace.resolve("absent"));
        assertTrue(fingerprints.isEmpty());
    }
}
