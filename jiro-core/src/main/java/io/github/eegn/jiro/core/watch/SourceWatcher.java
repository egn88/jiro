package io.github.eegn.jiro.core.watch;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Watches source roots recursively and hands back batches of changes.
 *
 * <p>Editors do not write files the way a human thinks about saving them: a single save can arrive
 * as a create, a truncate and a rename, and a "reformat project" arrives as hundreds of events at
 * once. {@link #awaitChanges} therefore blocks for the first event and then keeps draining until
 * the filesystem has been quiet for a moment, so a cycle is triggered once per edit rather than
 * once per inotify event.
 */
public final class SourceWatcher implements AutoCloseable {

    private final WatchService watchService;
    private final Map<WatchKey, Path> watchedDirectories = new HashMap<>();

    public SourceWatcher(List<Path> roots) throws IOException {
        this.watchService = roots.isEmpty()
                ? java.nio.file.FileSystems.getDefault().newWatchService()
                : roots.get(0).getFileSystem().newWatchService();
        for (Path root : roots) {
            if (Files.isDirectory(root)) {
                registerRecursively(root);
            }
        }
    }

    /** Number of directories currently under watch; useful for a startup log line. */
    public int watchedDirectoryCount() {
        return watchedDirectories.size();
    }

    /**
     * Blocks until at least one change is seen, then collects everything that arrives until the
     * watcher has been idle for {@code quietPeriod}.
     *
     * @return the changes, de-duplicated by path with the last observed kind winning
     */
    public List<FileChange> awaitChanges(Duration quietPeriod) throws InterruptedException {
        Map<Path, FileChange> batch = new LinkedHashMap<>();
        WatchKey key = watchService.take();
        do {
            drain(key, batch);
            key = watchService.poll(quietPeriod.toMillis(), TimeUnit.MILLISECONDS);
        } while (key != null);
        return new ArrayList<>(batch.values());
    }

    private void drain(WatchKey key, Map<Path, FileChange> batch) {
        Path directory = watchedDirectories.get(key);
        if (directory == null) {
            key.reset();
            return;
        }
        for (WatchEvent<?> event : key.pollEvents()) {
            WatchEvent.Kind<?> kind = event.kind();
            if (kind == StandardWatchEventKinds.OVERFLOW) {
                // The kernel queue was exceeded and individual events were lost. There is no way
                // to know what changed, so signal the whole root and let the caller rescan.
                batch.put(directory, new FileChange(directory, FileChange.Kind.MODIFIED));
                continue;
            }
            Path changed = directory.resolve((Path) event.context());
            if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed)) {
                // A new package appeared: start watching it before its files are written.
                try {
                    registerRecursively(changed);
                } catch (IOException ignored) {
                    // Losing a new directory only costs a missed cycle, not correctness of the run.
                }
            }
            batch.put(changed, new FileChange(changed, toKind(kind)));
        }
        if (!key.reset()) {
            watchedDirectories.remove(key);
        }
    }

    private static FileChange.Kind toKind(WatchEvent.Kind<?> kind) {
        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
            return FileChange.Kind.CREATED;
        }
        if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
            return FileChange.Kind.DELETED;
        }
        return FileChange.Kind.MODIFIED;
    }

    private void registerRecursively(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                WatchKey key = directory.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                watchedDirectories.put(key, directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public void close() throws IOException {
        watchService.close();
    }
}
