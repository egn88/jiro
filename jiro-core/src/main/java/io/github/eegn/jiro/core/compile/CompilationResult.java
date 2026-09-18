package io.github.eegn.jiro.core.compile;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Outcome of one incremental compilation.
 *
 * @param successful   whether bytecode was produced for every requested source
 * @param diagnostics  formatted compiler messages, errors first
 * @param requested    the sources handed to javac
 */
public record CompilationResult(boolean successful, List<String> diagnostics, Set<Path> requested) {

    public boolean hasDiagnostics() {
        return !diagnostics.isEmpty();
    }
}
