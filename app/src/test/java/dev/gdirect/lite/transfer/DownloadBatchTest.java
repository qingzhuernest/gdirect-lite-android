package dev.gdirect.lite.transfer;

import dev.gdirect.lite.drive.DriveSession;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public final class DownloadBatchTest {
    @Test public void skipsFailedFilesAndKeepsCompletedDownloadsInSelectionOrder() throws IOException {
        List<String> attempts = new ArrayList<>();
        IOException failure = new IOException("not downloadable");
        DownloadBatch.Result result = DownloadBatch.run(new String[]{"first","failed","last","first"}, () -> {},
            (id,position,total) -> {
                attempts.add(id);
                assertEquals(attempts.size(),position);
                assertEquals(3,total);
                if (id.equals("failed")) throw failure;
                return 10;
            });
        assertEquals(List.of("first","failed","last"),attempts);
        assertEquals(2,result.saved());
        assertEquals(3,result.total());
        assertEquals(20,result.bytes());
        assertSame(failure,result.error());
        assertFalse(result.complete());
    }

    @Test public void cancellationDuringAFileStopsBeforeTheNextOne() throws IOException {
        IOException cancelled = new IOException("cancelled");
        List<String> attempts = new ArrayList<>();
        DownloadBatch.Result result = DownloadBatch.run(new String[]{"first","second","last"}, () -> {
            if (attempts.size()==2) throw cancelled;
        }, (id,position,total) -> {
            attempts.add(id);
            if (position==2) throw new IOException("closed connection");
            return 10;
        });
        assertEquals(List.of("first","second"),attempts);
        assertEquals(1,result.saved());
        assertSame(cancelled,result.error());
    }

    @Test public void expiredAuthorizationStopsTheBatch() throws IOException {
        DriveSession.AuthorizationRequired expired = new DriveSession.AuthorizationRequired();
        DownloadBatch.Result result = DownloadBatch.run(new String[]{"first","last"}, () -> {},
            (id,position,total) -> {
                assertEquals("first",id);
                throw expired;
            });
        assertEquals(0,result.saved());
        assertSame(expired,result.error());
    }

    @Test public void validatesEverySelectedIdBeforeAnyDownloadStarts() {
        assertThrows(IOException.class,() -> DownloadBatch.run(new String[]{"valid","invalid/id"}, () -> {},
            (id,position,total) -> { throw new AssertionError("Started before validating selection"); }));
    }
}
