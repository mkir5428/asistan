package com.asistan.cip;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Locale;

public final class WakeWordService extends Service {
    static final String ACTION_START = "com.asistan.cip.action.START_WAKE";
    static final String ACTION_STOP = "com.asistan.cip.action.STOP_WAKE";
    static final String ACTION_PAUSE = "com.asistan.cip.action.PAUSE_WAKE";
    static final String ACTION_RESUME = "com.asistan.cip.action.RESUME_WAKE";
    static final String ACTION_STATUS = "com.asistan.cip.action.WAKE_STATUS";
    static final String ACTION_COMMAND = "com.asistan.cip.action.WAKE_COMMAND";
    static final String EXTRA_STATUS = "status";
    static final String EXTRA_COMMAND = "command";
    static final String PENDING_COMMAND = "pending_wake_command";

    private static final String CHANNEL_ID = "cip_wake_word";
    private static final int NOTIFICATION_ID = 7301;
    private static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer speechRecognizer;
    private boolean armed;
    private boolean paused;
    private String notificationStatus = "Hey Çip için cihaz üstünde dinleme açık.";

    static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification(notificationStatus));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopListening();
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            paused = true;
            cancelRecognition();
            publishStatus("Dinleme geçici olarak duraklatıldı.");
            return START_NOT_STICKY;
        }
        if (ACTION_RESUME.equals(action)) {
            paused = false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            stopListening("Bu cihazda Android cihaz-üstü konuşma tanıma kullanılamıyor. "
                    + "Android 12+ ve çevrimdışı Türkçe dil desteği gerekir.");
            return START_NOT_STICKY;
        }
        if (!paused) {
            startRecognition();
        }
        return START_NOT_STICKY;
    }

    @TargetApi(Build.VERSION_CODES.S)
    private void startRecognition() {
        if (paused || !running) {
            return;
        }
        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
                speechRecognizer.setRecognitionListener(new RecognitionListener() {
                    @Override public void onReadyForSpeech(android.os.Bundle params) { }
                    @Override public void onBeginningOfSpeech() { }
                    @Override public void onRmsChanged(float rmsdB) { }
                    @Override public void onBufferReceived(byte[] buffer) { }
                    @Override public void onEndOfSpeech() { }
                    @Override public void onPartialResults(android.os.Bundle partialResults) { }
                    @Override public void onEvent(int eventType, android.os.Bundle params) { }

                    @Override
                    public void onError(int error) {
                        if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                                || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                            stopListening("Cihazın çevrimdışı konuşma tanıması Türkçe'yi desteklemiyor. "
                                    + "Android dil ayarlarını kontrol et.");
                        } else if (!paused) {
                            publishStatus("Dinleme kesildi; yeniden deneniyor.");
                            main.postDelayed(WakeWordService.this::startRecognition, 800);
                        }
                    }

                    @Override
                    public void onResults(android.os.Bundle results) {
                        ArrayList<String> alternatives =
                                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        processTranscript(alternatives == null || alternatives.isEmpty()
                                ? "" : alternatives.get(0));
                    }
                });
            }
            Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            request.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            request.putExtra(RecognizerIntent.EXTRA_LANGUAGE, new Locale("tr", "TR").toLanguageTag());
            request.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            request.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            request.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            speechRecognizer.startListening(request);
            publishStatus(armed ? "Komutunu bekliyorum..." : "Hey Çip için cihaz üstünde dinliyorum.");
        } catch (RuntimeException error) {
            publishStatus("Cihaz-üstü dinleme başlatılamadı: " + error.getMessage());
            main.postDelayed(this::startRecognition, 1500);
        }
    }

    private void processTranscript(String transcript) {
        if (paused) {
            return;
        }
        String command = "";
        if (armed) {
            if (WakeCommandParser.containsWakePhrase(transcript)) {
                command = WakeCommandParser.commandAfterWakePhrase(transcript);
            } else {
                command = transcript.trim();
            }
            if (!command.isEmpty()) {
                armed = false;
            } else {
                publishStatus("Hey Çip algılandı. Komutunu söyle.");
            }
        } else if (WakeCommandParser.containsWakePhrase(transcript)) {
            command = WakeCommandParser.commandAfterWakePhrase(transcript);
            if (command.isEmpty()) {
                armed = true;
                publishStatus("Hey Çip algılandı. Komutunu söyle.");
            }
        }
        if (!command.isEmpty()) {
            paused = true;
            getSharedPreferences("cip_settings", MODE_PRIVATE).edit()
                    .putString(PENDING_COMMAND, command).apply();
            publishStatus("Komut alındı. Uygulama açıldığında işlenecek.");
            Intent broadcast = new Intent(ACTION_COMMAND)
                    .setPackage(getPackageName())
                    .putExtra(EXTRA_COMMAND, command);
            sendBroadcast(broadcast);
            return;
        }
        main.postDelayed(this::startRecognition, 250);
    }

    private void cancelRecognition() {
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
        }
    }

    private void stopListening() {
        stopListening("Sürekli dinleme kapatıldı.");
    }

    private void stopListening(String status) {
        paused = true;
        running = false;
        sendBroadcast(new Intent(ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_STATUS, status));
        main.removeCallbacksAndMessages(null);
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    private void publishStatus(String status) {
        notificationStatus = status;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && running) {
            manager.notify(NOTIFICATION_ID, buildNotification(status));
        }
        sendBroadcast(new Intent(ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_STATUS, status));
    }

    private Notification buildNotification(String status) {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopService = new Intent(this, WakeWordService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopService,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Çip uyandırma sözcüğü")
                .setContentText(status)
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause, "Durdur", stopPendingIntent).build())
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Çip sürekli dinleme", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Hey Çip uyandırma sözcüğünü cihaz üstünde dinler.");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        main.removeCallbacksAndMessages(null);
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
