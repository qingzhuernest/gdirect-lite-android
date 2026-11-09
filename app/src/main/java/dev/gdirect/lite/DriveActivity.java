package dev.gdirect.lite;

import dev.gdirect.lite.auth.AuthorizationCallback;
import dev.gdirect.lite.auth.GoogleAuthorization;
import dev.gdirect.lite.auth.RememberedAuthorization;
import dev.gdirect.lite.diagnostics.DiagnosticLog;
import dev.gdirect.lite.drive.DriveApi;
import dev.gdirect.lite.drive.DriveListing;
import dev.gdirect.lite.drive.BrowseReconnect;
import dev.gdirect.lite.drive.DriveSession;
import dev.gdirect.lite.drive.MutationCoordinator;
import dev.gdirect.lite.preview.PreviewPolicy;
import dev.gdirect.lite.transfer.DownloadBatch;
import dev.gdirect.lite.ui.ConnectionText;
import dev.gdirect.lite.ui.AppScreens;
import dev.gdirect.lite.ui.FileMenu;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.Intent;
import android.content.IntentSender;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.ClearTokenRequest;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.auth.api.identity.RevokeAccessRequest;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Explicit user consent, in-memory authorization, and a paginated read-only Drive browser. */
public final class DriveActivity extends AppCompatActivity {
    private static final int AUTHORIZE = 701;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final DriveListing browser = new DriveListing();
    private final BrowseReconnect reconnect = new BrowseReconnect();
    private final List<DriveApi.FileEntry> files = browser.files;
    private final Deque<String[]> parents = new ArrayDeque<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FileAdapter adapter = new FileAdapter();
    private final LinkedHashMap<String,DriveApi.FileEntry> downloadSelection = new LinkedHashMap<>();
    private long listingSession = -1;
    private LinearLayout selectionPanel;
    private TextView selectionCount;
    private Button downloadSelected;
    private TextView message, location, transfer, explanation,signInTitle;
    private LinearLayout queryRow,signInPanel,transferPanel;
    private View pathPanel;
    private android.widget.ScrollView signInScroll;
    private com.google.android.material.bottomnavigation.BottomNavigationView navigation;
    private com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton create;
    private final dev.gdirect.lite.ui.NavigationSelection navigationSelection=new dev.gdirect.lite.ui.NavigationSelection();
    private ListView fileList;
    private EditText search;
    private TextInputLayout searchField;
    private DriveApi.View currentView=DriveApi.View.FOLDER;
    private DriveApi.Sort currentSort=DriveApi.Sort.NAME;
    private String currentDrive="";
    private Button connect, more, up, retry, transferDetails, cancelTransfer;
    private ProgressBar loadingIndicator, transferProgress;
    private Future<?> listing;
    private String folder = "root", folderName, nextPage = "";
    private String activeSearch = "";
    private int requestVersion;
    private Integer lastActivityResult, lastSdkStatus;
    private String authorizationPhase = "not-requested";
    private boolean loading, authorizing, waitingResolution, wasConnected, transferActive, wasRevoking;
    private boolean editing,listingFailed,retryAppend,refreshAfterTransfer;
    private boolean managementRequested;
    private long authorizationTicket=-1;
    private RememberedAuthorization.State observedRestore=RememberedAuthorization.State.IDLE;
    private long observedMutation=MutationCoordinator.INSTANCE.revision();
    private int observedConnectionMode;
    private MaterialButton connectionIndicator;
    private String uploadParent;
    private long uploadSession=-1;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            GoogleNetwork connectivity=GoogleNetwork.get();
            String connectionText=ConnectionText.headline(connectivity.health(),DriveSession.INSTANCE.connected(),
                !DriveSession.INSTANCE.connected()&&GoogleAuthorization.memory(DriveActivity.this).state()==RememberedAuthorization.State.RESTORING,
                connectivity.connectionCheck().active());
            if(DriveSession.INSTANCE.connected()&&reconnect.retryPending()&&connectivity.health().online
                &&!connectivity.connectionCheck().active())connectionText="连接暂时中断 · 将自动重试";
            if(connectivity.vpnActive())connectionText+=" · VPN 已开启";
            if(!android.text.TextUtils.equals(connectionIndicator.getText(),connectionText))connectionIndicator.setText(connectionText);
            connectionIndicator.setContentDescription(connectionText+"，查看连接状态");
            if(wasConnected&&!DriveSession.INSTANCE.connected())clearBrowser();
            if(!authorizing&&!GoogleAuthorization.revoking.get()){
                RememberedAuthorization memory=GoogleAuthorization.memory(DriveActivity.this);
                if(!DriveSession.INSTANCE.connected())memory.restore();
                if(!DriveSession.INSTANCE.connected()&&memory.state()!=observedRestore){
                    observedRestore=memory.state();
                    if(observedRestore==RememberedAuthorization.State.RESTORING){authorizationPhase="restoring";message.setText(R.string.drive_restoring);}
                    else if(observedRestore==RememberedAuthorization.State.NEEDS_ACTION){authorizationPhase="restore-required";message.setText(R.string.drive_restore_required);}
                    else if(observedRestore==RememberedAuthorization.State.FAILED){authorizationPhase="restore-failed";message.setText(R.string.drive_restore_failed);}
                    updateControls();
                }
                if(DriveSession.INSTANCE.connected())observedRestore=memory.state();
            }
            if(editing!=MutationCoordinator.INSTANCE.busy())updateControls();
            if(observedMutation!=MutationCoordinator.INSTANCE.revision()&&!loading&&!editing&&DriveSession.INSTANCE.connected()) {
                observedMutation=MutationCoordinator.INSTANCE.revision();
                try {
                    if(DriveSession.INSTANCE.lease().id()==MutationCoordinator.INSTANCE.owner()) {
                        String error=MutationCoordinator.INSTANCE.error();
                        if(error==null)load(false);else{message.setText(error);listingFailed=true;retryAppend=false;updateControls();}
                    }
                }catch(DriveSession.AuthorizationRequired expired){clearBrowser();}
            }
            if (wasConnected && !DriveSession.INSTANCE.connected()) clearBrowser();
            if (wasRevoking && !GoogleAuthorization.revoking.get()) {
                message.setText(GoogleAuthorization.lastRevokeSucceeded ? R.string.drive_revoked : R.string.drive_revoke_failed);
                updateControls();
            }
            DownloadService.Snapshot s = DownloadService.snapshot;
            boolean hasTransfer=s!=DownloadService.IDLE;
            int percent=s.total>0?(int)Math.max(0,Math.min(100,s.done/(double)s.total*100)):0;
            String amount=s.active&&s.total>0?getString(R.string.drive_transfer_progress,percent,
                android.text.format.Formatter.formatShortFileSize(DriveActivity.this,s.done),
                android.text.format.Formatter.formatShortFileSize(DriveActivity.this,s.total)):"";
            String transferText=hasTransfer?s.text+(amount.isEmpty()?"":"\n"+amount):"";
            // Progress remains readable on focus without repeated spoken interruptions.
            transfer.setAccessibilityLiveRegion(s.active?View.ACCESSIBILITY_LIVE_REGION_NONE:View.ACCESSIBILITY_LIVE_REGION_POLITE);
            if(!android.text.TextUtils.equals(transfer.getText(),transferText))transfer.setText(transferText);
            transferPanel.setVisibility(hasTransfer?View.VISIBLE:View.GONE);
            transfer.setVisibility(hasTransfer?View.VISIBLE:View.GONE);
            transferProgress.setVisibility(s.active?View.VISIBLE:View.GONE);
            transferProgress.setIndeterminate(s.active&&s.total<=0);
            transferProgress.setContentDescription(amount.isEmpty()?s.text:amount);
            if(s.total>0)transferProgress.setProgress((int)Math.max(0,Math.min(1000,s.done/(double)s.total*1000)));
            transferDetails.setVisibility(hasTransfer?View.VISIBLE:View.GONE);
            cancelTransfer.setVisibility(s.active?View.VISIBLE:View.GONE);
            if(transferActive&&!s.active)refreshAfterTransfer=true;
            if(refreshAfterTransfer&&!loading&&!editing&&DriveSession.INSTANCE.connected()) {refreshAfterTransfer=false;load(false);}
            if (transferActive != s.active) { transferActive = s.active; updateSelectionPanel(); adapter.notifyDataSetChanged(); }
            reconnectBrowser();
            handler.postDelayed(this, 500);
        }
    };

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size);
        return view;
    }
    private Button button(int text) {
        Button view = text == R.string.drive_connect ? new MaterialButton(this)
            : new MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle);
        view.setText(text); view.setAllCaps(false); view.setMinHeight(dp(48));
        return view;
    }
    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view;
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        observedConnectionMode=GoogleNetwork.get().connectionMode();
        folderName = getString(R.string.drive_root);
        if (saved != null) {
            managementRequested=saved.getBoolean("management_requested",false);
            authorizationTicket=saved.getLong("authorization_ticket",-1);
            uploadParent=saved.getString("upload_parent");uploadSession=saved.getLong("upload_session",-1);
            authorizationPhase = saved.getString("authorization_phase","not-requested");
            if (saved.containsKey("last_activity_result")) lastActivityResult = saved.getInt("last_activity_result");
            if (saved.containsKey("last_sdk_status")) lastSdkStatus = saved.getInt("last_sdk_status");
            try {
                if(DriveSession.INSTANCE.connected()&&saved.getLong("browser_session",-1)==DriveSession.INSTANCE.lease().id()) {
                    observedMutation=saved.getLong("mutation_revision",observedMutation);
                    org.json.JSONObject state=new org.json.JSONObject(saved.getString("browser_state","{}"));
                    folder=DriveApi.requireId(state.optString("folder","root"));folderName=state.optString("name",folderName);
                    currentView=DriveApi.View.valueOf(state.optString("view","FOLDER"));currentSort=DriveApi.Sort.valueOf(state.optString("sort","NAME"));
                    currentDrive=state.optString("drive","");if(!currentDrive.isEmpty())DriveApi.requireId(currentDrive);activeSearch=state.optString("query","");
                    org.json.JSONArray history=state.optJSONArray("parents");
                    if(history!=null)for(int i=0;i<Math.min(history.length(),100);i++) {
                        org.json.JSONArray record=history.getJSONArray(i);String id=DriveApi.requireId(record.getString(0)),drive=record.getString(3);
                        DriveApi.View.valueOf(record.getString(2));if(!drive.isEmpty())DriveApi.requireId(drive);
                        parents.addLast(new String[]{id,record.getString(1),record.getString(2),drive});
                    }
                }
            }catch(IOException|org.json.JSONException|IllegalArgumentException error){folder="root";folderName=getString(R.string.drive_root);currentView=DriveApi.View.FOLDER;currentSort=DriveApi.Sort.NAME;currentDrive="";activeSearch="";parents.clear();}
        }
        waitingResolution = saved != null && saved.getBoolean("waiting_resolution", false);
        authorizing = waitingResolution;
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        LinearLayout container=column();
        container.setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(this,com.google.android.material.R.attr.colorSurface,android.graphics.Color.WHITE));
        int navigationColor=com.google.android.material.color.MaterialColors.getColor(this,com.google.android.material.R.attr.colorSurfaceContainer,android.graphics.Color.WHITE);
        View navigationInset=new View(this);
        navigationInset.setBackgroundColor(navigationColor);
        navigationInset.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        navigationInset.setLayoutParams(new LinearLayout.LayoutParams(-1,0));
        getWindow().setNavigationBarColor(navigationColor);
        getWindow().setNavigationBarDividerColor(navigationColor);
        getWindow().setNavigationBarContrastEnforced(false);
        container.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=android.os.Build.VERSION.SDK_INT>=30
                ?insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout())
                :insets.getSystemWindowInsets();
            int keyboard=android.os.Build.VERSION.SDK_INT>=30?insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                :(bars.bottom>insets.getStableInsetBottom()?bars.bottom:0);
            boolean typing=keyboard>bars.bottom||(android.os.Build.VERSION.SDK_INT<30&&keyboard>0);
            int bottom=typing?0:bars.bottom;
            android.view.ViewGroup.LayoutParams footer=navigationInset.getLayoutParams();
            if(footer.height!=bottom){footer.height=bottom;navigationInset.setLayoutParams(footer);}
            view.setPadding(bars.left,bars.top,bars.right,typing?keyboard:0);return insets.consumeSystemWindowInsets();
        });
        setContentView(container);container.requestApplyInsets();
        com.google.android.material.appbar.MaterialToolbar toolbar=new com.google.android.material.appbar.MaterialToolbar(this);
        toolbar.setTitle(R.string.app_name);container.addView(toolbar,new LinearLayout.LayoutParams(-1,-2));setSupportActionBar(toolbar);
        connectionIndicator=(MaterialButton)getLayoutInflater().inflate(R.layout.view_connection_indicator,container,false);
        connectionIndicator.setOnClickListener(v->startActivity(AppScreens.intent(this,ConnectionActivity.class)));container.addView(connectionIndicator);
        LinearLayout root=column();root.setPadding(0,0,dp(8),0);root.setFocusableInTouchMode(true);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (!downloadSelection.isEmpty()) clearDownloadSelection();
                else if (!editing&&(!activeSearch.isEmpty()||search.getText().length()>0)) clearSearch();
                else if (!editing&&!parents.isEmpty()) goUp();
                else {
                    setEnabled(false);
                    try { getOnBackPressedDispatcher().onBackPressed(); }
                    finally { setEnabled(true); }
                }
            }
        });
        signInPanel=column();signInPanel.setPadding(dp(24),dp(24),dp(24),dp(24));
        signInTitle=label("连接你的 Google Drive",24);signInTitle.setAccessibilityHeading(true);signInPanel.addView(signInTitle);
        explanation = label("浏览、预览和下载云盘文件。默认只读，按需开启文件管理。", 16);
        explanation.setPadding(0,dp(16),0,dp(12));
        signInPanel.addView(explanation);
        connect = button(R.string.drive_connect);
        connect.setOnClickListener(v -> authorize());
        signInPanel.addView(connect, new LinearLayout.LayoutParams(-2,-2));

        queryRow = new LinearLayout(this);
        queryRow.setGravity(Gravity.CENTER_VERTICAL);
        searchField = new TextInputLayout(this);
        searchField.setHint(getString(R.string.drive_search_hint));
        search = new TextInputEditText(searchField.getContext());
        search.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});
        search.setText(activeSearch);searchField.setHint(getString(currentView==DriveApi.View.FOLDER?R.string.drive_search_hint:R.string.drive_search_all));
        search.setSingleLine(true); search.setTextSize(16); search.setMinHeight(dp(48));
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            startSearch(); return true;
        });
        searchField.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_FILLED);
        searchField.setBoxBackgroundColor(com.google.android.material.color.MaterialColors.getColor(this,com.google.android.material.R.attr.colorSurfaceContainerHigh,android.graphics.Color.LTGRAY));
        searchField.setBoxCornerRadii(dp(28),dp(28),dp(28),dp(28));
        searchField.setBoxStrokeWidth(0);searchField.setBoxStrokeWidthFocused(0);
        searchField.setStartIconDrawable(R.drawable.ic_search);
        searchField.setStartIconContentDescription(getString(R.string.drive_search));
        searchField.setStartIconOnClickListener(v->startSearch());
        searchField.setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT);
        searchField.addView(search, new LinearLayout.LayoutParams(-1,-2));
        searchField.setEndIconOnClickListener(v -> clearSearch());
        queryRow.addView(searchField, new LinearLayout.LayoutParams(0,-2,1));
        queryRow.setPadding(dp(16),0,dp(16),dp(8));container.addView(queryRow);
        location = label(folderName,16);
        location.setPadding(0,dp(8),0,dp(8));
        location.setAccessibilityHeading(true);
        location.setCompoundDrawablesRelativeWithIntrinsicBounds(0,0,R.drawable.ic_expand,0);
        location.setCompoundDrawablePadding(dp(8));
        location.setCompoundDrawableTintList(ColorStateList.valueOf(com.google.android.material.color.MaterialColors.getColor(this,com.google.android.material.R.attr.colorOnSurfaceVariant,android.graphics.Color.GRAY)));
        location.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        location.setMinHeight(dp(48));location.setGravity(Gravity.CENTER_VERTICAL);
        location.setTooltipText(getString(R.string.drive_choose_library));
        location.setOnClickListener(v->chooseLibrary());
        LinearLayout pathRow=new LinearLayout(this);pathRow.setGravity(Gravity.CENTER_VERTICAL);
        location.setMaxLines(2);location.setEllipsize(TextUtils.TruncateAt.END);
        pathRow.addView(location,new LinearLayout.LayoutParams(0,-2,1));
        up=(Button)getLayoutInflater().inflate(R.layout.view_folder_up,pathRow,false);
        up.setOnClickListener(v -> goUp());
        pathRow.addView(up,new LinearLayout.LayoutParams(dp(48),-2));root.addView(pathRow);pathPanel=pathRow;
        root.setPadding(dp(20),0,dp(20),dp(8));
        loadingIndicator = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);loadingIndicator.setIndeterminate(true);
        container.addView(loadingIndicator, new LinearLayout.LayoutParams(-1,dp(4)));
        message = label("",14);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        message.setPadding(dp(20),dp(8),dp(20),dp(8));
        container.addView(message);
        message.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){message.setVisibility(s.length()==0?View.GONE:View.VISIBLE);}public void afterTextChanged(android.text.Editable e){}});
        retry=button(R.string.drive_retry_list);
        retry.setOnClickListener(v->load(retryAppend));
        container.addView(retry,new LinearLayout.LayoutParams(-1,-2));
        fileList = new ListView(this);
        fileList.addHeaderView(root, null, false);
        LinearLayout footer = column();
        more = button(R.string.drive_more);
        more.setOnClickListener(v -> load(true));
        footer.addView(more,new LinearLayout.LayoutParams(-1,-2));
        transfer = label("",14);transfer.setMaxLines(2);transfer.setEllipsize(TextUtils.TruncateAt.END);
        transfer.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        transferPanel=column();transferPanel.setPadding(dp(20),dp(8),dp(20),dp(8));
        transferPanel.setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(this,com.google.android.material.R.attr.colorSurfaceContainer,android.graphics.Color.WHITE));
        transferPanel.addView(transfer);
        transferProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);transferProgress.setMax(1000);
        transferPanel.addView(transferProgress,new LinearLayout.LayoutParams(-1,dp(4)));
        LinearLayout transferActions=new LinearLayout(this);
        transferDetails=button(R.string.drive_transfer_open);
        transferDetails.setOnClickListener(v->startActivity(AppScreens.intent(this,TransferActivity.class)));
        transferActions.addView(transferDetails,new LinearLayout.LayoutParams(0,-2,1));
        cancelTransfer=button(R.string.drive_cancel_transfer);
        cancelTransfer.setOnClickListener(v->{
            try{startService(new Intent(this,DownloadService.class).setAction(DownloadService.ACTION_STOP));}
            catch(RuntimeException failed){message.setText(R.string.drive_cancel_failed);}
        });
        LinearLayout.LayoutParams cancelParams=new LinearLayout.LayoutParams(0,-2,1);cancelParams.setMarginStart(dp(8));
        transferActions.addView(cancelTransfer,cancelParams);transferPanel.addView(transferActions);
        transfer.setVisibility(View.GONE);transferProgress.setVisibility(View.GONE);transferDetails.setVisibility(View.GONE);cancelTransfer.setVisibility(View.GONE);
        fileList.addFooterView(footer, null, false);
        fileList.setAdapter(adapter);
        fileList.setDivider(null);fileList.setClipToPadding(false);fileList.setPadding(dp(4),0,dp(4),dp(96));
        android.widget.FrameLayout content=new android.widget.FrameLayout(this);
        content.addView(fileList,new android.widget.FrameLayout.LayoutParams(-1,-1));
        signInScroll=new android.widget.ScrollView(this);signInScroll.setFillViewport(true);signInScroll.addView(signInPanel);
        content.addView(signInScroll,new android.widget.FrameLayout.LayoutParams(-1,-1));
        create=new com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton(this);
        create.setText(R.string.drive_create);create.setIconResource(R.drawable.ic_add);
        create.setContentDescription(getString(R.string.drive_create));
        create.setOnClickListener(v->new MaterialAlertDialogBuilder(this).setTitle(R.string.drive_create)
            .setItems(new String[]{getString(R.string.drive_upload),getString(R.string.drive_new_folder)},(dialog,n)->{if(n==0)chooseUpload();else editName(null);}).show());
        android.widget.FrameLayout.LayoutParams floating=new android.widget.FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END);
        floating.setMargins(dp(16),dp(16),dp(16),dp(16));content.addView(create,floating);
        container.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        selectionPanel=column();selectionPanel.setPadding(dp(20),dp(4),dp(20),dp(4));
        selectionPanel.setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(this,
            com.google.android.material.R.attr.colorSurfaceContainer,android.graphics.Color.WHITE));
        selectionCount=label("",14);
        selectionCount.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        selectionPanel.addView(selectionCount);
        LinearLayout selectionActions=new LinearLayout(this);
        Button cancelSelection=button(R.string.drive_cancel_selection);
        cancelSelection.setOnClickListener(v->clearDownloadSelection());
        selectionActions.addView(cancelSelection,new LinearLayout.LayoutParams(0,-2,1));
        downloadSelected=button(R.string.drive_download_selected);
        downloadSelected.setOnClickListener(v->downloadSelection());
        selectionActions.addView(downloadSelected,new LinearLayout.LayoutParams(0,-2,1));
        selectionPanel.addView(selectionActions);
        container.addView(selectionPanel,new LinearLayout.LayoutParams(-1,-2));
        container.addView(transferPanel,new LinearLayout.LayoutParams(-1,-2));
        navigation=new com.google.android.material.bottomnavigation.BottomNavigationView(this);
        navigation.setLabelVisibilityMode(com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED);
        navigation.getMenu().add(0,101,0,R.string.drive_nav_home).setIcon(R.drawable.ic_home);
        navigation.getMenu().add(0,104,3,R.string.drive_nav_files).setIcon(R.drawable.ic_folder);
        navigation.setOnItemSelectedListener(item->{
            if(navigationSelection.isSyncing())return true;
            navigate(item.getItemId()==101?DriveApi.View.RECENT:DriveApi.View.FOLDER);return true;
        });
        navigation.setOnItemReselectedListener(item->{if(!navigationSelection.isSyncing()&&!loading&&!editing&&DriveSession.INSTANCE.connected())load(false);});
        container.addView(navigation,new LinearLayout.LayoutParams(-1,-2));
        container.addView(navigationInset);
        container.requestApplyInsets();
        updateControls();
        if (DriveSession.INSTANCE.connected()) {if(editing)message.setText(R.string.drive_editing);else load(false);}
        else message.setText(GoogleAuthorization.revoking.get()?getString(R.string.drive_revoking):"");
        handleSettingsAction(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);setIntent(intent);handleSettingsAction(intent);
    }
    private void handleSettingsAction(Intent intent) {
        String action=intent.getStringExtra(SettingsActivity.EXTRA_ACCOUNT_ACTION);
        intent.removeExtra(SettingsActivity.EXTRA_ACCOUNT_ACTION);
        if(action==null)return;
        handler.post(()->{
            if(isFinishing()||isDestroyed())return;
            switch(action) {
                case "connect":authorize();break;
                case "manage":if(DriveSession.INSTANCE.connected())requestManagement();break;
                case "disconnect":if(!authorizing)disconnect();break;
                case "revoke":if(!authorizing)new MaterialAlertDialogBuilder(this).setTitle(R.string.drive_revoke)
                    .setMessage(R.string.drive_revoke_explanation).setNegativeButton(android.R.string.cancel,null)
                    .setPositiveButton(R.string.drive_revoke,(d,w)->revoke()).show();break;
            }
        });
    }

    private void authorize() {
        RememberedAuthorization.Profile profile=GoogleAuthorization.memory(this).profile();
        authorize(profile!=null&&profile.manage);
    }
    private void authorize(boolean manage) {
        if (authorizing || GoogleAuthorization.revoking.get()) return;
        authorizationTicket=GoogleAuthorization.memory(this).beginInteractive();
        DiagnosticLog.get().event(DiagnosticLog.Event.AUTH_BEGIN);
        authorizationPhase = "request";
        managementRequested=manage;
        lastActivityResult = null; lastSdkStatus = null;
        authorizing = true;
        updateControls();
        message.setText(R.string.drive_authorizing);
        AuthorizationRequest.Builder builder = AuthorizationRequest.builder()
            .setRequestedScopes(Collections.singletonList(new Scope(manage?DriveApi.MANAGE_SCOPE:DriveApi.READONLY_SCOPE)));
        RememberedAuthorization.Profile profile=GoogleAuthorization.memory(this).profile();
        if(profile!=null)builder.setAccount(new android.accounts.Account(profile.account,"com.google"));
        AuthorizationRequest request=builder.build();
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener(result -> {
                if (isDestroyed() || isFinishing()) return;
                if (result.hasResolution()) {
                    try {
                        if (result.getPendingIntent() == null) throw new IOException();
                        waitingResolution = true;
                        authorizationPhase = "google-consent";
                        startIntentSenderForResult(result.getPendingIntent().getIntentSender(), AUTHORIZE, null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException | IOException error) {
                        authFailed(getString(R.string.drive_auth_failed));
                    }
                } else acceptAuthorization(result);
            })
            .addOnFailureListener(error -> {
                if (isDestroyed() || isFinishing()) return;
                int code = error instanceof ApiException ? ((ApiException)error).getStatusCode() : -1;
                authStatusFailed(code);
            });
    }

    @Override protected void onActivityResult(int request, int resultCode, Intent data) {
        super.onActivityResult(request,resultCode,data);
        if(request==703) {
            if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;
            if(uploadParent==null||DownloadService.snapshot.active){message.setText(R.string.drive_download_busy);return;}
            try {
                DriveSession.Lease lease=DriveSession.INSTANCE.lease();lease.requireWrite();
                if(lease.id()!=uploadSession)throw new DriveSession.AuthorizationRequired();
                Intent transfer=new Intent(this,DownloadService.class).setAction(DownloadService.ACTION_START)
                    .putExtra(DownloadService.EXTRA_UPLOAD_URI,data.getData().toString()).putExtra(DownloadService.EXTRA_UPLOAD_PARENT,uploadParent)
                    .putExtra(DownloadService.EXTRA_DRIVE_SESSION,uploadSession);
                startForegroundService(transfer);message.setText(R.string.drive_upload_started);
            } catch(IOException|RuntimeException error){message.setText(R.string.drive_upload_failed);}
            finally{uploadParent=null;uploadSession=-1;}
            return;
        }
        if (request != AUTHORIZE) return;
        authorizationPhase = "callback";
        lastActivityResult = resultCode;
        DiagnosticLog.get().record(DiagnosticLog.Event.AUTH_CALLBACK,0,null,null,null,0,resultCode,null);
        waitingResolution = false;
        AuthorizationCallback.Result<AuthorizationResult> decoded = AuthorizationCallback.decode(resultCode,data,payload -> {
            try { return Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(payload); }
            catch (ApiException error) { throw new AuthorizationCallback.StatusFailure(error.getStatusCode()); }
        });
        if (decoded.status != null) authStatusFailed(decoded.status);
        else if (decoded.value != null) acceptAuthorization(decoded.value);
        else authFailed(getString(R.string.drive_auth_no_result,decoded.activityResult));
    }

    private void acceptAuthorization(AuthorizationResult result) {
        try {
            boolean manage=result.getGrantedScopes().contains(DriveApi.MANAGE_SCOPE);
            if (!manage && !result.getGrantedScopes().contains(DriveApi.READONLY_SCOPE)) {
                authFailed(getString(R.string.drive_scope_missing)); return;
            }
            if(!GoogleAuthorization.memory(this).acceptInteractive(authorizationTicket,result.getAccessToken(),
                new java.util.HashSet<>(result.getGrantedScopes()),managementRequested,GoogleAuthorization.account(result))){
                authFailed(getString(R.string.drive_auth_superseded));return;
            }
            DiagnosticLog.get().event(DiagnosticLog.Event.AUTH_OK);
            authorizationPhase = "authorized";
            authorizing = false; waitingResolution = false;
            clearBrowser();
            load(false);
        } catch (IllegalArgumentException error) { authFailed(getString(R.string.drive_auth_failed)); }
    }

    private void authFailed(String reason) {
        DiagnosticLog.get().record(DiagnosticLog.Event.AUTH_FAILED,0,null,null,null,0,lastSdkStatus==null?0:lastSdkStatus,null);
        authorizing = false; waitingResolution = false;
        GoogleAuthorization.memory(this).interactiveFailed(authorizationTicket);
        observedRestore=GoogleAuthorization.memory(this).state();
        message.setText(reason); updateControls();
    }

    private void authStatusFailed(int code) {
        lastSdkStatus = code;
        int reason;
        switch (code) {
            case 10: reason = R.string.drive_auth_config; break;
            case 7: case 15: reason = R.string.drive_auth_network; break;
            case 16: case 12501: reason = R.string.drive_auth_interrupted; break;
            default: reason = R.string.drive_auth_failed;
        }
        // Only a numeric SDK status is exposed. Never render provider messages or tokens.
        authFailed(getString(reason) + "\n" + getString(R.string.drive_auth_status,code));
    }

    private void disconnect() {
        DiagnosticLog.get().event(DiagnosticLog.Event.ACCOUNT_DISCONNECTED);
        try {
            String token = DriveSession.INSTANCE.lease().token();
            GoogleAuthorization.memory(this).forget();
            Identity.getAuthorizationClient(this).clearToken(ClearTokenRequest.builder().setToken(token).build());
        } catch (DriveSession.AuthorizationRequired ignored) { GoogleAuthorization.memory(this).forget(); }
        clearBrowser(); message.setText(R.string.drive_disconnected);
    }

    private void clearBrowser() {
        clearDownloadSelection();
        listingSession = -1;
        reconnect.reset();
        requestVersion++;
        if (listing != null) listing.cancel(true);
        loading = false;
        browser.reset(); listingFailed=false;refreshAfterTransfer=false; nextPage = ""; parents.clear(); folder = "root"; activeSearch = "";
        currentDrive="";
        currentView=DriveApi.View.FOLDER; currentSort=DriveApi.Sort.NAME;
        searchField.setHint(getString(R.string.drive_search_hint));
        folderName = getString(R.string.drive_root);
        search.setText("");
        location.setText(folderName); adapter.notifyDataSetChanged();
        message.setText(R.string.drive_not_connected);
        updateControls();
    }

    private void updateControls() {
        editing=MutationCoordinator.INSTANCE.busy();
        wasConnected = DriveSession.INSTANCE.connected();
        wasRevoking = GoogleAuthorization.revoking.get();
        signInScroll.setVisibility(wasConnected?View.GONE:View.VISIBLE);
        fileList.setVisibility(wasConnected?View.VISIBLE:View.GONE);
        pathPanel.setVisibility(wasConnected?View.VISIBLE:View.GONE);
        navigation.setVisibility(wasConnected?View.VISIBLE:View.GONE);
        connect.setVisibility(wasConnected ? View.GONE : View.VISIBLE);
        connect.setEnabled(!authorizing && !wasRevoking);
        explanation.setVisibility(wasConnected ? View.GONE : View.VISIBLE);
        boolean restoring=GoogleAuthorization.memory(this).state()==RememberedAuthorization.State.RESTORING;
        signInTitle.setText(restoring?"正在恢复账户连接":authorizing?"正在连接 Google 账户":"连接你的 Google Drive");
        explanation.setText(restoring?"已记住你的账户，正在恢复访问权限。":authorizing?"请在 Google 页面完成确认。":"浏览、预览和下载云盘文件。默认只读，按需开启文件管理。");
        message.setVisibility(!wasConnected&&(restoring||authorizing)||message.getText().length()==0?View.GONE:View.VISIBLE);
        queryRow.setVisibility(wasConnected ? View.VISIBLE : View.GONE);
        if(navigation!=null) {
            navigationSelection.sync(navigation.getSelectedItemId(),currentView==DriveApi.View.RECENT?101:104,navigation::setSelectedItemId);
            for(int i=0;i<navigation.getMenu().size();i++)navigation.getMenu().getItem(i).setEnabled(wasConnected&&!editing&&!authorizing);
        }
        if(create!=null){create.setVisibility(wasConnected&&currentView==DriveApi.View.FOLDER?View.VISIBLE:View.GONE);create.setEnabled(!loading&&!editing&&!DownloadService.snapshot.active);}

        location.setVisibility(wasConnected ? View.VISIBLE : View.GONE);
        boolean library=currentView!=DriveApi.View.RECENT;
        location.setClickable(library&&!editing);
        location.setCompoundDrawablesRelativeWithIntrinsicBounds(0,0,library?R.drawable.ic_expand:0,0);
        location.setTooltipText(library?getString(R.string.drive_choose_library):null);
        search.setEnabled(wasConnected && !loading&&!editing);
        up.setVisibility(parents.isEmpty() ? View.GONE : View.VISIBLE);
        up.setEnabled(!loading&&!editing);
        more.setVisibility(nextPage.isEmpty() ? View.GONE : View.VISIBLE);
        more.setEnabled(!loading&&!editing);
        retry.setVisibility(listingFailed&&wasConnected?View.VISIBLE:View.GONE);
        retry.setEnabled(!loading&&!editing);
        retry.setText(retryAppend?R.string.drive_retry_more:R.string.drive_retry_list);
        loadingIndicator.setVisibility(loading || editing || authorizing || wasRevoking
            ||GoogleAuthorization.memory(this).state()==RememberedAuthorization.State.RESTORING ? View.VISIBLE : View.GONE);
        updateSelectionPanel();
        invalidateOptionsMenu();
    }

    private void navigate(DriveApi.View selected) {
        if(editing||!DriveSession.INSTANCE.connected())return;
        currentView=selected;currentDrive="";parents.clear();folder="root";activeSearch="";search.setText("");
        folderName=getResources().getStringArray(R.array.drive_views)[selected.ordinal()];location.setText(folderName);
        searchField.setHint(getString(selected==DriveApi.View.FOLDER?R.string.drive_search_hint:R.string.drive_search_all));
        if(selected==DriveApi.View.RECENT)currentSort=DriveApi.Sort.MODIFIED;
        load(false);
    }

    private void chooseLibrary(){
        if(editing||!DriveSession.INSTANCE.connected()||currentView==DriveApi.View.RECENT)return;
        DriveApi.View[] views={DriveApi.View.FOLDER,DriveApi.View.ALL,DriveApi.View.STARRED,DriveApi.View.SHARED,DriveApi.View.TRASH,DriveApi.View.DRIVES};
        String[] labels=new String[views.length],all=getResources().getStringArray(R.array.drive_views);int selected=-1;
        for(int i=0;i<views.length;i++){labels[i]=all[views[i].ordinal()];if(currentView==views[i])selected=i;}
        new MaterialAlertDialogBuilder(this).setTitle(R.string.drive_view).setSingleChoiceItems(labels,selected,(dialog,n)->{
            dialog.dismiss();navigate(views[n]);
        }).setNegativeButton(android.R.string.cancel,null).show();
    }

    private void clearSearch() {
        if(editing)return;
        boolean applied=!activeSearch.isEmpty();
        search.setText("");activeSearch="";
        if(applied&&DriveSession.INSTANCE.connected())load(false);
    }

    private void startSearch() {
        if (loading || editing || !DriveSession.INSTANCE.connected()) return;
        ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(search.getWindowToken(),0);
        search.clearFocus();
        activeSearch = search.getText().toString(); load(false);
    }

    private void load(boolean append) {
        load(append,false);
    }

    private void reconnectBrowser(){
        if(authorizing||GoogleAuthorization.revoking.get()||!DriveSession.INSTANCE.connected())return;
        GoogleNetwork net=GoogleNetwork.get();
        dev.gdirect.lite.network.ConnectionHealth.Snapshot health=net.health();
        dev.gdirect.lite.network.ConnectionCheck.Snapshot check=net.connectionCheck();
        try {
            BrowseReconnect.Action action=reconnect.next(DriveSession.INSTANCE.lease().id(),net.healthGeneration(),
                health.online,loading||editing,check.active(),
                health.api.state==dev.gdirect.lite.network.ConnectionHealth.State.READY?check.finishedAt:0,
                android.os.SystemClock.elapsedRealtime());
            if(action==BrowseReconnect.Action.NONE)return;
            DiagnosticLog.get().record(DiagnosticLog.Event.BROWSE_AUTOCONNECT,0,"www.googleapis.com",null,null,0,action.ordinal(),null);
            if(action==BrowseReconnect.Action.SESSION){authorizationPhase="restored";clearBrowser();}
            boolean append=(action==BrowseReconnect.Action.RETRY||action==BrowseReconnect.Action.CHECK)&&retryAppend;
            load(append,true);
        }catch(DriveSession.AuthorizationRequired expired){clearBrowser();}
    }

    private void load(boolean append,boolean automatic) {
        if(editing)return;
        DriveSession.Lease lease;
        try { lease = DriveSession.INSTANCE.lease(); }
        catch (DriveSession.AuthorizationRequired error) { clearBrowser(); return; }
        if (!append || listingSession != lease.id()) clearDownloadSelection();
        GoogleNetwork net=GoogleNetwork.get();
        net.health(); // Observe the live path before binding this listing attempt to its generation.
        reconnect.started(lease.id(),net.healthGeneration(),net.connectionCheck().finishedAt,automatic);
        int version = ++requestVersion;
        if (listing != null) listing.cancel(true);
        String queryFolder = folder, query = activeSearch;
        DriveApi.View queryView=currentView; DriveApi.Sort querySort=currentSort;
        String queryDrive=currentDrive;
        String context=lease.id()+"|"+folder+"|"+currentView+"|"+currentSort+"|"+currentDrive+"|"+activeSearch;
        String page=browser.begin(context,append);
        nextPage=browser.next();listingFailed=false;retryAppend=append;adapter.notifyDataSetChanged();
        loading = true; updateControls(); message.setText(R.string.drive_loading);
        long logOperation=DiagnosticLog.get().operation(),logStart=System.nanoTime();
        DiagnosticLog.get().record(DiagnosticLog.Event.LIST_BEGIN,logOperation,"www.googleapis.com",null,null,0,0,null);
        listing = network.submit(() -> {
            try {
                DriveApi.Page result = new DriveApi(GoogleAuthorization.transport(this,lease)).list(queryFolder,query,page,queryView,querySort,queryDrive);
                DiagnosticLog.get().record(DiagnosticLog.Event.LIST_READY,logOperation,"www.googleapis.com",null,null,DiagnosticLog.elapsed(logStart),0,null);
                handler.post(() -> {
                    if (isDestroyed() || version != requestVersion) return;
                    try { lease.requireCurrent(); } catch (DriveSession.AuthorizationRequired error) { clearBrowser(); return; }
                    try{browser.accept(result);}catch(IOException invalid){
                        reconnect.failed(false,android.os.SystemClock.elapsedRealtime());
                        loading=false;listingFailed=true;updateControls();message.setText(invalid.getMessage());return;
                    }
                    nextPage = browser.next();
                    listingSession = lease.id();
                    reconnect.succeeded();
                    loading = false; adapter.notifyDataSetChanged(); updateControls();
                    message.setText(files.isEmpty() ? getString(R.string.drive_empty) : "");
                    DiagnosticLog.get().record(DiagnosticLog.Event.LIST_DISPLAYED,logOperation,"www.googleapis.com",null,null,DiagnosticLog.elapsed(logStart),0,null);
                });
            } catch (IOException error) {
                DiagnosticLog.get().record(DiagnosticLog.Event.LIST_FAILED,logOperation,"www.googleapis.com",null,null,DiagnosticLog.elapsed(logStart),0,error);
                boolean transientFailure=BrowseReconnect.transientFailure(error,GoogleNetwork.get().health().api);
                handler.post(() -> {
                    if (isDestroyed() || version != requestVersion) return;
                    if (!DriveSession.INSTANCE.connected()) clearBrowser();
                    reconnect.failed(transientFailure,android.os.SystemClock.elapsedRealtime());
                    loading = false; listingFailed=DriveSession.INSTANCE.connected(); updateControls();
                    message.setText(error.getMessage());
                });
            }
        });
    }

    private void enterFolder(DriveApi.FileEntry entry) {
        if(parents.size()>=100){message.setText(R.string.drive_folder_depth);return;}
        parents.push(new String[]{folder,folderName,currentView.name(),currentDrive});
        if(!entry.driveId.isEmpty())currentDrive=entry.driveId;
        currentView=DriveApi.View.FOLDER; searchField.setHint(getString(R.string.drive_search_hint));
        folder = entry.id; folderName = entry.name; activeSearch = "";
        search.setText(""); location.setText(folderName); load(false);
    }
    private void goUp() {
        if (parents.isEmpty()) { finish(); return; }
        String[] previous = parents.pop(); folder = previous[0]; folderName = previous[1]; activeSearch = "";
        currentView=DriveApi.View.valueOf(previous[2]);
        currentDrive=previous[3];
        searchField.setHint(getString(currentView==DriveApi.View.FOLDER?R.string.drive_search_hint:R.string.drive_search_all));
        search.setText(""); location.setText(folderName); load(false);
    }

    private void download(DriveApi.FileEntry file) {
        startDownload(file,null);
    }
    private void downloadSelection() {
        if (downloadSelection.isEmpty() || loading || editing) return;
        startDownload(null,new ArrayList<>(downloadSelection.values()));
    }
    private void startDownload(DriveApi.FileEntry file,List<DriveApi.FileEntry> selected) {
        if (!DriveSession.INSTANCE.connected()) { clearBrowser(); return; }
        if (DownloadService.snapshot.active) { message.setText(R.string.drive_download_busy); return; }
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},702);
        Intent request = new Intent(this,DownloadService.class).setAction(DownloadService.ACTION_START);
        request.putExtra(DownloadService.EXTRA_THREADS,getSharedPreferences("transfer-settings",MODE_PRIVATE).getInt("workers",4));
        try {
            DriveSession.Lease lease=DriveSession.INSTANCE.lease();
            request.putExtra(DownloadService.EXTRA_DRIVE_SESSION,lease.id());
            if (selected==null) {
                request.putExtra(DownloadService.EXTRA_DRIVE_ID,file.id);
            } else {
                if (lease.id()!=listingSession) throw new DriveSession.AuthorizationRequired();
                String[] ids=new String[selected.size()];
                for (int i=0;i<ids.length;i++) {
                    DriveApi.FileEntry entry=selected.get(i);
                    lease.rememberKey(entry.id,entry.resourceKey);
                    ids[i]=entry.id;
                }
                request.putExtra(DownloadService.EXTRA_DRIVE_IDS,ids);
            }
        }
        catch (DriveSession.AuthorizationRequired error) { clearBrowser(); return; }
        catch (IOException error) { message.setText(R.string.drive_download_failed); return; }
        try {
            startForegroundService(request);
            if (selected!=null) clearDownloadSelection();
            message.setText(selected==null?R.string.drive_download_started:R.string.drive_batch_started);
        }
        catch (RuntimeException error) { message.setText(R.string.drive_download_failed); }
    }
    private void toggleDownloadSelection(DriveApi.FileEntry entry) {
        if (loading || editing || !entry.downloadable || !files.contains(entry)) return;
        if (DownloadService.snapshot.active) { message.setText(R.string.drive_download_busy); return; }
        try {
            if (listingSession!=DriveSession.INSTANCE.lease().id()) { clearBrowser(); return; }
            if (downloadSelection.remove(entry.id)==null) {
                if (downloadSelection.size()>=DownloadBatch.LIMIT) {
                    message.setText(getString(R.string.drive_selection_limit,DownloadBatch.LIMIT));return;
                }
                downloadSelection.put(entry.id,entry);
            }
            updateSelectionPanel();
            adapter.notifyDataSetChanged();
        } catch (DriveSession.AuthorizationRequired error) { clearBrowser(); }
    }
    private void clearDownloadSelection() {
        if (downloadSelection.isEmpty()) return;
        downloadSelection.clear();
        updateSelectionPanel();
        adapter.notifyDataSetChanged();
    }
    private void updateSelectionPanel() {
        int count=downloadSelection.size();
        selectionPanel.setVisibility(count==0?View.GONE:View.VISIBLE);
        selectionCount.setText(getString(R.string.drive_selected_count,count));
        downloadSelected.setEnabled(count>0&&!loading&&!editing&&!DownloadService.snapshot.active);
    }
    private void openShortcut(DriveApi.FileEntry entry) {
        if(loading||editing)return;
        DriveSession.Lease lease;try{lease=DriveSession.INSTANCE.lease();}catch(IOException e){clearBrowser();return;}
        int version=++requestVersion;loading=true;updateControls();message.setText(R.string.drive_loading);
        listing=network.submit(()->{
            try {
                DriveApi.FileEntry target=new DriveApi(GoogleAuthorization.transport(this,lease)).resolve(entry);
                handler.post(()->{
                    if(isDestroyed()||version!=requestVersion)return;loading=false;updateControls();
                    try{lease.requireCurrent();}catch(IOException e){clearBrowser();return;}
                    if(target.folder)enterFolder(target);
                    else if(target.previewable())openPreview(target);
                    else if(target.downloadable)fileDetails(target);else message.setText(R.string.drive_unsupported);
                });
            }catch(IOException e){handler.post(()->{if(!isDestroyed()&&version==requestVersion){loading=false;updateControls();message.setText(e.getMessage());}});}
        });
    }
    private void openPreview(DriveApi.FileEntry entry) {
        if(PreviewPolicy.media(entry.previewMime)&&entry.size>32L*1024*1024) {
            new MaterialAlertDialogBuilder(this).setTitle(R.string.drive_preview).setMessage(getString(R.string.drive_preview_full_media,android.text.format.Formatter.formatFileSize(this,entry.size)))
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.drive_preview,(d,w)->startPreview(entry)).show();return;
        }
        startPreview(entry);
    }
    private void startPreview(DriveApi.FileEntry entry) {
        try{startActivity(new Intent(this,FilePreviewActivity.class).putExtra("file",entry.id).putExtra("session",DriveSession.INSTANCE.lease().id()));}catch(IOException e){clearBrowser();}
    }

    private void requestManagement() {
        if(editing||authorizing||GoogleAuthorization.revoking.get())return;
        if(DownloadService.snapshot.active){message.setText(R.string.drive_download_busy);return;}
        new MaterialAlertDialogBuilder(this).setTitle(R.string.drive_manage_access).setMessage(R.string.drive_manage_explanation)
            .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.drive_manage_allow,(d,w)->authorize(true)).show();
    }
    private void chooseUpload() {
        if(!managementReady())return;
        if(DownloadService.snapshot.active){message.setText(R.string.drive_download_busy);return;}
        try{uploadSession=DriveSession.INSTANCE.lease().id();}catch(IOException e){clearBrowser();return;}
        uploadParent=folder;
        if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},702);
        Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        try{startActivityForResult(picker,703);}catch(RuntimeException e){message.setText(R.string.drive_upload_failed);uploadParent=null;}
    }
    private boolean managementReady() {
        if(!DriveSession.INSTANCE.connected()){clearBrowser();return false;}
        if(!DriveSession.INSTANCE.writable()){requestManagement();return false;}
        return !editing&&!loading;
    }
    private interface FileEdit { void run(DriveApi api)throws IOException; }
    private void runEdit(FileEdit operation) {
        if(!managementReady())return;
        DriveSession.Lease lease;
        try{lease=DriveSession.INSTANCE.lease();}catch(DriveSession.AuthorizationRequired e){clearBrowser();return;}
        boolean accepted=MutationCoordinator.INSTANCE.submit(lease.id(),()->{
            lease.requireWrite();operation.run(new DriveApi(GoogleAuthorization.transport(getApplicationContext(),lease)));
        },outcome->handler.post(()->{
                if(isDestroyed())return;
                observedMutation=MutationCoordinator.INSTANCE.revision();updateControls();adapter.notifyDataSetChanged();
                try{lease.requireCurrent();}catch(DriveSession.AuthorizationRequired e){if(DriveSession.INSTANCE.connected())load(false);else clearBrowser();return;}
                if(outcome!=null){message.setText(outcome);listingFailed=true;retryAppend=false;updateControls();}else load(false);
            }));
        if(!accepted){updateControls();return;}
        requestVersion++;if(listing!=null)listing.cancel(true);
        loading=false;updateControls();adapter.notifyDataSetChanged();message.setText(R.string.drive_editing);
    }
    private void editName(DriveApi.FileEntry entry) {
        if(!managementReady())return;
        String parent=folder;
        TextInputLayout field=new TextInputLayout(this);field.setPadding(dp(20),dp(8),dp(20),0);
        field.setHint(getString(R.string.drive_name));
        TextInputEditText input=new TextInputEditText(field.getContext());input.setSingleLine(true);input.setMinHeight(dp(48));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(255)});
        if(entry!=null)input.setText(entry.name);field.addView(input);
        androidx.appcompat.app.AlertDialog dialog=new MaterialAlertDialogBuilder(this).setTitle(entry==null?R.string.drive_new_folder:R.string.drive_rename)
            .setView(field).setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.drive_save,null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            String name=input.getText()==null?"":input.getText().toString();
            try{DriveApi.requireName(name);}catch(IOException e){field.setError(e.getMessage());return;}
            dialog.dismiss();runEdit(api->{if(entry==null)api.createFolder(parent,name);else api.rename(entry.id,name);});
        }));dialog.show();
    }
    private int fileMenuLabel(FileMenu.Action action,DriveApi.FileEntry entry){
        switch(action){
            case OPEN:return entry.shortcut?R.string.drive_open_shortcut:entry.folder?R.string.drive_open_folder:R.string.drive_preview;
            case DOWNLOAD:return entry.exportMime.isEmpty()?R.string.drive_download:R.string.drive_export;
            case SELECT_DOWNLOAD:return R.string.drive_select_for_download;
            case DETAILS:return R.string.drive_details;
            case SHARE:return R.string.drive_share;
            case COPY_LINK:return R.string.drive_copy_link;
            case RENAME:return R.string.drive_rename;
            case STAR:return entry.starred?R.string.drive_unstar:R.string.drive_star;
            case MOVE:return R.string.drive_move;
            case RESTORE:return R.string.drive_restore;
            case TRASH:return R.string.drive_trash;
            case DELETE:return R.string.drive_delete_forever;
            default:throw new IllegalArgumentException("Unknown file action");
        }
    }
    private void performFileAction(FileMenu.Action action,DriveApi.FileEntry entry){
        switch(action){
            case OPEN:if(entry.shortcut)openShortcut(entry);else if(entry.folder)enterFolder(entry);else openPreview(entry);break;
            case DOWNLOAD:download(entry);break;
            case SELECT_DOWNLOAD:toggleDownloadSelection(entry);break;
            case DETAILS:fileDetails(entry);break;
            case SHARE:sharing(entry);break;
            case COPY_LINK:copyLink(entry);break;
            case RENAME:editName(entry);break;
            case STAR:runEdit(api->api.star(entry.id,!entry.starred));break;
            case MOVE:chooseDestination(entry);break;
            case RESTORE:runEdit(api->api.trash(entry.id,false));break;
            case TRASH:case DELETE:
                if(!managementReady())return;
                boolean permanently=action==FileMenu.Action.DELETE;
                new MaterialAlertDialogBuilder(this).setTitle(permanently?R.string.drive_delete_forever:R.string.drive_trash)
                    .setMessage(getString(permanently?R.string.drive_delete_forever_warning:R.string.drive_trash_confirm,entry.name))
                    .setNegativeButton(android.R.string.cancel,null).setPositiveButton(permanently?R.string.drive_delete_forever:R.string.drive_trash,(dialog,n)->{
                        if(permanently)runEdit(api->api.deletePermanently(entry.id));else runEdit(api->api.trash(entry.id,true));
                    }).show();break;
        }
    }
    private void chooseDestination(DriveApi.FileEntry entry) {
        if(!managementReady())return;
        LinearLayout content=column();content.setPadding(dp(20),0,dp(20),0);
        TextView path=label(getString(R.string.drive_root),16),state=label("",14);
        Button back=button(R.string.drive_up),next=button(R.string.drive_more),shared=button(R.string.drive_shared_drives);
        android.widget.ArrayAdapter<String> labels=new android.widget.ArrayAdapter<>(this,android.R.layout.simple_list_item_1);
        ListView list=new ListView(this);list.setAdapter(labels);
        content.addView(path);content.addView(back);content.addView(shared);content.addView(state);content.addView(list,new LinearLayout.LayoutParams(-1,dp(240)));content.addView(next);
        List<DriveApi.FileEntry> folders=new ArrayList<>();Deque<String[]> stack=new ArrayDeque<>();
        String[] target={"root",getString(R.string.drive_root)},page={""};int[] generation={0};Future<?>[] job={null};
        androidx.appcompat.app.AlertDialog dialog=new MaterialAlertDialogBuilder(this).setTitle(R.string.drive_move).setView(content)
            .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.drive_move_here,null).create();
        class Picker {
            void load(boolean append) {
                int version=++generation[0];if(job[0]!=null)job[0].cancel(true);
                if(!append){folders.clear();labels.clear();page[0]="";}
                path.setText(target[1]);state.setText(R.string.drive_loading);back.setEnabled(false);next.setEnabled(false);shared.setEnabled(false);list.setEnabled(false);dialog.getButton(-1).setEnabled(false);
                String parent=target[0],token=page[0];DriveSession.Lease lease;
                try{lease=DriveSession.INSTANCE.lease();}catch(IOException e){state.setText(e.getMessage());return;}
                job[0]=network.submit(()->{
                    try {
                        DriveApi api=new DriveApi(GoogleAuthorization.transport(DriveActivity.this,lease));
                        DriveApi.Page result=parent.isEmpty()?api.list("root","",token,DriveApi.View.DRIVES,DriveApi.Sort.NAME):api.folders(parent,token);
                        handler.post(()->{
                            if(isDestroyed()||!dialog.isShowing()||version!=generation[0])return;
                            try{lease.requireCurrent();}catch(IOException e){state.setText(e.getMessage());return;}
                            folders.addAll(result.files);for(DriveApi.FileEntry file:result.files)labels.add(file.name);
                            page[0]=result.nextToken;state.setText(folders.isEmpty()?getString(R.string.drive_no_folders):getString(R.string.drive_choose_folder));
                            back.setEnabled(!stack.isEmpty());next.setEnabled(!page[0].isEmpty());shared.setEnabled(!parent.isEmpty());list.setEnabled(true);dialog.getButton(-1).setEnabled(!parent.isEmpty()&&!entry.id.equals(parent));
                        });
                    }catch(IOException e){handler.post(()->{if(dialog.isShowing()&&version==generation[0]){state.setText(e.getMessage());back.setEnabled(!stack.isEmpty());}});}
                });
            }
        }
        Picker picker=new Picker();
        list.setOnItemClickListener((p,v,n,id)->{stack.push(target.clone());target[0]=folders.get(n).id;target[1]=folders.get(n).name;picker.load(false);});
        back.setOnClickListener(v->{if(!stack.isEmpty()){String[] old=stack.pop();target[0]=old[0];target[1]=old[1];picker.load(false);}});
        next.setOnClickListener(v->picker.load(true));
        shared.setOnClickListener(v->{stack.push(target.clone());target[0]="";target[1]=getString(R.string.drive_shared_drives);picker.load(false);});
        dialog.setOnDismissListener(d->{generation[0]++;if(job[0]!=null)job[0].cancel(true);});
        dialog.setOnShowListener(d->{dialog.getButton(-1).setOnClickListener(v->{String destination=target[0];dialog.dismiss();runEdit(api->api.move(entry.id,destination));});picker.load(false);});
        dialog.show();
    }
    private void sharing(DriveApi.FileEntry entry) {
        String[] options={getString(R.string.drive_copy_link),getString(R.string.drive_share_person),getString(R.string.drive_share_anyone),getString(R.string.drive_permissions)};
        new MaterialAlertDialogBuilder(this).setTitle(R.string.drive_share).setItems(options,(d,n)->{
            if(n==0) {
                try{String link=DriveApi.sharingLink(entry);((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText(entry.name,link));message.setText(R.string.drive_link_copied);}
                catch(IOException e){message.setText(e.getMessage());}return;
            }
            if(n==1){sharePerson(entry);return;}
            if(n==2){if(!managementReady())return;new MaterialAlertDialogBuilder(this).setTitle(R.string.drive_share_anyone).setMessage(R.string.drive_share_public_warning)
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.drive_share_anyone,(a,b)->runEdit(api->api.share(entry.id,null,false,true))).show();return;}
            showPermissions(entry);
        }).show();
    }
    private void sharePerson(DriveApi.FileEntry entry) {
        if(!managementReady())return;
        LinearLayout content=column();content.setPadding(dp(20),0,dp(20),0);
        TextInputLayout field=new TextInputLayout(this);field.setHint(getString(R.string.drive_email));
        TextInputEditText email=new TextInputEditText(field.getContext());email.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);field.addView(email);content.addView(field);
        email.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(254)});
        android.widget.CheckBox writer=new android.widget.CheckBox(this);writer.setText(R.string.drive_editor);content.addView(writer);
        content.addView(label(getString(R.string.drive_share_notification),14));
        androidx.appcompat.app.AlertDialog dialog=new MaterialAlertDialogBuilder(this).setTitle(R.string.drive_share_person).setView(content).setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.drive_save,null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{String address=email.getText()==null?"":email.getText().toString().trim();
            try{DriveApi.permissionJson(address,writer.isChecked(),false);}catch(IOException e){field.setError(e.getMessage());return;}
            boolean editable=writer.isChecked();dialog.dismiss();runEdit(api->api.share(entry.id,address,editable,false));
        }));dialog.show();
    }
    private void showPermissions(DriveApi.FileEntry entry) {
        if(loading||editing)return;if(!DriveSession.INSTANCE.connected()){clearBrowser();return;}
        DriveSession.Lease lease;try{lease=DriveSession.INSTANCE.lease();}catch(IOException e){clearBrowser();return;}
        loading=true;updateControls();message.setText(R.string.drive_loading);
        int version=++requestVersion;
        listing=network.submit(()->{
            try {
                List<DriveApi.Permission> result=new DriveApi(GoogleAuthorization.transport(this,lease)).permissions(entry.id);
                handler.post(()->{
                    if(isDestroyed()||version!=requestVersion)return;loading=false;updateControls();
                    try{lease.requireCurrent();}catch(IOException e){clearBrowser();return;}
                    String[] labels=new String[result.size()];for(int i=0;i<labels.length;i++)labels[i]=result.get(i).name+" · "+result.get(i).role;
                    message.setText(R.string.drive_permissions);
                    new MaterialAlertDialogBuilder(this).setTitle(R.string.drive_permissions).setItems(labels,(d,n)->{
                        DriveApi.Permission permission=result.get(n);
                        if(!permission.removable){message.setText(R.string.drive_permission_inherited);return;}
                        new MaterialAlertDialogBuilder(this).setTitle(R.string.drive_remove_access).setMessage(getString(R.string.drive_remove_access_confirm,permission.name))
                            .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.drive_remove_access,(a,b)->runEdit(api->api.removePermission(entry.id,permission.id))).show();
                    }).setNegativeButton(android.R.string.cancel,null).show();
                });
            }catch(IOException e){handler.post(()->{if(!isDestroyed()&&version==requestVersion){loading=false;updateControls();message.setText(e.getMessage());}});}
        });
    }

    private void copyLink(DriveApi.FileEntry entry) {
        try{String link=DriveApi.sharingLink(entry);((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText(entry.name,link));message.setText(R.string.drive_link_copied);}
        catch(IOException error){message.setText(error.getMessage());}
    }
    private boolean rememberEntry(DriveApi.FileEntry entry) {
        try{DriveSession.INSTANCE.lease().rememberKey(entry.id,entry.resourceKey);return true;}
        catch(IOException e){message.setText(e.getMessage());return false;}
    }
    private void fileDetails(DriveApi.FileEntry entry) {
        MaterialAlertDialogBuilder details=new MaterialAlertDialogBuilder(this).setTitle(entry.name)
            .setMessage(getString(R.string.drive_details_body,entry.mime,entry.size<0?getString(R.string.drive_size_unknown):android.text.format.Formatter.formatFileSize(this,entry.size),entry.modified.isEmpty()?getString(R.string.drive_size_unknown):entry.modified))
            .setPositiveButton(android.R.string.ok,null);
        if(entry.downloadable&&!DownloadService.snapshot.active)details.setNeutralButton(entry.exportMime.isEmpty()?R.string.drive_download:R.string.drive_export,(dialog,which)->download(entry));
        details.show();
    }
    private final class FileAdapter extends BaseAdapter {
        private final class Row {
            final LinearLayout root=new LinearLayout(DriveActivity.this);
            final LinearLayout content=column();
            final TextView name=label("",16),detail=label("",14);
            final android.widget.ImageView icon=new android.widget.ImageView(DriveActivity.this);
            final androidx.appcompat.widget.AppCompatImageButton more=new androidx.appcompat.widget.AppCompatImageButton(DriveActivity.this);
            Row(){
                root.setGravity(Gravity.CENTER_VERTICAL);root.setMinimumHeight(dp(72));root.setPadding(0,dp(8),0,dp(8));
                name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);detail.setMaxLines(1);detail.setEllipsize(TextUtils.TruncateAt.END);
                content.addView(name);content.addView(detail);content.setPadding(0,0,dp(8),0);content.setMinimumHeight(dp(48));content.setGravity(Gravity.CENTER_VERTICAL);
                icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);icon.setPadding(dp(8),dp(8),dp(8),dp(8));
                root.addView(icon,new LinearLayout.LayoutParams(dp(48),dp(48)));
                content.setPadding(dp(8),0,dp(8),0);
                detail.setTextColor(com.google.android.material.color.MaterialColors.getColor(DriveActivity.this,com.google.android.material.R.attr.colorOnSurfaceVariant,android.graphics.Color.GRAY));
                root.addView(content,new LinearLayout.LayoutParams(0,-2,1));
                more.setImageResource(R.drawable.ic_more_vert);more.setScaleType(android.widget.ImageView.ScaleType.CENTER);
                more.setImageTintList(android.content.res.ColorStateList.valueOf(com.google.android.material.color.MaterialColors.getColor(DriveActivity.this,com.google.android.material.R.attr.colorOnSurface,android.graphics.Color.BLACK)));
                android.util.TypedValue ripple=new android.util.TypedValue();getTheme().resolveAttribute(android.R.attr.selectableItemBackground,ripple,true);
                content.setBackgroundResource(ripple.resourceId);more.setBackgroundResource(ripple.resourceId);
                root.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));root.setTag(this);
            }
        }
        @Override public int getCount(){return files.size();}
        @Override public Object getItem(int n){return files.get(n);}
        @Override public long getItemId(int n){return n;}
        @Override public View getView(int n,View old,ViewGroup parent) {
            DriveApi.FileEntry entry=files.get(n);Row row=old!=null&&old.getTag() instanceof FileAdapter.Row?(Row)old.getTag():new Row();
            row.name.setText(entry.name);
            boolean selecting=!downloadSelection.isEmpty(), selected=downloadSelection.containsKey(entry.id);
            row.root.setBackgroundColor(selected?com.google.android.material.color.MaterialColors.getColor(DriveActivity.this,
                com.google.android.material.R.attr.colorSecondaryContainer,android.graphics.Color.TRANSPARENT):android.graphics.Color.TRANSPARENT);
            row.icon.setImageResource(selected?android.R.drawable.checkbox_on_background:entry.folder?R.drawable.ic_folder:entry.mime.startsWith("image/")?R.drawable.ic_image:entry.mime.startsWith("video/")?R.drawable.ic_video:R.drawable.ic_file);
            row.icon.setImageTintList(ColorStateList.valueOf(com.google.android.material.color.MaterialColors.getColor(DriveActivity.this,entry.folder?com.google.android.material.R.attr.colorOnSurfaceVariant:androidx.appcompat.R.attr.colorPrimary,android.graphics.Color.GRAY)));
            boolean previewable=entry.previewable();
            String detail=entry.shortcut?getString(R.string.drive_shortcut):entry.folder?getString(R.string.drive_folder):!entry.exportMime.isEmpty()&&entry.downloadable?getString(R.string.drive_export_format,entry.exportSuffix):entry.downloadable?android.text.format.Formatter.formatFileSize(DriveActivity.this,entry.size):getString(R.string.drive_unsupported);
            if(entry.downloadable&&entry.size<0&&entry.exportMime.isEmpty())detail=getString(R.string.drive_size_unknown);
            if(!entry.modified.isEmpty())try {
                long modified=java.time.Instant.parse(entry.modified).toEpochMilli();
                detail+=" · "+android.text.format.DateUtils.getRelativeTimeSpanString(modified,System.currentTimeMillis(),android.text.format.DateUtils.MINUTE_IN_MILLIS);
            }catch(java.time.format.DateTimeParseException ignored){}
            row.detail.setText(detail);
            int descriptionAction=entry.trashed?R.string.drive_details:entry.shortcut?R.string.drive_open_shortcut:entry.folder?R.string.drive_open_folder:previewable?R.string.drive_preview:R.string.drive_details;
            if (selecting&&entry.downloadable) descriptionAction=selected?R.string.drive_deselect_file:R.string.drive_select_for_download;
            row.content.setContentDescription(getString(R.string.drive_action_named,getString(descriptionAction),entry.name+" · "+detail));
            row.name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.detail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.content.setEnabled(!loading&&!editing&&(!selecting||entry.downloadable));row.content.setFocusable(true);
            row.content.setSelected(selected);
            row.content.setOnLongClickListener(entry.downloadable?v->{toggleDownloadSelection(entry);return true;}:null);
            row.content.setOnClickListener(v->{if(!downloadSelection.isEmpty()){toggleDownloadSelection(entry);return;}if(!rememberEntry(entry))return;if(entry.trashed)fileDetails(entry);else if(entry.shortcut)openShortcut(entry);else if(entry.folder)enterFolder(entry);else if(previewable)openPreview(entry);else fileDetails(entry);});
            row.more.setContentDescription(getString(R.string.drive_action_named,getString(R.string.drive_more_actions),entry.name));row.more.setEnabled(!loading&&!editing);
            row.more.setVisibility(selecting?View.INVISIBLE:View.VISIBLE);
            row.more.setOnClickListener(v->{
                androidx.appcompat.widget.PopupMenu popup=new androidx.appcompat.widget.PopupMenu(DriveActivity.this,row.more);
                int menuVersion=requestVersion;
                for(FileMenu.Action action:FileMenu.actions(entry,currentView==DriveApi.View.DRIVES)){
                    popup.getMenu().add(action.group,action.ordinal()+1,action.ordinal(),fileMenuLabel(action,entry))
                        .setEnabled(!DownloadService.snapshot.active ||
                            (action!=FileMenu.Action.DOWNLOAD && action!=FileMenu.Action.SELECT_DOWNLOAD));
                }
                popup.getMenu().setGroupDividerEnabled(true);
                popup.setOnMenuItemClickListener(item->{
                    if(menuVersion!=requestVersion||loading||editing||!rememberEntry(entry))return true;
                    performFileAction(FileMenu.Action.values()[item.getItemId()-1],entry);return true;
                });popup.show();
            });return row.root;
        }
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(1,6,0,R.string.drive_refresh);
        menu.add(1,10,1,R.string.drive_sort);
        menu.add(2,13,2,R.string.drive_public_download);
        menu.add(2,9,3,R.string.drive_transfer_details);
        menu.add(3,11,4,R.string.settings_title);
        menu.setGroupDividerEnabled(true);
        return true;
    }
    @Override public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(6).setEnabled(DriveSession.INSTANCE.connected()&&!loading&&!editing&&!authorizing);
        menu.findItem(10).setEnabled(DriveSession.INSTANCE.connected()&&!loading&&!editing&&currentView!=DriveApi.View.DRIVES);
        return super.onPrepareOptionsMenu(menu);
    }
    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if(item.getItemId()==13){startActivity(AppScreens.intent(this,MainActivity.class));return true;}
        if (item.getItemId() == android.R.id.home) { if(!activeSearch.isEmpty())clearSearch();else goUp(); return true; }
        if(item.getItemId()==6){load(false);return true;}
        if(item.getItemId()==9){startActivity(AppScreens.intent(this,TransferActivity.class));return true;}
        if(item.getItemId()==10){
            new MaterialAlertDialogBuilder(this).setTitle(R.string.drive_sort)
                .setSingleChoiceItems(R.array.drive_sorts,currentSort.ordinal(),(dialog,n)->{
                    dialog.dismiss();DriveApi.Sort choice=DriveApi.Sort.values()[n];
                    if(choice!=currentSort){currentSort=choice;load(false);}
                }).setNegativeButton(android.R.string.cancel,null).show();return true;
        }
        if(item.getItemId()==11){
            Intent settings=AppScreens.intent(this,SettingsActivity.class).putExtra("authorization_phase",authorizationPhase);
            if(lastActivityResult!=null)settings.putExtra("last_activity_result",lastActivityResult);
            if(lastSdkStatus!=null)settings.putExtra("last_sdk_status",lastSdkStatus);
            startActivity(settings);return true;
        }
        return super.onOptionsItemSelected(item);
    }
    private void revoke() {
        if(DriveSession.INSTANCE.scopes().isEmpty()){message.setText(R.string.drive_revoke_connect);return;}
        if (!GoogleAuthorization.revoking.compareAndSet(false,true)) return;
        List<Scope> revokeScopes=new ArrayList<>();for(String scope:DriveSession.INSTANCE.scopes())revokeScopes.add(new Scope(scope));
        if(revokeScopes.isEmpty())revokeScopes.add(new Scope(DriveApi.READONLY_SCOPE));
        RevokeAccessRequest.Builder builder=RevokeAccessRequest.builder().setScopes(revokeScopes);
        RememberedAuthorization.Profile profile=GoogleAuthorization.memory(this).profile();
        if(profile!=null)builder.setAccount(new android.accounts.Account(profile.account,"com.google"));
        RevokeAccessRequest request=builder.build();
        disconnect();
        message.setText(R.string.drive_revoking); updateControls();
        Identity.getAuthorizationClient(this).revokeAccess(request)
            .addOnCompleteListener(result -> {
                GoogleAuthorization.lastRevokeSucceeded = result.isSuccessful();
                GoogleAuthorization.revoking.set(false);
                if (!isDestroyed()) {
                    message.setText(result.isSuccessful() ? R.string.drive_revoked : R.string.drive_revoke_failed);
                    updateControls();
                }
            });
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putLong("mutation_revision",observedMutation);
        state.putBoolean("management_requested",managementRequested);
        state.putLong("authorization_ticket",authorizationTicket);
        state.putString("upload_parent",uploadParent);state.putLong("upload_session",uploadSession);
        try {
            if(DriveSession.INSTANCE.connected()) {
                org.json.JSONArray history=new org.json.JSONArray();
                for(String[] entry:parents)history.put(new org.json.JSONArray().put(entry[0]).put(entry[1].substring(0,Math.min(entry[1].length(),512))).put(entry[2]).put(entry[3]));
                org.json.JSONObject browser=new org.json.JSONObject().put("folder",folder).put("name",folderName.substring(0,Math.min(folderName.length(),512))).put("view",currentView.name()).put("sort",currentSort.name()).put("drive",currentDrive).put("query",activeSearch).put("parents",history);
                state.putLong("browser_session",DriveSession.INSTANCE.lease().id());state.putString("browser_state",browser.toString());
            }
        }catch(IOException|org.json.JSONException ignored){}
        state.putBoolean("waiting_resolution",waitingResolution);
        state.putString("authorization_phase",authorizationPhase);
        if (lastActivityResult != null) state.putInt("last_activity_result",lastActivityResult);
        if (lastSdkStatus != null) state.putInt("last_sdk_status",lastSdkStatus);
        super.onSaveInstanceState(state);
    }
    @Override protected void onResume() {
        super.onResume();handler.post(ticker);
        int mode=GoogleNetwork.get().connectionMode();
        if(mode!=observedConnectionMode){observedConnectionMode=mode;if(DriveSession.INSTANCE.connected())load(false);}
    }
    @Override protected void onPause() { handler.removeCallbacks(ticker); super.onPause(); }
    @Override protected void onDestroy() {
        requestVersion++; if (listing != null) listing.cancel(true); network.shutdownNow(); handler.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
