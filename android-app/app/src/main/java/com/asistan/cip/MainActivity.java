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
import android.database.Cursor;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
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
    private TextView wakeStatus;
    private MediaRecorder recorder;
    private File recordingFile;
    private boolean recording;
    private boolean busy;
    private TextToSpeech textToSpeech;
    private boolean ttsReady;
    private boolean wakeReceiverRegistered;

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
        root.setBackgroundColor(0xFFF4F7F6);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Çip Asistan");
        title.setTextSize(24);
        title.setTextColor(0xFF124E43);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button settings = new Button(this);
        settings.setText("Ayarlar");
        settings.setOnClickListener(view -> showSettings());
        header.addView(settings);
        root.addView(header);

        TextView intro = new TextView(this);
        intro.setText("Yaz, ses gönder veya “Hey Çip” diyerek cihazda dinlemeyi başlat. "
                + "Uyandırma sözcüğü konuşmacı kimliğini doğrulamaz.");
        intro.setTextSize(14);
        intro.setTextColor(0xFF52625F);
        root.addView(intro);

        LinearLayout wakeControls = new LinearLayout(this);
        wakeControls.setGravity(Gravity.CENTER_VERTICAL);
        wakeStatus = new TextView(this);
        wakeStatus.setText("Sürekli dinleme kapalı.");
        wakeStatus.setTextSize(13);
        wakeStatus.setTextColor(0xFF52625F);
        wakeControls.addView(wakeStatus, new LinearLayout.LayoutParams(0, -2, 1));
        wakeWordButton = new Button(this);
        wakeWordButton.setText("Hey Çip'i aç");
        wakeWordButton.setOnClickListener(view -> toggleWakeWordService());
        wakeControls.addView(wakeWordButton);
        root.addView(wakeControls);

        conversation = new LinearLayout(this);
        conversation.setOrientation(LinearLayout.VERTICAL);
        conversation.setPadding(dp(12), dp(10), dp(12), dp(10));
        conversationScroll = new ScrollView(this);
        conversationScroll.setFillViewport(true);
        conversationScroll.setBackgroundColor(0xFFFFFFFF);
        conversationScroll.addView(conversation);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollParams.topMargin = dp(12);
        scrollParams.bottomMargin = dp(12);
        root.addView(conversationScroll, scrollParams);
        addMessage("Çip", "Hazırım. Bana bir şey sorabilir veya ampulünü kontrol etmemi isteyebilirsin.");

        messageInput = new EditText(this);
        messageInput.setHint("Mesajını yaz...");
        messageInput.setMinLines(1);
        messageInput.setMaxLines(4);
        messageInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        messageInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        root.addView(messageInput, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        sendButton = new Button(this);
        sendButton.setText("Gönder");
        sendButton.setOnClickListener(view -> sendText());
        actions.addView(sendButton, new LinearLayout.LayoutParams(0, -2, 1));
        recordButton = new Button(this);
        recordButton.setText("Ses kaydet");
        recordButton.setOnClickListener(view -> toggleRecording());
        LinearLayout.LayoutParams recordParams = new LinearLayout.LayoutParams(0, -2, 1);
        recordParams.leftMargin = dp(8);
        actions.addView(recordButton, recordParams);
        root.addView(actions);

        LinearLayout deviceActions = new LinearLayout(this);
        deviceActions.setGravity(Gravity.CENTER);
        addDeviceActionButton(deviceActions, "Dosya seç", DeviceCommandRouter.Action.OPEN_FILE);
        addDeviceActionButton(deviceActions, "Kişi ara", DeviceCommandRouter.Action.PICK_PHONE_TO_CALL);
        addDeviceActionButton(deviceActions, "SMS", DeviceCommandRouter.Action.PICK_PHONE_FOR_SMS);
        addDeviceActionButton(deviceActions, "Takvim", DeviceCommandRouter.Action.CREATE_CALENDAR_EVENT);
        root.addView(deviceActions);
        setContentView(root);
    }

    private void addDeviceActionButton(LinearLayout row, String label, DeviceCommandRouter.Action action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(11);
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setOnClickListener(view -> launchDeviceAction(action, null));
        row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
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
        if (wakeWordButton != null) {
            wakeWordButton.setText(WakeWordService.isRunning() ? "Hey Çip'i kapat" : "Hey Çip'i aç");
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
            recordButton.setText("Kaydı bitir");
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
        recordButton.setText("Ses kaydet");
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
        TextView bubble = new TextView(this);
        bubble.setText(sender + "\n" + text);
        bubble.setTextSize(16);
        bubble.setTextColor(sender.equals("Çip") ? 0xFF124E43 : 0xFF263330);
        bubble.setPadding(dp(12), dp(9), dp(12), dp(9));
        bubble.setBackgroundColor(sender.equals("Sen") ? 0xFFE5F2ED : 0xFFF0F4F2);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        conversation.addView(bubble, params);
        conversationScroll.post(() -> conversationScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void setBusy(boolean value) {
        busy = value;
        sendButton.setEnabled(!value);
        recordButton.setEnabled(!value);
        sendButton.setText(value ? "Bekle..." : "Gönder");
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
    }

    @Override
    protected void onStop() {
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
        }
    }

    @Override
    protected void onDestroy() {
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
