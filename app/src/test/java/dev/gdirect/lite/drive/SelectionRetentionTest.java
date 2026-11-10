package dev.gdirect.lite.drive;
import static org.junit.Assert.*;
import org.junit.Test;
public final class SelectionRetentionTest {
    @Test public void recreationRetainsSelectionsAcrossPagesAndFailedReloads(){
        SelectionRetention<String> s=new SelectionRetention<>();s.bind("account|folder|query");s.items.put("page1","one");s.items.put("page2","two");
        s.recreate("account|folder|query");assertTrue(s.preserve("account|folder|query"));assertEquals(2,s.items.size());
        assertTrue(s.preserve("account|folder|query"));s.loaded("account|folder|query");assertFalse(s.preserve("account|folder|query"));
    }
    @Test public void accountFolderAndQueryChangesCannotInheritSelection(){
        for(String context:new String[]{"other|folder|query","account|other|query","account|folder|other"}){
            SelectionRetention<String> s=new SelectionRetention<>();s.bind("account|folder|query");s.items.put("id","entry");
            s.recreate(context);assertTrue(s.items.isEmpty());assertFalse(s.preserve(context));
        }
    }
    @Test public void logoutAndCompletedBatchClearPendingRecreation(){
        SelectionRetention<String> s=new SelectionRetention<>();s.bind("context");s.items.put("id","entry");s.recreate("context");s.clear();
        assertTrue(s.items.isEmpty());assertFalse(s.preserve("context"));
    }
}
