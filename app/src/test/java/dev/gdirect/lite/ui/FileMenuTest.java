package dev.gdirect.lite.ui;

import dev.gdirect.lite.drive.DriveApi;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static dev.gdirect.lite.ui.FileMenu.Action.*;
import static org.junit.Assert.*;

public final class FileMenuTest {
    private DriveApi.FileEntry entry(String mime,boolean download,boolean trashed)throws Exception{
        JSONObject json=new JSONObject().put("id","fixture_file").put("name","fixture").put("mimeType",mime)
            .put("size","1024").put("trashed",trashed).put("capabilities",new JSONObject().put("canDownload",download));
        if(mime.endsWith("shortcut"))json.put("shortcutDetails",new JSONObject().put("targetId","fixture_target"));
        return new DriveApi.FileEntry(json);
    }
    @Test public void ordinaryFileHasDirectActionsAndOnlyOneSharingEntry()throws Exception{
        List<FileMenu.Action> actions=FileMenu.actions(entry("application/pdf",true,false),false);
        assertEquals(Arrays.asList(OPEN,DOWNLOAD,SELECT_DOWNLOAD,DETAILS,SHARE,COPY_LINK,RENAME,STAR,MOVE,TRASH),actions);
        assertEquals(1,Collections.frequency(actions,SHARE));assertFalse(actions.contains(DELETE));
    }
    @Test public void restrictedDownloadCannotReappearAsPreview()throws Exception{
        List<FileMenu.Action> actions=FileMenu.actions(entry("application/pdf",false,false),false);
        assertFalse(actions.contains(DOWNLOAD));assertFalse(actions.contains(OPEN));assertTrue(actions.contains(DETAILS));
        assertFalse(actions.contains(SELECT_DOWNLOAD));
    }
    @Test public void folderCanOpenButCannotOfferAFileDownload()throws Exception{
        List<FileMenu.Action> actions=FileMenu.actions(entry("application/vnd.google-apps.folder",true,false),false);
        assertTrue(actions.contains(OPEN));assertTrue(actions.contains(MOVE));assertFalse(actions.contains(DOWNLOAD));
    }
    @Test public void shortcutOpensTheTargetWithoutOfferingTheShortcutAsFileContent()throws Exception{
        List<FileMenu.Action> actions=FileMenu.actions(entry("application/vnd.google-apps.shortcut",true,false),false);
        assertTrue(actions.contains(OPEN));assertFalse(actions.contains(DOWNLOAD));
    }
    @Test public void trashCannotBeOpenedSharedOrDownloadedAndKeepsDestructionSeparate()throws Exception{
        assertEquals(Arrays.asList(DETAILS,RESTORE,DELETE),FileMenu.actions(entry("application/vnd.google-apps.folder",true,true),false));
        assertTrue(DELETE.group>RESTORE.group);
    }
    @Test public void sharedDriveRootIsNotTreatedAsAnEditableFile()throws Exception{
        assertEquals(Arrays.asList(OPEN,DETAILS),FileMenu.actions(entry("application/vnd.google-apps.folder",true,false),true));
    }
}
