package io.github.eegn.jiro.core.resume;

/**
 * One element of the test classpath as it was when the coverage index was recorded.
 *
 * <p>Only {@code digest} takes part in the consistency check. Path, size and modification time are
 * a cache key, so an unchanged jar is not re-hashed on every start; an environment that restores
 * the local repository with fresh timestamps, or at another path, pays one re-hash and still
 * resumes, because equal content is equal content.
 *
 * @param path         absolute path of the jar or directory
 * @param size         length in bytes for a file, {@code -1} for a directory or a missing entry
 * @param lastModified modification time in milliseconds, {@code -1} when not a regular file
 * @param digest       SHA-256 of the content; for a directory, of its relative paths and contents
 */
public record ClasspathEntry(String path, long size, long lastModified, String digest) {
}
