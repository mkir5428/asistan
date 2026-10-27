package com.asistan.cip;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.util.Base64;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class MainActivity extends Activity {
    private static final int REQUEST_MICROPHONE = 41;
    private static final int REQUEST_WAKE_MICROPHONE = 42;
    private static final int REQUEST_NOTIFICATIONS = 43;
    private static final int REQUEST_OPEN_FILE = 51;
    private static final int REQUEST_PICK_CALL = 52;
    private static final int REQUEST_PICK_SMS = 53;
    private static final int REQUEST_NATIVE_ACTIVITY = 54;
    private static final int REQUEST_TRANSFER_SAVE = 55;
    private static final int REQUEST_TRANSFER_UPLOAD = 56;
    private static final long TRANSFER_POLL_INTERVAL_MS = 5000L;
    private static final String PREFERENCES = "cip_settings";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_SESSION = "session_id";
    private static final String KEY_API_SECRET = "encrypted_api_key";
    private static final String KEYSTORE_ALIAS = "cip_api_key";

    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private SharedPreferences preferences;
    private LinearLayout conversation;
    private ScrollView conversationScroll;
    private EditText messageInput;
    private Button sendButton;
    private Button recordButton;
    private Button wakeWordButton;
    private TextView wakeIndicator;
    private TextView wakeStatus;
    private TextView transferStatus;
    private Button transferToggleButton;
    private LinearLayout transferContent;
    private LinearLayout transferActionsContainer;
    private MediaRecorder recorder;
    private File recordingFile;
    private boolean recording;
    private boolean busy;
    private TextToSpeech textToSpeech;
    private boolean ttsReady;
    private boolean wakeReceiverRegistered;
    private boolean transferPollInProgress;
    private boolean transferPollEnabled;
    private boolean transferUploadBusy;
    private boolean transferExpanded;
    private TransferCredentialStore transferCredentialStore;
    private final WindowsActionApproval transferApproval = new AndroidBiometricApproval();
    private final Handler transferHandler = new Handler(Looper.getMainLooper());
    private TransferAction transferActionAwaitingSave;
    private File transferDownloadFile;
    private TransferAction transferActionAwaitingUpload;
    private File transferUploadFile;

    private final Runnable transferPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (transferPollEnabled) {
                pollTransferActions(false);
                transferHandler.postDelayed(this, TRANSFER_POLL_INTERVAL_MS);
            }
        }
    };

    private final BroadcastReceiver wakeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (WakeWordService.ACTION_STATUS.equals(intent.getAction())) {
                updateWakeStatus(intent.getStringExtra(WakeWordService.EXTRA_STATUS));
            } else if (WakeWordService.ACTION_COMMAND.equals(intent.getAction())) {
                handlePendingWakeCommand(intent.getStringExtra(WakeWordService.EXTRA_COMMAND));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        transferCredentialStore = new TransferCredentialStore(preferences);
        if (preferences.getString(KEY_SESSION, null) == null) {
            preferences.edit().putString(KEY_SESSION, UUID.randomUUID().toString()).apply();
        }
        buildScreen();
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int languageStatus = textToSpeech.setLanguage(new Locale("tr", "TR"));
                ttsReady = languageStatus != TextToSpeech.LANG_MISSING_DATA
                        && languageStatus != TextToSpeech.LANG_NOT_SUPPORTED;
            }
        });
        textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
            }

            @Override
            public void onDone(String utteranceId) {
                runOnUiThread(MainActivity.this::resumeWakeWordServiceIfActive);
            }

            @Override
            public void onError(String utteranceId) {
                runOnUiThread(MainActivity.this::resumeWakeWordServiceIfActive);
            }
        });
        if (preferences.getString(KEY_SERVER_URL, "").isEmpty() || !hasApiKey()) {
            showSettings();
        }
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        root.setBackgroundColor(0xFFF4F7F2);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(16), dp(16), dp(16));
        GradientDrawable headerBackground = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{0xFF087F66, 0xFF175A78});
        headerBackground.setCornerRadius(dp(26));
        header.setBackground(headerBackground);
        header.setElevation(dp(4));
        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.screen_title);
        title.setTextSize(28);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        titleBlock.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.screen_subtitle);
        subtitle.setTextSize(13);
        subtitle.setTextColor(0xFFE0F4EF);
        titleBlock.addView(subtitle);
        header.addView(titleBlock, new LinearLayout.LayoutParams(0, -2, 1));
        Button settings = new Button(this);
        settings.setText(R.string.settings_button);
        styleButton(settings, 0x33FFFFFF, Color.WHITE, 0x66FFFFFF);
        settings.setContentDescription(getString(R.string.settings_content_description));
        settings.setOnClickListener(view -> showSettings());
        header.addView(settings);
        root.addView(header);

        LinearLayout wakeControls = new LinearLayout(this);
        wakeControls.setGravity(Gravity.CENTER_VERTICAL);
        wakeControls.setPadding(dp(14), dp(8), dp(8), dp(8));
        wakeControls.setBackground(surfaceBackground());
        wakeControls.setElevation(dp(2));
        wakeIndicator = new TextView(this);
        wakeIndicator.setText("●");
        wakeIndicator.setTextSize(14);
        wakeIndicator.setTextColor(0xFF92A49C);
        wakeIndicator.setGravity(Gravity.CENTER);
        wakeControls.addView(wakeIndicator, new LinearLayout.LayoutParams(dp(28), dp(48)));
        wakeStatus = new TextView(this);
        wakeStatus.setText(R.string.wake_status_off);
        wakeStatus.setTextSize(13);
        wakeStatus.setTextColor(0xFF52625F);
        wakeStatus.setMaxLines(2);
        wakeStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        wakeControls.addView(wakeStatus, new LinearLayout.LayoutParams(0, -2, 1));
        wakeWordButton = new Button(this);
        wakeWordButton.setText(R.string.wake_button_start);
        styleButton(wakeWordButton, 0xFFE2F6EC, 0xFF11634F, 0x00FFFFFF);
        wakeWordButton.setOnClickListener(view -> toggleWakeWordService());
        wakeControls.addView(wakeWordButton);
        LinearLayout.LayoutParams wakeParams = new LinearLayout.LayoutParams(-1, -2);
        wakeParams.topMargin = dp(12);
        root.addView(wakeControls, wakeParams);
        TextView wakePrivacyNote = new TextView(this);
        wakePrivacyNote.setText(R.string.wake_privacy_note);
        wakePrivacyNote.setTextSize(11);
        wakePrivacyNote.setTextColor(0xFF71837C);
        wakePrivacyNote.setPadding(dp(6), dp(4), dp(6), 0);
        root.addView(wakePrivacyNote);
        root.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(12)));

        LinearLayout chatPanel = new LinearLayout(this);
        chatPanel.setOrientation(LinearLayout.VERTICAL);
        chatPanel.setPadding(dp(14), dp(12), dp(14), dp(10));
        chatPanel.setBackground(surfaceBackground());
        chatPanel.setElevation(dp(2));
        LinearLayout chatHeader = new LinearLayout(this);
        chatHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView conversationTitle = new TextView(this);
        conversationTitle.setText(R.string.conversation_title);
        conversationTitle.setTextSize(17);
        conversationTitle.setTextColor(0xFF17352F);
        conversationTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        chatHeader.addView(conversationTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView onlineLabel = new TextView(this);
        onlineLabel.setText(R.string.conversation_ready);
        onlineLabel.setTextSize(12);
        onlineLabel.setTextColor(0xFF148263);
        chatHeader.addView(onlineLabel);
        chatPanel.addView(chatHeader);

        conversation = new LinearLayout(this);
        conversation.setOrientation(LinearLayout.VERTICAL);
        conversation.setPadding(dp(4), dp(12), dp(4), dp(8));
        conversationScroll = new ScrollView(this);
        conversationScroll.setFillViewport(true);
        conversationScroll.setClipToPadding(false);
        conversationScroll.addView(conversation);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollParams.topMargin = dp(4);
        chatPanel.addView(conversationScroll, scrollParams);
        LinearLayout.LayoutParams chatParams = new LinearLayout.LayoutParams(-1, 0, 1);
        chatParams.bottomMargin = dp(12);
        root.addView(chatPanel, chatParams);
        addMessage("Çip", getString(R.string.welcome_message));

        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.VERTICAL);
        composer.setPadding(dp(8), dp(8), dp(8), dp(8));
        composer.setBackground(surfaceBackground());
        composer.setElevation(dp(2));
        LinearLayout messageRow = new LinearLayout(this);
        messageRow.setGravity(Gravity.CENTER_VERTICAL);
        messageInput = new EditText(this);
        messageInput.setHint(R.string.message_hint);
        messageInput.setMinLines(1);
        messageInput.setMaxLines(4);
        messageInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        messageInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        messageInput.setTextSize(16);
        messageInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        messageInput.setBackground(roundedBackground(0xFFF3F7F4, dp(18), 0x00FFFFFF));
        messageInput.setContentDescription(getString(R.string.message_content_description));
        messageRow.addView(messageInput, new LinearLayout.LayoutParams(0, -2, 1));
        sendButton = new Button(this);
        sendButton.setText(R.string.send_button);
        styleButton(sendButton, 0xFF16A078, Color.WHITE, 0x00FFFFFF);
        sendButton.setContentDescription(getString(R.string.send_content_description));
        sendButton.setOnClickListener(view -> sendText());
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(-2, -2);
        sendParams.leftMargin = dp(8);
        messageRow.addView(sendButton, sendParams);
        composer.addView(messageRow);
        messageInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendText();
                return true;
            }
            return false;
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        recordButton = new Button(this);
        recordButton.setText(R.string.record_button);
        styleButton(recordButton, 0xFFFFE8D8, 0xFF9C4B2D, 0x00FFFFFF);
        recordButton.setContentDescription(getString(R.string.record_content_description));
        recordButton.setOnClickListener(view -> toggleRecording());
        actions.addView(recordButton, new LinearLayout.LayoutParams(0, -2, 1));
        TextView privacyNote = new TextView(this);
        privacyNote.setText(R.string.microphone_permission_note);
        privacyNote.setTextSize(11);
        privacyNote.setTextColor(0xFF71837C);
        privacyNote.setGravity(Gravity.CENTER);
        actions.addView(privacyNote, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.topMargin = dp(6);
        composer.addView(actions, actionsParams);
        LinearLayout.LayoutParams composerParams = new LinearLayout.LayoutParams(-1, -2);
        composerParams.bottomMargin = dp(10);
        root.addView(composer, composerParams);

        HorizontalScrollView deviceActionScroll = new HorizontalScrollView(this);
        deviceActionScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout deviceActions = new LinearLayout(this);
        deviceActions.setGravity(Gravity.CENTER_VERTICAL);
        deviceActions.setOrientation(LinearLayout.HORIZONTAL);
        addDeviceActionButton(deviceActions, getString(R.string.device_action_file),
                DeviceCommandRouter.Action.OPEN_FILE);
        addDeviceActionButton(deviceActions, getString(R.string.device_action_contact),
                DeviceCommandRouter.Action.PICK_PHONE_TO_CALL);
        addDeviceActionButton(deviceActions, getString(R.string.device_action_sms),
                DeviceCommandRouter.Action.PICK_PHONE_FOR_SMS);
        addDeviceActionButton(deviceActions, getString(R.string.device_action_calendar),
                DeviceCommandRouter.Action.CREATE_CALENDAR_EVENT);
        deviceActionScroll.addView(deviceActions);
        root.addView(deviceActionScroll);

        LinearLayout transferCard = new LinearLayout(this);
        transferCard.setOrientation(LinearLayout.VERTICAL);
        transferCard.setPadding(dp(12), dp(8), dp(12), dp(8));
        transferCard.setBackground(surfaceBackground());
        transferCard.setElevation(dp(2));
        LinearLayout transferHeader = new LinearLayout(this);
        transferHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView transferTitle = new TextView(this);
        transferTitle.setText(R.string.transfer_title);
        transferTitle.setTextSize(14);
        transferTitle.setTextColor(0xFF17352F);
        transferTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        transferHeader.addView(transferTitle, new LinearLayout.LayoutParams(0, -2, 1));
        Button pairButton = new Button(this);
        pairButton.setText(R.string.transfer_pair_button);
        styleButton(pairButton, 0xFFEBF3FA, 0xFF275E7A, 0x00FFFFFF);
        pairButton.setContentDescription(getString(R.string.transfer_pair_content_description));
        pairButton.setOnClickListener(view -> showTransferPairingDialog());
        transferHeader.addView(pairButton);
        transferToggleButton = new Button(this);
        transferToggleButton.setText(R.string.transfer_details_show);
        styleButton(transferToggleButton, 0xFFF1F5F2, 0xFF52625F, 0x00FFFFFF);
        transferToggleButton.setOnClickListener(view -> setTransferExpanded(!transferExpanded));
        transferHeader.addView(transferToggleButton);
        transferCard.addView(transferHeader);

        transferStatus = new TextView(this);
        transferStatus.setText(R.string.transfer_status_unpaired);
        transferStatus.setTextSize(12);
        transferStatus.setTextColor(0xFF52625F);
        transferStatus.setPadding(dp(4), dp(2), dp(4), dp(4));
        transferCard.addView(transferStatus);

        transferContent = new LinearLayout(this);
        transferContent.setOrientation(LinearLayout.VERTICAL);
        LinearLayout transferButtons = new LinearLayout(this);
        transferButtons.setGravity(Gravity.CENTER);
        Button refreshTransfers = new Button(this);
        refreshTransfers.setText(R.string.transfer_refresh);
        styleButton(refreshTransfers, 0xFFE5F4ED, 0xFF11634F, 0x00FFFFFF);
        refreshTransfers.setOnClickListener(view -> pollTransferActions(true));
        transferButtons.addView(refreshTransfers, new LinearLayout.LayoutParams(0, -2, 1));
        Button revokePairing = new Button(this);
        revokePairing.setText(R.string.transfer_revoke);
        styleButton(revokePairing, 0xFFFFE8E3, 0xFF963F32, 0x00FFFFFF);
        revokePairing.setOnClickListener(view -> revokeTransferPairing());
        LinearLayout.LayoutParams revokeParams = new LinearLayout.LayoutParams(0, -2, 1);
        revokeParams.leftMargin = dp(8);
        transferButtons.addView(revokePairing, revokeParams);
        transferContent.addView(transferButtons);
        Button sendFileToPc = new Button(this);
        sendFileToPc.setText(R.string.transfer_send_file);
        styleButton(sendFileToPc, 0xFFEBF3FA, 0xFF275E7A, 0x00FFFFFF);
        sendFileToPc.setOnClickListener(view -> startPhoneUploadPicker(null));
        transferContent.addView(sendFileToPc);

        ScrollView transferActionScroll = new ScrollView(this);
        transferActionsContainer = new LinearLayout(this);
        transferActionsContainer.setOrientation(LinearLayout.VERTICAL);
        transferActionScroll.addView(transferActionsContainer);
        transferContent.addView(transferActionScroll, new LinearLayout.LayoutParams(-1, dp(112)));
        transferCard.addView(transferContent);
        LinearLayout.LayoutParams transferParams = new LinearLayout.LayoutParams(-1, -2);
        transferParams.topMargin = dp(10);
        root.addView(transferCard, transferParams);
        setTransferExpanded(false);
        setContentView(root);
    }

    private void addDeviceActionButton(LinearLayout row, String label, DeviceCommandRouter.Action action) {
        Button button = new Button(this);
        button.setText(label);
        styleButton(button, 0xFFFFFFFF, 0xFF31574D, 0xFFDCE8E0);
        button.setTextSize(12);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.rightMargin = dp(6);
        button.setOnClickListener(view -> launchDeviceAction(action, null));
        row.addView(button, params);
    }

    private void setTransferExpanded(boolean expanded) {
        transferExpanded = expanded;
        if (transferContent != null) {
            transferContent.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        if (transferToggleButton != null) {
            transferToggleButton.setText(expanded
                    ? R.string.transfer_details_hide : R.string.transfer_details_show);
            transferToggleButton.setContentDescription(getString(expanded
                    ? R.string.transfer_details_hide_content_description
                    : R.string.transfer_details_show_content_description));
        }
    }

    private GradientDrawable roundedBackground(int color, int radius, int strokeColor) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(radius);
        if (Color.alpha(strokeColor) != 0) {
            background.setStroke(dp(1), strokeColor);
        }
        return background;
    }

    private GradientDrawable surfaceBackground() {
        return roundedBackground(Color.WHITE, dp(22), 0xFFE7EEE9);
    }

    private void styleButton(Button button, int background, int foreground, int stroke) {
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(foreground);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable shape = roundedBackground(background, dp(16), stroke);
        GradientDrawable rippleMask = roundedBackground(Color.WHITE, dp(16), 0x00FFFFFF);
        int rippleColor = (foreground & 0x00FFFFFF) | 0x33000000;
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor),
                shape, rippleMask));
        button.setElevation(0);
    }

    private void showSettings() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);

        EditText urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setHint("Sunucu adresi (https://...)");
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setText(preferences.getString(KEY_SERVER_URL, ""));
        form.addView(urlInput);

        EditText keyInput = new EditText(this);
        keyInput.setSingleLine(true);
        keyInput.setHint("ASISTAN_API_KEY");
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        String currentApiKey;
        try {
            currentApiKey = readApiKey();
        } catch (IllegalStateException error) {
            currentApiKey = "";
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
        keyInput.setText(currentApiKey);
        form.addView(keyInput);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Sunucu ayarları")
                .setMessage("Render sunucu adresini ve API anahtarını gir. Anahtar bu cihazda şifreli saklanır.")
                .setView(form)
                .setPositiveButton("Kaydet", null)
                .setNeutralButton("Anahtarı sil", null)
                .setNegativeButton("İptal", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String serverUrl = urlInput.getText().toString().trim();
                String apiKey = keyInput.getText().toString().trim();
                Uri uri = Uri.parse(serverUrl);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                    urlInput.setError("https:// ile başlayan geçerli bir adres gir.");
                    return;
                }
                try {
                    if (apiKey.isEmpty() && readApiKey().isEmpty()) {
                        keyInput.setError("Sunucu API anahtarını gir.");
                        return;
                    }
                } catch (IllegalStateException error) {
                    keyInput.setError(error.getMessage());
                    return;
                }
                try {
                    if (!apiKey.isEmpty()) {
                        saveApiKey(apiKey);
                    }
                    preferences.edit().putString(KEY_SERVER_URL, trimTrailingSlash(serverUrl)).apply();
                    dialog.dismiss();
                    Toast.makeText(this, "Ayarlar kaydedildi.", Toast.LENGTH_SHORT).show();
                } catch (Exception error) {
                    Toast.makeText(this, "Anahtar güvenli kaydedilemedi: " + error.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                try {
                    clearApiKey();
                    keyInput.setText("");
                    Toast.makeText(this, "API anahtarı cihazdan silindi.", Toast.LENGTH_SHORT).show();
                } catch (Exception error) {
                    Toast.makeText(this, "Anahtar silinemedi: " + error.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        });
        dialog.show();
    }

    private void showTransferPairingDialog() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            showTransferError(new IllegalStateException(
                    "Güvenli aktarım biyometrik imza için Android 9 veya üzerini gerektirir."));
            return;
        }
        EditText codeInput = new EditText(this);
        codeInput.setSingleLine(true);
        codeInput.setHint("Bilgisayardaki 10 haneli eşleşme kodu");
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout form = new LinearLayout(this);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        form.addView(codeInput, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Bilgisayarı eşleştir")
                .setMessage("Eşleşme kodunu yalnızca güvendiğin bilgisayarda oluştur.")
                .setView(form)
                .setPositiveButton("Eşleştir", null)
                .setNegativeButton("İptal", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String code = codeInput.getText().toString().trim();
                    if (!code.matches("[0-9]{10}")) {
                        codeInput.setError("10 haneli sayısal kodu gir.");
                        return;
                    }
                    dialog.dismiss();
                    confirmTransferPairReplacement(code);
                }));
        dialog.show();
    }

    private void confirmTransferPairReplacement(String pairingCode) {
        TransferCredentials oldCredentials;
        try {
            oldCredentials = transferCredentialStore.read();
        } catch (IllegalStateException error) {
            showTransferError(error);
            return;
        }
        if (oldCredentials == null) {
            claimTransferPairing(pairingCode, null);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Eşleşmeyi değiştir?")
                .setMessage("Mevcut bilgisayar eşleşmesi kaldırılacak ve yeni kodla eşleşilecek.")
                .setPositiveButton("Devam et", (dialog, which) -> claimTransferPairing(pairingCode, oldCredentials))
                .setNegativeButton("İptal", null)
                .show();
    }

    private void claimTransferPairing(String pairingCode, TransferCredentials oldCredentials) {
        String serverUrl = trimTrailingSlash(preferences.getString(KEY_SERVER_URL, ""));
        if (serverUrl.isEmpty()) {
            showSettings();
            return;
        }
        updateTransferStatus("Bilgisayar eşleştiriliyor...");
        network.execute(() -> {
            try {
                if (oldCredentials != null) {
                    new TransferApi(oldCredentials.serverUrl).revoke(oldCredentials);
                    transferCredentialStore.clear();
                }
                AndroidBiometricApproval signer = (AndroidBiometricApproval) transferApproval;
                TransferApi.PairingResult result =
                        new TransferApi(serverUrl).claim(pairingCode, signer.publicKeyPem());
                transferCredentialStore.save(serverUrl, result.pairingId, result.deviceToken);
                runOnUiThread(() -> {
                    long days = Math.max(1L, result.expiresInSeconds / 86_400L);
                    updateTransferStatus("Bilgisayar eşleşti. Eşleşme süresi: yaklaşık " + days + " gün.");
                    pollTransferActions(true);
                });
            } catch (Exception error) {
                runOnUiThread(() -> showTransferError(error));
            }
        });
    }

    private void revokeTransferPairing() {
        TransferCredentials credentials;
        try {
            credentials = transferCredentialStore.read();
        } catch (IllegalStateException error) {
            showTransferError(error);
            return;
        }
        if (credentials == null) {
            updateTransferStatus("Kaldırılacak eşleşme yok.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Eşleşmeyi kaldır?")
                .setMessage("Bu cihazın bilgisayarla dosya aktarım yetkisi iptal edilecek.")
                .setPositiveButton("Kaldır", (dialog, which) -> network.execute(() -> {
                    try {
                        new TransferApi(credentials.serverUrl).revoke(credentials);
                        transferCredentialStore.clear();
                        runOnUiThread(() -> {
                            transferActionsContainer.removeAllViews();
                            updateTransferStatus("Bilgisayar eşleşmesi kaldırıldı.");
                        });
                    } catch (Exception error) {
                        runOnUiThread(() -> showTransferError(error));
                    }
                }))
                .setNegativeButton("İptal", null)
                .show();
    }

    private void pollTransferActions(boolean showStatus) {
        if (transferPollInProgress) {
            return;
        }
        TransferCredentials credentials;
        try {
            credentials = transferCredentialStore.read();
        } catch (IllegalStateException error) {
            showTransferError(error);
            return;
        }
        if (credentials == null) {
            transferActionsContainer.removeAllViews();
            updateTransferStatus("Bilgisayar eşleşmesi yok.");
            return;
        }
        String configuredServer = trimTrailingSlash(preferences.getString(KEY_SERVER_URL, ""));
        if (!credentials.serverUrl.equals(configuredServer)) {
            updateTransferStatus("Eşleşme başka bir sunucuya kayıtlı. Eski eşleşmeyi kaldırıp yeniden eşleştir.");
            return;
        }
        transferPollInProgress = true;
        if (showStatus) {
            updateTransferStatus("Aktarım istekleri denetleniyor...");
        }
        network.execute(() -> {
            try {
                List<TransferAction> actions =
                        new TransferApi(credentials.serverUrl).listActions(credentials);
                runOnUiThread(() -> renderTransferActions(actions));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (showStatus) {
                        showTransferError(error);
                    } else {
                        updateTransferStatus(error.getMessage() == null
                                ? "Aktarım istekleri denetlenemedi." : error.getMessage());
                    }
                });
            } finally {
                runOnUiThread(() -> transferPollInProgress = false);
            }
        });
    }

    private void renderTransferActions(List<TransferAction> actions) {
        transferActionsContainer.removeAllViews();
        updateTransferStatus(actions.isEmpty()
                ? "Eşleşme etkin; bekleyen aktarım isteği yok."
                : actions.size() + " aktarım isteği.");
        for (TransferAction action : actions) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(10), dp(8), dp(10), dp(6));
            card.setBackground(roundedBackground(0xFFF2F7F3, dp(18), 0xFFDCE8E0));
            TextView detail = new TextView(this);
            String direction = action.direction.equals("pc_to_phone")
                    ? "Bilgisayardan telefona" : "Telefondan bilgisayara";
            detail.setText(getString(R.string.transfer_action_details, direction, action.filename,
                    formatFileSize(action.size), transferStateLabel(action.state)));
            detail.setTextColor(0xFF31574D);
            detail.setTextSize(13);
            card.addView(detail);
            LinearLayout controls = new LinearLayout(this);
            controls.setGravity(Gravity.END);
            if (action.awaitsApproval() && action.direction.equals("pc_to_phone")) {
                Button download = new Button(this);
                download.setText(R.string.transfer_action_review);
                styleButton(download, 0xFF16A078, Color.WHITE, 0x00FFFFFF);
                download.setOnClickListener(view -> confirmDownloadAction(action));
                controls.addView(download);
            } else if (action.direction.equals("pc_to_phone") && action.state.equals("ready")) {
                Button download = new Button(this);
                download.setText(R.string.transfer_action_download);
                styleButton(download, 0xFFE5F4ED, 0xFF11634F, 0x00FFFFFF);
                download.setOnClickListener(view -> downloadReadyAction(action));
                controls.addView(download);
            } else if (action.direction.equals("phone_to_pc")
                    && (action.awaitsApproval() || action.state.equals("approved")
                    || action.state.equals("receiving"))) {
                Button upload = new Button(this);
                upload.setText(action.awaitsApproval() ? "Dosyayı seç ve onayla" : "Yüklemeyi sürdür");
                styleButton(upload, 0xFFEBF3FA, 0xFF275E7A, 0x00FFFFFF);
                upload.setOnClickListener(view -> startPhoneUploadPicker(action));
                controls.addView(upload);
            }
            if (!action.state.equals("completed") && !action.state.equals("cancelled")) {
                Button cancel = new Button(this);
                cancel.setText(R.string.transfer_action_cancel);
                styleButton(cancel, 0xFFFFE8E3, 0xFF963F32, 0x00FFFFFF);
                cancel.setOnClickListener(view -> confirmCancelTransfer(action));
                controls.addView(cancel);
            }
            card.addView(controls);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
            cardParams.bottomMargin = dp(6);
            transferActionsContainer.addView(card, cardParams);
        }
    }

    private void confirmDownloadAction(TransferAction action) {
        if (!action.awaitsApproval() || !"pc_to_phone".equals(action.direction)) {
            showTransferError(new IllegalStateException("Bu aktarım isteği onay beklemiyor."));
            return;
        }
        String prompt = "Bilgisayardan telefona dosya alınacak.\n\nDosya: " + action.filename
                + "\nBoyut: " + formatFileSize(action.size)
                + "\nSHA-256: " + action.sha256
                + "\n\nDevam etmeden önce Android biyometrik onayı gerekecek.";
        new AlertDialog.Builder(this)
                .setTitle("Dosya aktarımını incele")
                .setMessage(prompt)
                .setPositiveButton("Biyometrik onayla", (dialog, which) -> approveAndDownload(action))
                .setNegativeButton("Vazgeç", null)
                .show();
    }

    private void startPhoneUploadPicker(TransferAction existingAction) {
        if (transferUploadBusy) {
            updateTransferStatus("Başka bir dosya aktarımı zaten sürüyor.");
            return;
        }
        if (transferUploadFile != null && transferUploadFile.exists()
                && !transferUploadFile.delete()) {
            showTransferError(new IOException("Önceki geçici dosya silinemedi."));
            return;
        }
        transferUploadBusy = true;
        transferActionAwaitingUpload = existingAction;
        transferUploadFile = null;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        try {
            startActivityForResult(picker, REQUEST_TRANSFER_UPLOAD);
        } catch (Exception error) {
            transferUploadBusy = false;
            transferActionAwaitingUpload = null;
            showTransferError(error);
        }
    }

    private void preparePhoneUpload(Uri sourceUri) {
        if (sourceUri == null) {
            transferUploadBusy = false;
            transferActionAwaitingUpload = null;
            return;
        }
        final TransferAction existing = transferActionAwaitingUpload;
        final String filename;
        try {
            filename = transferBasename(sourceUri);
        } catch (Exception error) {
            transferUploadBusy = false;
            transferActionAwaitingUpload = null;
            showTransferError(error);
            return;
        }
        File selectedBytes = new File(getCacheDir(), "cip-transfer-upload-" + UUID.randomUUID() + ".part");
        updateTransferStatus("Seçilen dosya güvenli biçimde hazırlanıyor...");
        network.execute(() -> {
            try {
                copyTransferSource(sourceUri, selectedBytes);
                long size = selectedBytes.length();
                if (size < 1) {
                    throw new IOException("Boş dosyalar aktarılamaz.");
                }
                String sha256 = TransferApi.sha256(selectedBytes);
                if (existing != null && (!filename.equals(existing.filename)
                        || size != existing.size || !sha256.equals(existing.sha256))) {
                    throw new IOException("Seçilen dosyanın adı, boyutu veya SHA-256 özeti aktarım isteğiyle eşleşmiyor.");
                }
                if (existing != null) {
                    runOnUiThread(() -> {
                        transferUploadFile = selectedBytes;
                        if (existing.awaitsApproval()) {
                            confirmUploadAction(existing, selectedBytes);
                        } else {
                            uploadPreviouslyApprovedAction(existing, selectedBytes);
                        }
                    });
                    return;
                }
                TransferCredentials credentials = requireCurrentTransferCredentials(null);
                TransferAction action = new TransferApi(credentials.serverUrl)
                        .createPhoneUploadAction(credentials, filename, size, sha256);
                runOnUiThread(() -> {
                    transferActionAwaitingUpload = action;
                    transferUploadFile = selectedBytes;
                    confirmUploadAction(action, selectedBytes);
                });
            } catch (Exception error) {
                if (selectedBytes.exists() && !selectedBytes.delete()) {
                    error.addSuppressed(new IOException("Geçici dosya silinemedi."));
                }
                runOnUiThread(() -> {
                    transferUploadBusy = false;
                    transferActionAwaitingUpload = null;
                    showTransferError(error);
                });
            }
        });
    }

    private String transferBasename(Uri uri) throws Exception {
        String displayName = selectedFileName(uri);
        if (displayName == null || displayName.trim().isEmpty()) {
            throw new IOException("Seçilen dosyanın adı okunamadı.");
        }
        String basename = displayName.replace('\\', '/');
        int separator = basename.lastIndexOf('/');
        if (separator >= 0) {
            basename = basename.substring(separator + 1);
        }
        if (basename.trim().isEmpty() || basename.equals(".") || basename.equals("..")
                || basename.length() > 255 || containsControlCharacter(basename)) {
            throw new IOException("Seçilen dosyanın adı geçersiz.");
        }
        return basename;
    }

    private boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private void copyTransferSource(Uri source, File destination) throws Exception {
        long copied = 0;
        try (InputStream input = getContentResolver().openInputStream(source);
             OutputStream output = new FileOutputStream(destination)) {
            if (input == null) {
                throw new IOException("Seçilen dosya okunamadı.");
            }
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                copied += count;
                if (copied > TransferAction.MAX_FILE_SIZE) {
                    throw new IOException("Dosyalar en fazla 20 MiB olabilir.");
                }
                output.write(buffer, 0, count);
            }
            output.flush();
        } catch (Exception error) {
            if (destination.exists() && !destination.delete()) {
                error.addSuppressed(new IOException("Geçici dosya silinemedi."));
            }
            throw error;
        }
    }

    private void confirmUploadAction(TransferAction action, File selectedBytes) {
        if (!action.awaitsApproval() || !"phone_to_pc".equals(action.direction)) {
            transferUploadBusy = false;
            showTransferError(new IllegalStateException("Bu aktarım isteği biyometrik onay beklemiyor."));
            return;
        }
        String prompt = "Seçilen dosya bilgisayara gönderilecek.\n\nDosya: " + action.filename
                + "\nBoyut: " + formatFileSize(action.size)
                + "\nSHA-256: " + action.sha256
                + "\n\nGönderimden önce bu işlem için biyometrik onay gerekecek.";
        new AlertDialog.Builder(this)
                .setTitle("Dosya aktarımını incele")
                .setMessage(prompt)
                .setPositiveButton("Biyometrik onayla", (dialog, which) ->
                        approveAndUpload(action, selectedBytes))
                .setNegativeButton("Vazgeç", (dialog, which) -> deletePendingTransferUpload())
                .show();
    }

    private void approveAndUpload(TransferAction action, File source) {
        transferApproval.signChallenge(this, action.challenge, (signature, error) -> {
            if (signature == null) {
                transferUploadBusy = false;
                showTransferError(new IllegalStateException(error == null
                        ? "Biyometrik onay tamamlanamadı." : error));
                return;
            }
            final TransferCredentials credentials;
            try {
                credentials = requireCurrentTransferCredentials(action.pairingId);
            } catch (Exception credentialError) {
                transferUploadBusy = false;
                showTransferError(credentialError);
                return;
            }
            updateTransferStatus("Biyometrik onay gönderiliyor...");
            network.execute(() -> {
                try {
                    TransferApi api = new TransferApi(credentials.serverUrl);
                    api.approve(credentials, action, signature);
                    updateTransferStatusFromWorker("Onaylandı; dosya bilgisayara yükleniyor...");
                    api.upload(credentials, action, source);
                    runOnUiThread(() -> {
                        transferUploadBusy = false;
                        deletePendingTransferUpload();
                        updateTransferStatus("Dosya bilgisayara hazırlandı.");
                        pollTransferActions(true);
                    });
                } catch (Exception transferError) {
                    runOnUiThread(() -> {
                        transferUploadBusy = false;
                        showTransferError(transferError);
                        pollTransferActions(false);
                    });
                }
            });
        });
    }

    private void uploadPreviouslyApprovedAction(TransferAction action, File source) {
        if (!action.state.equals("approved") && !action.state.equals("receiving")) {
            transferUploadBusy = false;
            showTransferError(new IllegalStateException("Aktarım sunucuda gönderim için onaylanmamış."));
            return;
        }
        final TransferCredentials credentials;
        try {
            credentials = requireCurrentTransferCredentials(action.pairingId);
        } catch (Exception error) {
            transferUploadBusy = false;
            showTransferError(error);
            return;
        }
        updateTransferStatus("Onaylanmış dosya bilgisayara yükleniyor...");
        network.execute(() -> {
            try {
                new TransferApi(credentials.serverUrl).upload(credentials, action, source);
                runOnUiThread(() -> {
                    transferUploadBusy = false;
                    deletePendingTransferUpload();
                    updateTransferStatus("Dosya bilgisayara hazırlandı.");
                    pollTransferActions(true);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    transferUploadBusy = false;
                    showTransferError(error);
                    pollTransferActions(false);
                });
            }
        });
    }

    private TransferCredentials requireCurrentTransferCredentials(String expectedPairingId) throws IOException {
        TransferCredentials credentials = transferCredentialStore.read();
        String configuredServer = trimTrailingSlash(preferences.getString(KEY_SERVER_URL, ""));
        if (credentials == null || !credentials.serverUrl.equals(configuredServer)) {
            throw new IOException("Geçerli bilgisayar eşleşmesi yok veya sunucu değişmiş.");
        }
        if (expectedPairingId != null && !credentials.pairingId.equals(expectedPairingId)) {
            throw new IOException("Aktarım isteği bu eşleşmeye ait değil.");
        }
        return credentials;
    }

    private void deletePendingTransferUpload() {
        if (transferUploadFile != null && transferUploadFile.exists()
                && !transferUploadFile.delete()) {
            Toast.makeText(this, "Geçici aktarım dosyası silinemedi.", Toast.LENGTH_LONG).show();
        }
        transferUploadFile = null;
        transferActionAwaitingUpload = null;
        transferUploadBusy = false;
    }

    private void updateTransferStatusFromWorker(String status) {
        runOnUiThread(() -> updateTransferStatus(status));
    }

    private void approveAndDownload(TransferAction action) {
        if (!action.awaitsApproval() || action.challenge == null) {
            showTransferError(new IllegalStateException("Geçerli aktarım onay bilgisi yok."));
            return;
        }
        updateTransferStatus("Biyometrik onay bekleniyor...");
        transferApproval.signChallenge(this, action.challenge, (signature, error) -> {
            if (signature == null) {
                showTransferError(new IllegalStateException(error == null
                        ? "Biyometrik onay tamamlanamadı." : error));
                return;
            }
            TransferCredentials credentials;
            try {
                credentials = transferCredentialStore.read();
            } catch (IllegalStateException credentialError) {
                showTransferError(credentialError);
                return;
            }
            if (credentials == null || !credentials.pairingId.equals(action.pairingId)
                    || !credentials.serverUrl.equals(trimTrailingSlash(
                    preferences.getString(KEY_SERVER_URL, "")))) {
                showTransferError(new IllegalStateException("Aktarım eşleşmesi artık geçerli değil."));
                return;
            }
            updateTransferStatus("İstek imzalanıyor ve dosya doğrulanıyor...");
            network.execute(() -> {
                try {
                    TransferApi api = new TransferApi(credentials.serverUrl);
                    api.approve(credentials, action, signature);
                    List<TransferAction> actions = api.listActions(credentials);
                    TransferAction latest = findTransferAction(actions, action.actionId);
                    if (latest == null || !"ready".equals(latest.state)) {
                        runOnUiThread(() -> {
                            renderTransferActions(actions);
                            updateTransferStatus("Onaylandı. Bilgisayar dosyayı hazırladığında indirebilirsin.");
                        });
                        return;
                    }
                    downloadTransferFile(api, credentials, latest);
                } catch (Exception transferError) {
                    runOnUiThread(() -> {
                        showTransferError(transferError);
                        pollTransferActions(false);
                    });
                }
            });
        });
    }

    private void downloadReadyAction(TransferAction action) {
        if (!"pc_to_phone".equals(action.direction) || !"ready".equals(action.state)) {
            showTransferError(new IllegalStateException("Dosya indirmeye hazır değil."));
            return;
        }
        final TransferCredentials credentials;
        try {
            credentials = requireCurrentTransferCredentials(action.pairingId);
        } catch (Exception error) {
            showTransferError(error);
            return;
        }
        updateTransferStatus("Hazır dosya indiriliyor ve doğrulanıyor...");
        network.execute(() -> {
            try {
                downloadTransferFile(new TransferApi(credentials.serverUrl), credentials, action);
            } catch (Exception error) {
                runOnUiThread(() -> {
                    showTransferError(error);
                    pollTransferActions(false);
                });
            }
        });
    }

    private void downloadTransferFile(TransferApi api, TransferCredentials credentials,
                                      TransferAction action) throws Exception {
        File downloaded = new File(getCacheDir(), "cip-transfer-" + UUID.randomUUID() + ".part");
        try {
            api.download(credentials, action, downloaded);
            runOnUiThread(() -> requestDownloadDestination(action, downloaded));
        } catch (Exception error) {
            if (downloaded.exists() && !downloaded.delete()) {
                error.addSuppressed(new IOException("Geçici aktarım dosyası silinemedi."));
            }
            throw error;
        }
    }

    private TransferAction findTransferAction(List<TransferAction> actions, String actionId) {
        for (TransferAction action : actions) {
            if (action.actionId.equals(actionId)) {
                return action;
            }
        }
        return null;
    }

    private void requestDownloadDestination(TransferAction action, File downloaded) {
        transferActionAwaitingSave = action;
        transferDownloadFile = downloaded;
        transferStatus.setOnClickListener(null);
        Intent createDocument = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        createDocument.addCategory(Intent.CATEGORY_OPENABLE);
        createDocument.setType("application/octet-stream");
        createDocument.putExtra(Intent.EXTRA_TITLE, safeTransferFilename(action.filename));
        try {
            startActivityForResult(createDocument, REQUEST_TRANSFER_SAVE);
            updateTransferStatus("Dosya doğrulandı. Kaydedilecek konumu seç.");
        } catch (Exception error) {
            deletePendingTransferDownload();
            showTransferError(error);
        }
    }

    private void confirmCancelTransfer(TransferAction action) {
        new AlertDialog.Builder(this)
                .setTitle("Aktarımı iptal et?")
                .setMessage(action.filename + " aktarım isteği iptal edilecek.")
                .setPositiveButton("İptal et", (dialog, which) -> {
                    TransferCredentials credentials;
                    try {
                        credentials = transferCredentialStore.read();
                    } catch (IllegalStateException error) {
                        showTransferError(error);
                        return;
                    }
                    if (credentials == null || !credentials.pairingId.equals(action.pairingId)) {
                        showTransferError(new IllegalStateException("Aktarım eşleşmesi artık geçerli değil."));
                        return;
                    }
                    network.execute(() -> {
                        try {
                            new TransferApi(credentials.serverUrl).cancel(credentials, action);
                            runOnUiThread(() -> pollTransferActions(true));
                        } catch (Exception error) {
                            runOnUiThread(() -> showTransferError(error));
                        }
                    });
                })
                .setNegativeButton("Vazgeç", null)
                .show();
    }

    private void copyVerifiedDownload(Uri destination) throws Exception {
        if (destination == null || transferDownloadFile == null || transferActionAwaitingSave == null) {
            throw new IllegalStateException("Kaydedilecek doğrulanmış aktarım dosyası yok.");
        }
        if (!TransferApi.sha256(transferDownloadFile).equals(transferActionAwaitingSave.sha256)
                || transferDownloadFile.length() != transferActionAwaitingSave.size) {
            throw new IOException("Kaydetmeden önce aktarım dosyasının doğrulaması başarısız oldu.");
        }
        try (InputStream input = new FileInputStream(transferDownloadFile);
             OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
            if (output == null) {
                throw new IOException("Seçilen konumda dosya oluşturulamadı.");
            }
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
        }
    }

    private void deletePendingTransferDownload() {
        if (transferDownloadFile != null && transferDownloadFile.exists()
                && !transferDownloadFile.delete()) {
            Toast.makeText(this, "Geçici aktarım dosyası silinemedi.", Toast.LENGTH_LONG).show();
        }
        transferDownloadFile = null;
        transferActionAwaitingSave = null;
    }

    private void updateTransferStatus(String status) {
        if (transferStatus != null) {
            transferStatus.setText(status);
            transferStatus.setOnClickListener(null);
        }
    }

    private void showTransferError(Exception error) {
        String message = error.getMessage() == null ? "Aktarım işlemi başarısız oldu." : error.getMessage();
        updateTransferStatus(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private String transferStateLabel(String state) {
        switch (state) {
            case "awaiting_approval": return "Biyometrik onay bekliyor";
            case "approved": return "Onaylandı";
            case "receiving": return "Alınıyor";
            case "ready": return "Hazır";
            case "sending": return "Gönderiliyor";
            case "completed": return "Tamamlandı";
            case "cancelled": return "İptal edildi";
            default: return state;
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " bayt";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    private String safeTransferFilename(String filename) {
        String safe = filename.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) {
            return "cip-transfer.bin";
        }
        if (safe.length() > 200) {
            safe = safe.substring(0, 200);
        }
        return safe;
    }

    private void sendText() {
        sendText(messageInput.getText().toString().trim());
    }

    private void sendText(String text) {
        text = text.trim();
        if (text.isEmpty()) {
            return;
        }
        if (!ensureConfigured()) {
            resumeWakeWordServiceIfActive();
            return;
        }
        messageInput.setText("");
        addMessage("Sen", text);
        pauseWakeWordService();
        setBusy(true);
        final String command = text;
        network.execute(() -> {
            try {
                String answer = postForm("/komut", "text", command, "session", sessionId());
                runOnUiThread(() -> showAnswer(answer));
            } catch (Exception error) {
                runOnUiThread(() -> showError(error));
            } finally {
                runOnUiThread(() -> setBusy(false));
            }
        });
    }

    private void toggleRecording() {
        if (recording) {
            finishRecording();
            return;
        }
        if (!ensureConfigured()) {
            return;
        }
        pauseWakeWordService();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
            return;
        }
        startRecording();
    }

    private void toggleWakeWordService() {
        if (WakeWordService.isRunning()) {
            sendWakeServiceAction(WakeWordService.ACTION_STOP, false);
            updateWakeStatus("Sürekli dinleme kapatıldı.");
            return;
        }
        startWakeWordAfterPermissions();
    }

    private void startWakeWordAfterPermissions() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_WAKE_MICROPHONE);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        Intent service = new Intent(this, WakeWordService.class).setAction(WakeWordService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
            updateWakeStatus("Cihaz-üstü dinleme başlatılıyor...");
        } catch (RuntimeException error) {
            updateWakeStatus("Dinleme başlatılamadı: " + error.getMessage());
        }
    }

    private void sendWakeServiceAction(String action, boolean starting) {
        Intent service = new Intent(this, WakeWordService.class).setAction(action);
        try {
            if (starting && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
        } catch (RuntimeException error) {
            updateWakeStatus("Dinleme durumu değiştirilemedi: " + error.getMessage());
        }
    }

    private void pauseWakeWordService() {
        if (WakeWordService.isRunning()) {
            sendWakeServiceAction(WakeWordService.ACTION_PAUSE, false);
        }
    }

    private void resumeWakeWordServiceIfActive() {
        if (WakeWordService.isRunning()) {
            sendWakeServiceAction(WakeWordService.ACTION_RESUME, false);
        }
    }

    private void updateWakeStatus(String status) {
        if (wakeStatus != null) {
            wakeStatus.setText(status == null ? "Dinleme durumu bilinmiyor." : status);
        }
        boolean active = WakeWordService.isRunning();
        if (wakeIndicator != null) {
            wakeIndicator.setTextColor(active ? 0xFF16A078
                    : status != null && (status.contains("başlat") || status.contains("algılandı"))
                    ? 0xFFF0A43A : 0xFF92A49C);
        }
        if (wakeWordButton != null) {
            wakeWordButton.setText(active ? R.string.wake_button_stop : R.string.wake_button_start);
            styleButton(wakeWordButton, active ? 0xFFFFE8D8 : 0xFFE2F6EC,
                    active ? 0xFF9C4B2D : 0xFF11634F, 0x00FFFFFF);
        }
    }

    private void handlePendingWakeCommand(String command) {
        preferences.edit().remove(WakeWordService.PENDING_COMMAND).apply();
        updateWakeStatus("Uyandırma sözcüğü algılandı.");
        if (command != null && !command.trim().isEmpty()) {
            handleAssistantCommand(command);
        }
    }

    private void handleAssistantCommand(String command) {
        DeviceCommandRouter.Result result = DeviceCommandRouter.route(command);
        if (result != null) {
            addMessage("Sen", command);
            pauseWakeWordService();
            launchDeviceAction(result.action, result.phoneNumber);
            return;
        }
        sendText(command);
    }

    private void launchDeviceAction(DeviceCommandRouter.Action action, String phoneNumber) {
        pauseWakeWordService();
        try {
            switch (action) {
                case OPEN_FILE:
                    Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    picker.addCategory(Intent.CATEGORY_OPENABLE);
                    picker.setType("*/*");
                    startActivityForResult(picker, REQUEST_OPEN_FILE);
                    break;
                case CREATE_CALENDAR_EVENT:
                    Intent event = new Intent(Intent.ACTION_INSERT);
                    event.setData(CalendarContract.Events.CONTENT_URI);
                    event.putExtra(CalendarContract.Events.TITLE, "Çip ile etkinlik");
                    event.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,
                            System.currentTimeMillis() + 60 * 60 * 1000L);
                    event.putExtra(CalendarContract.EXTRA_EVENT_END_TIME,
                            System.currentTimeMillis() + 2 * 60 * 60 * 1000L);
                    startActivityForResult(event, REQUEST_NATIVE_ACTIVITY);
                    break;
                case PICK_PHONE_TO_CALL:
                    startActivityForResult(new Intent(Intent.ACTION_PICK,
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQUEST_PICK_CALL);
                    break;
                case PICK_PHONE_FOR_SMS:
                    startActivityForResult(new Intent(Intent.ACTION_PICK,
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQUEST_PICK_SMS);
                    break;
                case DIAL_NUMBER:
                    startActivityForResult(new Intent(Intent.ACTION_DIAL,
                            Uri.fromParts("tel", phoneNumber, null)), REQUEST_NATIVE_ACTIVITY);
                    break;
                case COMPOSE_SMS:
                    startActivityForResult(new Intent(Intent.ACTION_SENDTO,
                            Uri.fromParts("smsto", phoneNumber, null)), REQUEST_NATIVE_ACTIVITY);
                    break;
            }
        } catch (Exception error) {
            showError(error);
            resumeWakeWordServiceIfActive();
        }
    }

    private void handlePickedPhone(Uri contactUri, boolean composeSms) {
        if (contactUri == null) {
            resumeWakeWordServiceIfActive();
            return;
        }
        String number = null;
        try (Cursor cursor = getContentResolver().query(contactUri,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                number = cursor.getString(0);
            }
        } catch (Exception error) {
            showError(error);
        }
        if (number == null || number.trim().isEmpty()) {
            addMessage("Hata", "Seçilen kişide kullanılabilir bir telefon numarası yok.");
            resumeWakeWordServiceIfActive();
            return;
        }
        launchDeviceAction(composeSms ? DeviceCommandRouter.Action.COMPOSE_SMS
                : DeviceCommandRouter.Action.DIAL_NUMBER, number);
    }

    private String selectedFileName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception error) {
            return uri.getLastPathSegment();
        }
        return uri.getLastPathSegment();
    }

    private void startRecording() {
        try {
            recordingFile = new File(getCacheDir(), "cip-" + System.currentTimeMillis() + ".m4a");
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(16000);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setMaxFileSize(20_000_000L);
            recorder.setMaxDuration(120_000);
            recorder.setOutputFile(recordingFile.getAbsolutePath());
            recorder.setOnInfoListener((mediaRecorder, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                        || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    runOnUiThread(this::finishRecording);
                }
            });
            recorder.prepare();
            recorder.start();
            recording = true;
            recordButton.setText(R.string.record_stop_button);
            recordButton.setContentDescription(getString(R.string.record_stop_content_description));
            styleButton(recordButton, 0xFFFF6B55, Color.WHITE, 0x00FFFFFF);
            animateTap(recordButton);
            Toast.makeText(this, "Dinliyorum...", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            releaseRecorder();
            Toast.makeText(this, "Kayıt başlatılamadı: " + error.getMessage(), Toast.LENGTH_LONG).show();
            resumeWakeWordServiceIfActive();
        }
    }

    private void finishRecording() {
        if (!recording) {
            return;
        }
        recording = false;
        recordButton.setText(R.string.record_button);
        recordButton.setContentDescription(getString(R.string.record_content_description));
        styleButton(recordButton, 0xFFFFE8D8, 0xFF9C4B2D, 0x00FFFFFF);
        try {
            recorder.stop();
            releaseRecorder();
            if (recordingFile == null || !recordingFile.isFile() || recordingFile.length() < 1000) {
                Toast.makeText(this, "Kayıt çok kısa; tekrar deneyebilirsin.", Toast.LENGTH_SHORT).show();
                deleteRecording();
                resumeWakeWordServiceIfActive();
                return;
            }
            uploadRecording(recordingFile);
        } catch (RuntimeException error) {
            releaseRecorder();
            deleteRecording();
            Toast.makeText(this, "Kayıt tamamlanamadı; biraz daha uzun konuşup tekrar dene.", Toast.LENGTH_LONG).show();
            resumeWakeWordServiceIfActive();
        }
    }

    private void uploadRecording(File audioFile) {
        setBusy(true);
        addMessage("Sen", "🎙 Ses gönderiliyor...");
        network.execute(() -> {
            try {
                JSONObject result = postAudio(audioFile);
                String transcript = result.optString("transkript", "");
                String answer = result.optString("cevap", "");
                runOnUiThread(() -> {
                    if (!transcript.isEmpty()) {
                        addMessage("Sen", transcript);
                    }
                    showAnswer(answer.isEmpty() ? "Bir yanıt alınamadı." : answer);
                });
            } catch (Exception error) {
                runOnUiThread(() -> showError(error));
            } finally {
                deleteRecording();
                runOnUiThread(() -> setBusy(false));
            }
        });
    }

    private String postForm(String path, String... values) throws Exception {
        StringBuilder form = new StringBuilder();
        for (int i = 0; i < values.length; i += 2) {
            if (form.length() > 0) {
                form.append('&');
            }
            form.append(encode(values[i])).append('=').append(encode(values[i + 1]));
        }
        HttpURLConnection connection = openConnection(path);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        try (OutputStream output = connection.getOutputStream()) {
            output.write(form.toString().getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(connection);
    }

    private JSONObject postAudio(File audioFile) throws Exception {
        String boundary = "CipBoundary" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection connection = openConnection("/ses?format=json");
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream output = connection.getOutputStream();
             FileInputStream input = new FileInputStream(audioFile)) {
            writeFormPart(output, boundary, "session", sessionId());
            output.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"recording.m4a\"\r\n"
                    + "Content-Type: audio/mp4\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        return new JSONObject(readResponse(connection));
    }

    private void writeFormPart(OutputStream output, String boundary, String name, String value) throws Exception {
        output.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private HttpURLConnection openConnection(String path) throws Exception {
        String server = preferences.getString(KEY_SERVER_URL, "");
        String secret = readApiKey();
        if (server.isEmpty() || secret.isEmpty()) {
            throw new IllegalStateException("Sunucu adresi veya API anahtarı ayarlanmamış.");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(server + path).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(90000);
        connection.setRequestProperty("Authorization", "Bearer " + secret);
        connection.setRequestProperty("Accept", "application/json, text/plain");
        return connection;
    }

    private String readResponse(HttpURLConnection connection) throws Exception {
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream();
            StringBuilder body = new StringBuilder();
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        body.append(line);
                    }
                }
            }
            if (status == 401) {
                throw new IllegalStateException("API anahtarı geçersiz. Ayarlar'dan kontrol et.");
            }
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("Sunucu " + status + " yanıtı verdi: " + body);
            }
            return body.toString();
        } finally {
            connection.disconnect();
        }
    }

    private void showAnswer(String answer) {
        addMessage("Çip", answer);
        if (ttsReady && !answer.isEmpty()) {
            int result = textToSpeech.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "cip-answer");
            if (result == TextToSpeech.ERROR) {
                resumeWakeWordServiceIfActive();
            }
        } else {
            resumeWakeWordServiceIfActive();
        }
    }

    private void showError(Exception error) {
        addMessage("Hata", error.getMessage() == null ? "Sunucuya bağlanılamadı." : error.getMessage());
        resumeWakeWordServiceIfActive();
    }

    private void addMessage(String sender, String text) {
        boolean isUser = sender.equals("Sen");
        LinearLayout message = new LinearLayout(this);
        message.setOrientation(LinearLayout.VERTICAL);
        message.setPadding(dp(14), dp(10), dp(14), dp(10));
        message.setBackground(roundedBackground(isUser ? 0xFFE4F5EC : 0xFFF1F5F1,
                dp(18), isUser ? 0xFFCDE7D8 : 0xFFE5ECE6));
        TextView author = new TextView(this);
        author.setText(isUser ? R.string.message_author_user
                : sender.equals("Çip") ? R.string.message_author_assistant : R.string.message_author_info);
        author.setTextSize(10);
        author.setLetterSpacing(0.08f);
        author.setTypeface(null, android.graphics.Typeface.BOLD);
        author.setTextColor(isUser ? 0xFF117251 : sender.equals("Çip") ? 0xFF246589 : 0xFF9C4B2D);
        message.addView(author);
        TextView body = new TextView(this);
        body.setText(text);
        body.setTextSize(15);
        body.setTextColor(0xFF20352F);
        body.setLineSpacing(dp(2), 1.0f);
        body.setPadding(0, dp(3), 0, 0);
        message.addView(body);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        conversation.addView(message, params);
        message.setAlpha(0f);
        message.setTranslationY(dp(8));
        message.animate().alpha(1f).translationY(0).setDuration(180).start();
        conversationScroll.post(() -> conversationScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void animateTap(View view) {
        view.animate().cancel();
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(100)
                .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f)
                        .setDuration(140).start())
                .start();
    }

    private void setBusy(boolean value) {
        busy = value;
        sendButton.setEnabled(!value);
        recordButton.setEnabled(!value);
        sendButton.setText(value ? "Bekle..." : "Gönder");
        sendButton.setAlpha(value ? 0.65f : 1f);
    }

    private boolean ensureConfigured() {
        if (preferences.getString(KEY_SERVER_URL, "").isEmpty() || !hasApiKey()) {
            showSettings();
            return false;
        }
        if (busy) {
            return false;
        }
        return true;
    }

    private boolean hasApiKey() {
        try {
            return !readApiKey().isEmpty();
        } catch (IllegalStateException error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private String sessionId() {
        return preferences.getString(KEY_SESSION, "");
    }

    private String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void saveApiKey(String apiKey) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey());
        byte[] encrypted = cipher.doFinal(apiKey.getBytes(StandardCharsets.UTF_8));
        String value = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        preferences.edit().putString(KEY_API_SECRET, value).apply();
    }

    private String readApiKey() {
        String stored = preferences.getString(KEY_API_SECRET, "");
        if (stored.isEmpty()) {
            return "";
        }
        try {
            String[] parts = stored.split(":", 2);
            if (parts.length != 2) {
                throw new IllegalStateException("Saklanan API anahtarı okunamadı.");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(),
                    new GCMParameterSpec(128, Base64.decode(parts[0], Base64.DEFAULT)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.DEFAULT)), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("Cihazdaki API anahtarı çözülemedi; Ayarlar'dan yeniden gir.", error);
        }
    }

    private SecretKey getOrCreateSecretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEYSTORE_ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private void clearApiKey() throws Exception {
        preferences.edit().remove(KEY_API_SECRET).apply();
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            keyStore.deleteEntry(KEYSTORE_ALIAS);
        }
    }

    private void releaseRecorder() {
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
    }

    private void deleteRecording() {
        if (recordingFile != null) {
            if (recordingFile.exists() && !recordingFile.delete()) {
                runOnUiThread(() -> Toast.makeText(this, "Geçici ses dosyası silinemedi.", Toast.LENGTH_LONG).show());
            }
            recordingFile = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MICROPHONE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording();
            } else {
                Toast.makeText(this, "Sesli komut için mikrofon izni gerekli.", Toast.LENGTH_LONG).show();
                resumeWakeWordServiceIfActive();
            }
        } else if (requestCode == REQUEST_WAKE_MICROPHONE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startWakeWordAfterPermissions();
            } else {
                updateWakeStatus("Sürekli dinleme için mikrofon izni gerekli.");
            }
        } else if (requestCode == REQUEST_NOTIFICATIONS) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                    || (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                startWakeWordAfterPermissions();
            } else {
                updateWakeStatus("Sürekli dinleme bildirimi için bildirim izni gerekli.");
            }
        }
    }

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(WakeWordService.ACTION_STATUS);
        filter.addAction(WakeWordService.ACTION_COMMAND);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wakeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(wakeReceiver, filter);
        }
        wakeReceiverRegistered = true;
        if (WakeWordService.isRunning()) {
            updateWakeStatus("Hey Çip için cihaz üstünde dinleme açık.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        String pendingCommand = preferences.getString(WakeWordService.PENDING_COMMAND, "");
        if (!pendingCommand.isEmpty()) {
            handlePendingWakeCommand(pendingCommand);
        }
        transferPollEnabled = true;
        transferHandler.removeCallbacks(transferPollRunnable);
        pollTransferActions(false);
        transferHandler.postDelayed(transferPollRunnable, TRANSFER_POLL_INTERVAL_MS);
    }

    @Override
    protected void onStop() {
        transferPollEnabled = false;
        transferHandler.removeCallbacks(transferPollRunnable);
        if (wakeReceiverRegistered) {
            unregisterReceiver(wakeReceiver);
            wakeReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OPEN_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                addMessage("Çip", "Seçilen dosya: " + selectedFileName(data.getData()));
            }
            resumeWakeWordServiceIfActive();
        } else if (requestCode == REQUEST_PICK_CALL || requestCode == REQUEST_PICK_SMS) {
            if (resultCode == RESULT_OK && data != null) {
                handlePickedPhone(data.getData(), requestCode == REQUEST_PICK_SMS);
            } else {
                resumeWakeWordServiceIfActive();
            }
        } else if (requestCode == REQUEST_NATIVE_ACTIVITY) {
            resumeWakeWordServiceIfActive();
        } else if (requestCode == REQUEST_TRANSFER_SAVE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try {
                    copyVerifiedDownload(data.getData());
                    Toast.makeText(this, "Dosya doğrulanıp kaydedildi.", Toast.LENGTH_LONG).show();
                    deletePendingTransferDownload();
                    pollTransferActions(true);
                } catch (Exception error) {
                    showTransferError(error);
                    offerDownloadSaveRetry();
                }
            } else if (transferDownloadFile != null) {
                offerDownloadSaveRetry();
            }
        } else if (requestCode == REQUEST_TRANSFER_UPLOAD) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                preparePhoneUpload(data.getData());
            } else {
                transferUploadBusy = false;
                transferActionAwaitingUpload = null;
                updateTransferStatus("Dosya seçimi iptal edildi.");
            }
        }
    }

    private void offerDownloadSaveRetry() {
        updateTransferStatus("Dosya doğrulandı; kaydetmek için dokunup konum seç.");
        transferStatus.setOnClickListener(view ->
                requestDownloadDestination(transferActionAwaitingSave, transferDownloadFile));
    }

    @Override
    protected void onDestroy() {
        transferHandler.removeCallbacksAndMessages(null);
        if (isFinishing()) {
            deletePendingTransferDownload();
            deletePendingTransferUpload();
        }
        if (recording && recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException ignored) {
                // Ignore invalid short recordings while the activity is closing.
            }
            releaseRecorder();
            deleteRecording();
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        network.shutdownNow();
        super.onDestroy();
    }
}
