package io.github.eegn.jiro.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Assigns a dense integer id to every method the agent instruments, and keeps the mapping back
 * to a readable key so the runner can report coverage in terms the selector understands.
 *
 * <p>Ids are handed out in class-loading order, which differs between runs. The mapping is
 * therefore reported alongside every coverage payload rather than persisted.
 *
 * <p>Registration is idempotent per method key. dev mode reloads the application through a fresh
 * classloader on every cycle, so the same method is presented for instrumentation again and again;
 * returning the id already assigned keeps the registry bounded by the size of the codebase instead
 * of growing with the length of the session.
 *
 * <p>Lives on the bootstrap classpath for the same reason as {@link CoverageRecorder}.
 */
public final class MethodRegistry {

    /** Index is the method id; value is {@code owner#name descriptor}. */
    private static final List<String> KEYS = new ArrayList<>(8192);

    /** Reverse of {@link #KEYS}, so a re-instrumented method keeps the id it already had. */
    private static final Map<String, Integer> IDS = new HashMap<>(8192);

    private MethodRegistry() {
    }

    /**
     * Registers an instrumented method and returns the id that its probe will use.
     *
     * @param owner internal class name, e.g. {@code com/example/OrderService}
     * @param name  method name, or {@code <init>} / {@code <clinit>}
     * @param descriptor JVM method descriptor, e.g. {@code (Ljava/lang/String;)V}
     */
    public static synchronized int register(String owner, String name, String descriptor) {
        String key = key(owner, name, descriptor);
        Integer existing = IDS.get(key);
        if (existing != null) {
            return existing;
        }
        KEYS.add(key);
        int id = KEYS.size() - 1;
        IDS.put(key, id);
        return id;
    }

    /** The canonical textual form of a method, shared by the agent and the selector. */
    public static String key(String owner, String name, String descriptor) {
        return owner + '#' + name + descriptor;
    }

    public static synchronized int size() {
        return KEYS.size();
    }

    /** Immutable view of the id-to-key mapping, indexed by method id. */
    public static synchronized String[] snapshot() {
        return KEYS.toArray(new String[0]);
    }
}
