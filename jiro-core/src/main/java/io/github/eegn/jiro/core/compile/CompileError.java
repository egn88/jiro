package io.github.eegn.jiro.core.compile;

/**
 * One compiler error, kept structured rather than pre-formatted.
 *
 * <p>The consumer is usually an agent deciding where to edit, and {@code "UserMapper.java:148
 * cannot find symbol"} makes it re-derive the absolute path and re-read the file to see what is
 * actually on that line. Carrying the path, the column and the offending source line means the fix
 * can be made from the report alone.
 *
 * @param file       absolute path to the source file
 * @param line       1-based line number, or 0 when the compiler did not attribute one
 * @param column     1-based column, or 0
 * @param message    the compiler's own message, unwrapped
 * @param sourceLine the text of {@code line}, or {@code null} if it could not be read
 */
public record CompileError(String file, long line, long column, String message, String sourceLine) {

    /** Human-readable single line, for the console. */
    public String describe() {
        String name = file == null ? "<unknown>" : file.substring(file.lastIndexOf('/') + 1);
        return name + ":" + line + (column > 0 ? ":" + column : "") + " " + message;
    }
}
