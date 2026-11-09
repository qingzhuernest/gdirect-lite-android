package dev.gdirect.lite.transfer;

import dev.gdirect.lite.drive.DriveApi;
import dev.gdirect.lite.drive.DriveSession;
import dev.gdirect.lite.network.ReadRetry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Runs the selected files in order, keeping completed downloads on failure or cancellation. */
public final class DownloadBatch {
    public static final int LIMIT = 50;

    public interface Download {
        long run(String id, int position, int total) throws IOException;
    }

    public record Result(int saved, int total, long bytes, IOException error) {
        public boolean complete() { return saved == total; }
    }

    public static Result run(String[] selection, ReadRetry.Check guard, Download download) throws IOException {
        if (selection.length == 0 || selection.length > LIMIT) throw new IOException("批量下载文件数量无效");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String id : selection) unique.add(DriveApi.requireId(id));
        List<String> ids = new ArrayList<>(unique);
        int saved = 0;
        long bytes = 0;
        IOException firstFailure = null;
        try {
            for (int i = 0; i < ids.size(); i++) {
                guard.run();
                try {
                    bytes += download.run(ids.get(i), i + 1, ids.size());
                    saved++;
                } catch (IOException error) {
                    if (error instanceof DriveSession.AuthorizationRequired) throw error;
                    guard.run();
                    if (firstFailure == null) firstFailure = error;
                }
            }
        } catch (IOException stopped) {
            return new Result(saved, ids.size(), bytes, stopped);
        }
        return new Result(saved, ids.size(), bytes, firstFailure);
    }

    private DownloadBatch() {}
}
