package dev.dsh.cordis.reload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 监听单个文件变更(轮询 mtime,简单可靠;复杂递归 WatchService 留 M4)。 */
public final class FileWatcher {
    private final Path file;
    private volatile long lastModified;

    public FileWatcher(Path file) { this.file = file; this.lastModified = readMtime(); }

    public boolean changed() {
        long now = readMtime();
        if (now != lastModified) { lastModified = now; return true; }
        return false;
    }

    private long readMtime() {
        try { return Files.getLastModifiedTime(file).toMillis(); }
        catch (IOException e) { return lastModified; }
    }
}
