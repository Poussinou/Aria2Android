package com.gianlu.aria2android;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AlertDialog;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.gianlu.aria2android.Aria2.BinService;
import com.gianlu.aria2android.Aria2.StartConfig;
import com.gianlu.commonutils.Analytics.AnalyticsApplication;
import com.gianlu.commonutils.AskPermission;
import com.gianlu.commonutils.Dialogs.ActivityWithDialog;
import com.gianlu.commonutils.Logging;
import com.gianlu.commonutils.MessageView;
import com.gianlu.commonutils.Preferences.Prefs;
import com.gianlu.commonutils.Toaster;
import com.yarolegovich.lovelydialog.LovelyTextInputDialog;
import com.yarolegovich.lovelyuserinput.LovelyInput;
import com.yarolegovich.mp.AbsMaterialPreference;
import com.yarolegovich.mp.AbsMaterialTextValuePreference;
import com.yarolegovich.mp.MaterialCheckboxPreference;
import com.yarolegovich.mp.MaterialEditTextPreference;
import com.yarolegovich.mp.MaterialPreferenceCategory;
import com.yarolegovich.mp.MaterialPreferenceScreen;
import com.yarolegovich.mp.MaterialSeekBarPreference;
import com.yarolegovich.mp.MaterialStandardPreference;
import com.yarolegovich.mp.io.MaterialPreferences;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

public class MainActivity extends ActivityWithDialog {
    private static final int STORAGE_ACCESS_CODE = 1;
    private boolean isRunning;
    private ServiceBroadcastReceiver receiver;
    private Messenger serviceMessenger;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            serviceMessenger = new Messenger(iBinder);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            serviceMessenger = null;
        }
    };
    private ToggleButton toggleServer;
    private MaterialEditTextPreference outputPath;
    private MaterialPreferenceCategory generalCategory;
    private MaterialPreferenceCategory rpcCategory;
    private MaterialPreferenceCategory notificationsCategory;
    private LinearLayout logsContainer;
    private MessageView logsMessage;

    private static boolean isARM() {
        for (String abi : Build.SUPPORTED_ABIS)
            if (abi.contains("arm"))
                return true;

        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == STORAGE_ACCESS_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                Uri uri = data.getData();
                if (uri != null) {
                    outputPath.setValue(FileUtil.getFullPathFromTreeUri(uri, this));
                    int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    getContentResolver().takePersistableUriPermission(uri, takeFlags);
                }
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!BinUtils.binAvailable(this)) {
            startActivity(new Intent(this, DownloadBinActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            return;
        }

        if (!isARM() && !Prefs.getBoolean(PK.CUSTOM_BIN)) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(R.string.archNotSupported)
                    .setMessage(R.string.archNotSupported_message)
                    .setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                            finish();
                        }
                    }).setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    finish();
                }
            }).setNeutralButton(R.string.importBin, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    startActivity(new Intent(MainActivity.this, DownloadBinActivity.class)
                            .putExtra("importBin", true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                }
            }).setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    finish();
                }
            });

            showDialog(builder);
            return;
        }

        if (Objects.equals(getIntent().getAction(), BinService.ACTION_START_SERVICE)) {
            toggleService(true);
            finish();
            return;
        }

        if (Objects.equals(getIntent().getAction(), BinService.ACTION_STOP_SERVICE)) {
            toggleService(false);
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        MaterialPreferences.instance().setUserInputModule(new LovelyInput.Builder()
                .addIcon(PK.OUTPUT_DIRECTORY.key(), R.drawable.baseline_folder_24)
                .addTextFilter(PK.OUTPUT_DIRECTORY.key(), R.string.invalidOutputPath, new LovelyTextInputDialog.TextFilter() {
                    @Override
                    public boolean check(String text) {
                        File path = new File(text);
                        return path.exists() && path.canWrite();
                    }
                })
                .addIcon(PK.RPC_PORT.key(), R.drawable.baseline_import_export_24)
                .addTextFilter(PK.RPC_PORT.key(), R.string.invalidPort, new LovelyTextInputDialog.TextFilter() {
                    @Override
                    public boolean check(String text) {
                        try {
                            int port = Integer.parseInt(text);
                            return port > 0 && port < 65536;
                        } catch (Exception ex) {
                            Logging.log(ex);
                            return false;
                        }
                    }
                })
                .addIcon(PK.RPC_TOKEN.key(), R.drawable.baseline_vpn_key_24)
                .addTextFilter(PK.RPC_TOKEN.key(), R.string.invalidToken, new LovelyTextInputDialog.TextFilter() {
                    @Override
                    public boolean check(String text) {
                        return !text.isEmpty();
                    }
                })
                .addIcon(PK.NOTIFICATION_UPDATE_DELAY.key(), R.drawable.baseline_notifications_24)
                .setTopColor(ContextCompat.getColor(this, R.color.colorPrimary))
                .build());

        MaterialPreferenceScreen screen = findViewById(R.id.main_preferences);

        // General
        generalCategory = new MaterialPreferenceCategory(this);
        generalCategory.setTitle(R.string.general);
        screen.addView(generalCategory);

        outputPath = new MaterialEditTextPreference.Builder(this)
                .showValueMode(AbsMaterialTextValuePreference.SHOW_ON_BOTTOM)
                .key(PK.OUTPUT_DIRECTORY.key())
                .defaultValue(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath())
                .build();
        outputPath.setTitle(R.string.outputPath);
        outputPath.setOverrideClickListener(new AbsMaterialPreference.OverrideOnClickListener() {
            @Override
            public boolean onClick(View v) {
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    startActivityForResult(intent, STORAGE_ACCESS_CODE);
                    return true;
                } catch (ActivityNotFoundException ex) {
                    Toaster.with(MainActivity.this).message(R.string.noOpenTree).ex(ex).show();
                    return false;
                }
            }
        });
        generalCategory.addView(outputPath);

        MaterialCheckboxPreference saveSession = new MaterialCheckboxPreference.Builder(this)
                .key(PK.SAVE_SESSION.key())
                .defaultValue(PK.SAVE_SESSION.fallback())
                .build();
        saveSession.setTitle(R.string.saveSession);
        saveSession.setSummary(R.string.saveSession_summary);
        generalCategory.addView(saveSession);

        MaterialCheckboxPreference startAtBoot = new MaterialCheckboxPreference.Builder(this)
                .key(PK.START_AT_BOOT.key())
                .defaultValue(PK.START_AT_BOOT.fallback())
                .build();
        startAtBoot.setTitle(R.string.startServiceAtBoot);
        startAtBoot.setSummary(R.string.startServiceAtBoot_summary);
        generalCategory.addView(startAtBoot);

        MaterialStandardPreference customOptions = new MaterialStandardPreference(this);
        customOptions.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ConfigEditorActivity.class));
            }
        });
        customOptions.setTitle(R.string.customOptions);
        generalCategory.addView(customOptions);

        // UI
        MaterialPreferenceCategory uiCategory = new MaterialPreferenceCategory(this);
        uiCategory.setTitle(R.string.ui);
        screen.addView(uiCategory);

        MaterialStandardPreference openAria2App = new MaterialStandardPreference(this);
        openAria2App.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAria2App();
            }
        });
        openAria2App.setTitle(R.string.openAria2App);
        openAria2App.setSummary(R.string.openAria2App_summary);
        uiCategory.addView(openAria2App);

        // RPC
        rpcCategory = new MaterialPreferenceCategory(this);
        rpcCategory.setTitle(R.string.rpc);
        screen.addView(rpcCategory);

        MaterialEditTextPreference rpcPort = new MaterialEditTextPreference.Builder(this)
                .showValueMode(AbsMaterialTextValuePreference.SHOW_ON_RIGHT)
                .key(PK.RPC_PORT.key())
                .defaultValue(String.valueOf(PK.RPC_PORT.fallback()))
                .build();
        rpcPort.setTitle(R.string.rpcPort);
        rpcCategory.addView(rpcPort);

        MaterialEditTextPreference rpcToken = new MaterialEditTextPreference.Builder(this)
                .showValueMode(AbsMaterialTextValuePreference.SHOW_ON_RIGHT)
                .key(PK.RPC_TOKEN.key())
                .defaultValue(String.valueOf(PK.RPC_TOKEN.fallback()))
                .build();
        rpcToken.setTitle(R.string.rpcToken);
        rpcCategory.addView(rpcToken);

        MaterialCheckboxPreference allowOriginAll = new MaterialCheckboxPreference.Builder(this)
                .key(PK.RPC_ALLOW_ORIGIN_ALL.key())
                .defaultValue(PK.RPC_ALLOW_ORIGIN_ALL.fallback())
                .build();
        allowOriginAll.setTitle(R.string.accessControlAllowOriginAll);
        allowOriginAll.setSummary(R.string.accessControlAllowOriginAll_summary);
        rpcCategory.addView(allowOriginAll);

        // Notifications
        notificationsCategory = new MaterialPreferenceCategory(this);
        notificationsCategory.setTitle(R.string.notification);
        screen.addView(notificationsCategory);

        MaterialCheckboxPreference showPerformance = new MaterialCheckboxPreference.Builder(this)
                .key(PK.SHOW_PERFORMANCE.key())
                .defaultValue(PK.SHOW_PERFORMANCE.fallback())
                .build();
        showPerformance.setTitle(R.string.showPerformance);
        showPerformance.setSummary(R.string.showPerformance_summary);
        notificationsCategory.addView(showPerformance);

        MaterialSeekBarPreference updateDelay = new MaterialSeekBarPreference.Builder(this)
                .showValue(true).minValue(1).maxValue(5)
                .key(PK.NOTIFICATION_UPDATE_DELAY.key())
                .defaultValue(PK.NOTIFICATION_UPDATE_DELAY.fallback())
                .build();
        updateDelay.setTitle(R.string.updateInterval);
        notificationsCategory.addView(updateDelay);

        screen.setVisibilityController(showPerformance, new AbsMaterialPreference[]{updateDelay}, true);

        // Logs
        MaterialPreferenceCategory logsCategory = new MaterialPreferenceCategory(this);
        logsCategory.setTitle(R.string.logs);
        screen.addView(logsCategory);

        logsMessage = new MessageView(this);
        logsMessage.setInfo(R.string.noLogs);
        logsCategory.addView(logsMessage);
        logsMessage.setVisibility(View.VISIBLE);

        logsContainer = new LinearLayout(this);
        logsContainer.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics());
        logsContainer.setPaddingRelative(pad, 0, pad, 0);
        logsCategory.addView(logsContainer);
        logsContainer.setVisibility(View.GONE);

        MaterialStandardPreference clearLogs = new MaterialStandardPreference(this);
        clearLogs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logsContainer.removeAllViews();
                logsContainer.setVisibility(View.GONE);
                logsMessage.setVisibility(View.VISIBLE);
            }
        });
        clearLogs.setTitle(R.string.clearLogs);
        logsCategory.addView(clearLogs);

        toggleServer = findViewById(R.id.main_toggleServer);
        toggleServer.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                toggleService(isChecked);
            }
        });

        TextView version = findViewById(R.id.main_binVersion);
        version.setText(BinUtils.binVersion(this));

        backwardCompatibility();
    }

    private void toggleService(boolean on) {
        boolean successful;
        if (on) successful = startService();
        else successful = stopService();

        if (successful) {
            int visibility = on ? View.GONE : View.VISIBLE;
            generalCategory.setVisibility(visibility);
            rpcCategory.setVisibility(visibility);
            notificationsCategory.setVisibility(visibility);

            isRunning = on;
            toggleServer.setChecked(on);
        }
    }

    @SuppressWarnings("deprecation")
    private void backwardCompatibility() {
        if (Prefs.getBoolean(PK.DEPRECATED_USE_CONFIG, false)) {
            File file = new File(Prefs.getString(PK.DEPRECATED_CONFIG_FILE, ""));
            if (file.exists() && file.isFile() && file.canRead()) {
                startActivity(new Intent(this, ConfigEditorActivity.class)
                        .putExtra("import", file.getAbsolutePath()));
            }
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unbindService(serviceConnection);
        } catch (IllegalArgumentException ex) {
            Logging.log(ex);
        }

        super.onDestroy();
    }

    private boolean startService() {
        Prefs.putLong(PK.CURRENT_SESSION_START, System.currentTimeMillis());
        AnalyticsApplication.sendAnalytics(this, Utils.ACTION_TURN_ON);

        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            AskPermission.ask(this, Manifest.permission.WRITE_EXTERNAL_STORAGE, new AskPermission.Listener() {
                @Override
                public void permissionGranted(@NonNull String permission) {
                    toggleService(true);
                }

                @Override
                public void permissionDenied(@NonNull String permission) {
                    Toaster.with(MainActivity.this).message(R.string.writePermissionDenied).error(true).show();
                }

                @Override
                public void askRationale(@NonNull AlertDialog.Builder builder) {
                    builder.setTitle(R.string.permissionRequest)
                            .setMessage(R.string.writeStorageMessage);
                }
            });
            return false;
        }

        File sessionFile = new File(getFilesDir(), "session");
        if (Prefs.getBoolean(PK.SAVE_SESSION) && !sessionFile.exists()) {
            try {
                if (!sessionFile.createNewFile()) {
                    Toaster.with(this).message(R.string.failedCreatingSessionFile).error(true).show();
                    return false;
                }
            } catch (IOException ex) {
                Toaster.with(this).message(R.string.failedCreatingSessionFile).ex(ex).show();
                return false;
            }
        }

        IntentFilter filter = new IntentFilter();
        for (BinService.Action action : BinService.Action.values())
            filter.addAction(action.toString());

        receiver = new ServiceBroadcastReceiver();
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, filter);
        try {
            if (serviceMessenger != null) {
                serviceMessenger.send(Message.obtain(null, BinService.START, StartConfig.fromPrefs()));
                return true;
            } else {
                bindService(new Intent(this, BinService.class), serviceConnection, BIND_AUTO_CREATE);
                return false;
            }
        } catch (JSONException ex) {
            Toaster.with(this).message(R.string.failedLoadingOptions).ex(ex).show();
            LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
            return false;
        } catch (RemoteException ex) {
            Toaster.with(this).message(R.string.failedStarting).ex(ex).show();
            LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
            return false;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, BinService.class), serviceConnection, BIND_AUTO_CREATE);
    }

    private boolean stopService() {
        if (serviceMessenger == null) return true;

        try {
            serviceMessenger.send(Message.obtain(null, BinService.STOP, null));
        } catch (RemoteException ex) {
            Toaster.with(this).message(R.string.failedStopping).ex(ex).show();
            return false;
        }

        Bundle bundle = null;
        if (Prefs.getLong(PK.CURRENT_SESSION_START, -1) != -1) {
            bundle = new Bundle();
            bundle.putLong(Utils.LABEL_SESSION_DURATION, System.currentTimeMillis() - Prefs.getLong(PK.CURRENT_SESSION_START, -1));
            Prefs.putLong(PK.CURRENT_SESSION_START, -1);
        }

        AnalyticsApplication.sendAnalytics(this, Utils.ACTION_TURN_OFF, bundle);

        return true;
    }

    private void installAria2App() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.aria2AppNotInstalled)
                .setMessage(R.string.aria2AppNotInstalled_message)
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.gianlu.aria2app")));
                            } catch (android.content.ActivityNotFoundException ex) {
                                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.gianlu.aria2app")));
                            }
                        } catch (ActivityNotFoundException ex) {
                            Logging.log(ex);
                        }
                    }
                }).setNegativeButton(android.R.string.no, null);

        showDialog(builder);
    }

    private void openAria2App() {
        try {
            getPackageManager().getPackageInfo("com.gianlu.aria2app", 0);
        } catch (PackageManager.NameNotFoundException ex) {
            Logging.log(ex);
            installAria2App();
            return;
        }

        if (isRunning) {
            startAria2App();
        } else {
            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setTitle(R.string.aria2NotRunning)
                    .setMessage(R.string.aria2NotRunning_message)
                    .setPositiveButton(android.R.string.no, null)
                    .setNegativeButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            startAria2App();
                        }
                    });

            showDialog(builder);
        }
    }

    private void startAria2App() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("com.gianlu.aria2app");
        if (intent != null) {
            startActivity(intent
                    .putExtra("external", true)
                    .putExtra("port", Prefs.getInt(PK.RPC_PORT))
                    .putExtra("token", Prefs.getString(PK.RPC_TOKEN)));
        }

        AnalyticsApplication.sendAnalytics(this, Utils.ACTION_OPENED_ARIA2APP);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.mainMenu_preferences:
                startActivity(new Intent(this, PreferenceActivity.class));
                return true;
            case R.id.mainMenu_changeBin:
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle(R.string.changeBinVersion)
                        .setMessage(R.string.changeBinVersion_message)
                        .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                if (BinUtils.delete(MainActivity.this)) {
                                    startActivity(new Intent(MainActivity.this, DownloadBinActivity.class)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                                    finish();
                                } else {
                                    Toaster.with(MainActivity.this).message(R.string.cannotDeleteBin).error(true).show();
                                }
                            }
                        }).setNegativeButton(android.R.string.no, null);

                showDialog(builder);
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void addLog(@NonNull Logging.LogLine line) {
        logsContainer.setVisibility(View.VISIBLE);
        logsMessage.setVisibility(View.GONE);
        logsContainer.addView(Logging.LogLineAdapter.createLogLineView(getLayoutInflater(), logsContainer, line), logsContainer.getChildCount());
    }

    private class ServiceBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, final Intent intent) {
            final BinService.Action action = BinService.Action.find(intent);
            if (action != null && intent != null) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        switch (action) {
                            case SERVER_START:
                                addLog(new Logging.LogLine(Logging.LogLine.Type.INFO, getString(R.string.serverStarted)));
                                break;
                            case SERVER_STOP:
                                addLog(new Logging.LogLine(Logging.LogLine.Type.INFO, getString(R.string.serverStopped)));
                                LocalBroadcastManager.getInstance(MainActivity.this).unregisterReceiver(receiver);
                                toggleService(false);
                                break;
                            case SERVER_EX:
                                Exception ex = (Exception) intent.getSerializableExtra("ex");
                                Logging.log(ex);
                                addLog(new Logging.LogLine(Logging.LogLine.Type.ERROR, getString(R.string.serverException, ex.getMessage())));
                                break;
                            case SERVER_MSG:
                                addLog((Logging.LogLine) intent.getSerializableExtra("msg"));
                                break;
                        }
                    }
                });
            }
        }
    }
}
