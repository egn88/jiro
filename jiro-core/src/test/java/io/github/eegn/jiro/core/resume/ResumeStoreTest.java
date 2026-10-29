package io.github.eegn.jiro.core.resume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eegn.jiro.core.analyze.ClassFingerprint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResumeStoreTest {

    @TempDir
    Path dir;

    static ResumeState sample() {
        return new ResumeState(
                Map.of("jdk", "25.0.1", "jvmArgs", "-Da=b\t-Dc=d\\e"),
                List.of(new ClasspathEntry("/repo/a.jar", 10, 20, "aa"),
                        new ClasspathEntry("/repo/b dir", -1, -1, "bb")),
                Map.of("0:application.yml", "rr"),
                Map.of("src/main/java/a/A.java", "s1"),
                "idx",
                Set.of("[engine:junit-jupiter]/[class:a.ATest]/[method:odd\nname()]"),
                41,
                Map.of("a/A", new ClassFingerprint("a/A", "abi",
                                Map.of("a/A#one()V", "h1", "a/A#two()V", "h2"), Set.of("x/Y")),
                        "a/B", new ClassFingerprint("a/B", "abi2", Map.of(), Set.of())));
    }

    @Test
    void roundTripsEverythingButReferences() throws Exception {
        ResumeStore store = new ResumeStore(dir.resolve("resume.state"));
        store.save(sample());

        ResumeState loaded = store.load();

        ResumeState expected = sample();
        assertEquals(expected.environment(), loaded.environment());
        assertEquals(expected.classpath(), loaded.classpath());
        assertEquals(expected.resources(), loaded.resources());
        assertEquals(expected.sources(), loaded.sources());
        assertEquals(expected.indexDigest(), loaded.indexDigest());
        assertEquals(expected.failingTests(), loaded.failingTests());
        assertEquals(41, loaded.lastCycleId());
        assertEquals(expected.fingerprints().keySet(), loaded.fingerprints().keySet());
        assertEquals(expected.fingerprints().get("a/A").methodHashes(),
                loaded.fingerprints().get("a/A").methodHashes());
        assertEquals("abi2", loaded.fingerprints().get("a/B").abiHash());
        assertTrue(loaded.fingerprints().get("a/A").references().isEmpty(),
                "the firewall runs on a fresh scan, so references are not persisted");
    }

    @Test
    void writesAtomicallyLeavingNoTemporaryFile() throws Exception {
        ResumeStore store = new ResumeStore(dir.resolve("resume.state"));
        store.save(sample());
        store.save(sample());

        try (var files = Files.list(dir)) {
            assertEquals(List.of(dir.resolve("resume.state")), files.toList());
        }
    }

    @Test
    void aMissingFileIsUnusable() {
        assertThrows(ResumeStore.Unusable.class, () -> new ResumeStore(dir.resolve("none")).load());
    }

    @Test
    void anotherFormatVersionIsUnusable() throws Exception {
        Path file = dir.resolve("resume.state");
        new ResumeStore(file).save(sample());
        String text = Files.readString(file);
        String body = text.substring(0, text.lastIndexOf("checksum\t"))
                .replaceFirst("jiro-resume-state\t1", "jiro-resume-state\t999");
        Files.writeString(file, body + "checksum\t" + Digests.of(body.getBytes(StandardCharsets.UTF_8)) + "\n");

        ResumeStore.Unusable unusable =
                assertThrows(ResumeStore.Unusable.class, () -> new ResumeStore(file).load());
        assertTrue(unusable.getMessage().contains("999"), unusable.getMessage());
    }

    @Test
    void aTruncatedFileIsUnusable() throws Exception {
        Path file = dir.resolve("resume.state");
        new ResumeStore(file).save(sample());
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length / 2));

        assertThrows(ResumeStore.Unusable.class, () -> new ResumeStore(file).load());
    }

    @Test
    void anEditedFileIsUnusable() throws Exception {
        Path file = dir.resolve("resume.state");
        new ResumeStore(file).save(sample());
        Files.writeString(file, Files.readString(file).replace("\th1\n", "\th9\n"));

        assertThrows(ResumeStore.Unusable.class, () -> new ResumeStore(file).load());
    }

    @Test
    void invalidateRemovesTheState() throws IOException {
        ResumeStore store = new ResumeStore(dir.resolve("resume.state"));
        store.save(sample());
        store.invalidate();

        assertFalse(Files.exists(store.file()));
    }
}
