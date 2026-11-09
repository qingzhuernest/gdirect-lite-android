package dev.gdirect.lite.ui;

import dev.gdirect.lite.drive.DriveApi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Menu eligibility only; the selected mutation still requires the live account/write checks. */
public final class FileMenu {
    public enum Action {
        OPEN(1),DOWNLOAD(1),SELECT_DOWNLOAD(1),DETAILS(1),SHARE(2),COPY_LINK(2),
        RENAME(3),STAR(3),MOVE(3),RESTORE(3),TRASH(4),DELETE(4);
        public final int group;
        Action(int group){this.group=group;}
    }
    public static List<Action> actions(DriveApi.FileEntry entry,boolean sharedDriveRoot){
        List<Action> actions=new ArrayList<>();
        if(!entry.trashed&&(entry.folder||entry.shortcut||entry.previewable()))actions.add(Action.OPEN);
        if(!sharedDriveRoot&&entry.downloadable){
            actions.add(Action.DOWNLOAD);
            actions.add(Action.SELECT_DOWNLOAD);
        }
        actions.add(Action.DETAILS);
        if(sharedDriveRoot)return Collections.unmodifiableList(actions);
        if(entry.trashed){actions.add(Action.RESTORE);actions.add(Action.DELETE);}
        else {
            actions.add(Action.SHARE);actions.add(Action.COPY_LINK);actions.add(Action.RENAME);
            actions.add(Action.STAR);actions.add(Action.MOVE);actions.add(Action.TRASH);
        }
        return Collections.unmodifiableList(actions);
    }
    private FileMenu(){}
}
