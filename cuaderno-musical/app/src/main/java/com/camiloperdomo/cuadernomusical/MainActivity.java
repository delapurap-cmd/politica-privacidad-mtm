package com.camiloperdomo.cuadernomusical;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kotlin.Unit;

public class MainActivity extends AppCompatActivity {
    private WebView web;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler timer = new Handler(Looper.getMainLooper());
    private SharedPreferences tracks;
    private File audioDir;
    private MediaPlayer player;
    private String playingId = "";
    private String playingTitle = "";
    private long loopA = 0;
    private long loopB = Long.MAX_VALUE;
    private boolean loopEnabled = false;
    private float speed = 1f;
    private float pitch = 1f;
    private volatile boolean engineReady = false;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        tracks = getSharedPreferences("tracks", MODE_PRIVATE);
        audioDir = new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "CuadernoMusical");
        audioDir.mkdirs();

        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new NotebookBridge(), "Native");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xff121418);
        root.addView(web, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
            );
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        web.loadUrl("file:///android_asset/cuaderno.html");

        worker.execute(() -> {
            try {
                YoutubeDL.getInstance().init(getApplication());
                FFmpeg.getInstance().init(getApplication());
                try {
                    YoutubeDL.getInstance().updateYoutubeDL(getApplicationContext(), YoutubeDL.UpdateChannel._STABLE);
                } catch (Exception ignored) {
                    // El motor incluido sigue disponible si no hay actualización o no hay red.
                }
                engineReady = true;
                sendJs("window.nativeEngineReady&&window.nativeEngineReady()") ;
            } catch (Exception e) {
                sendJs("window.nativeEngineError&&window.nativeEngineError(" + JSONObject.quote(e.getMessage()) + ")");
            }
        });
        timer.post(ticker);
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            try {
                if (player != null) {
                    long position = player.getCurrentPosition();
                    if (loopEnabled && loopB > loopA && position >= loopB) {
                        player.seekTo((int) loopA);
                        position = loopA;
                    }
                    sendPlayerState(position);
                }
            } catch (Exception ignored) { }
            timer.postDelayed(this, 160);
        }
    };

    private void sendJs(String script) {
        runOnUiThread(() -> web.evaluateJavascript(script, null));
    }

    private void sendPlayerState(long position) {
        try {
            JSONObject data = new JSONObject();
            data.put("id", playingId);
            data.put("title", playingTitle);
            data.put("position", position);
            data.put("duration", player == null ? 0 : player.getDuration());
            data.put("playing", player != null && player.isPlaying());
            data.put("loop", loopEnabled);
            sendJs("window.nativePlayerState&&window.nativePlayerState(" + data + ")");
        } catch (Exception ignored) { }
    }

    private final class NotebookBridge {
        @JavascriptInterface public boolean isEngineReady() {
            return engineReady;
        }

        @JavascriptInterface public String getTrackState(String id) {
            try {
                String path = tracks.getString(id + ".path", "");
                if (path.isEmpty() || !new File(path).isFile()) return "{}";
                JSONObject o = new JSONObject();
                o.put("downloaded", true);
                o.put("path", path);
                o.put("duration", tracks.getLong(id + ".duration", 0));
                o.put("wave", new JSONArray(tracks.getString(id + ".wave", "[]")));
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public void download(String id, String url, String title) {
            worker.execute(() -> downloadTrack(id, url, title));
        }

        @JavascriptInterface public void removeTrack(String id) {
            worker.execute(() -> deleteTrack(id));
        }

        @JavascriptInterface public void play(String id, String title) {
            runOnUiThread(() -> startTrack(id, title));
        }

        @JavascriptInterface public void toggle() {
            runOnUiThread(() -> {
                try {
                    if (player == null) return;
                    if (player.isPlaying()) player.pause(); else player.start();
                    sendPlayerState(player.getCurrentPosition());
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface public void pause() {
            runOnUiThread(() -> {
                try {
                    if (player != null && player.isPlaying()) {
                        player.pause();
                        sendPlayerState(player.getCurrentPosition());
                    }
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface public void seek(long millis) {
            runOnUiThread(() -> { if (player != null) player.seekTo((int) millis); });
        }

        @JavascriptInterface public void setLoop(long a, long b, boolean enabled) {
            loopA = Math.max(0, a);
            loopB = Math.max(loopA, b);
            loopEnabled = enabled;
        }

        @JavascriptInterface public void setSpeed(double value) {
            speed = (float)Math.max(.5, Math.min(1.5, value));
            runOnUiThread(() -> applyPlaybackParams());
        }

        @JavascriptInterface public void setPitch(double semitones) {
            pitch = (float)Math.pow(2.0, Math.max(-6, Math.min(6, semitones)) / 12.0);
            runOnUiThread(() -> applyPlaybackParams());
        }
    }

    private void downloadTrack(String id, String url, String title) {
        sendDownload(id, 0, "downloading", "Preparando…");
        File target = new File(audioDir, safeId(id) + ".mp3");
        try {
            if (target.exists() && !target.delete()) throw new Exception("No se pudo reemplazar el audio anterior");
            YoutubeDLRequest request = new YoutubeDLRequest(url);
            request.addOption("--no-playlist");
            request.addOption("-x");
            request.addOption("--audio-format", "mp3");
            request.addOption("--audio-quality", "0");
            request.addOption("--embed-metadata");
            request.addOption("-o", target.getAbsolutePath());
            YoutubeDL.getInstance().execute(request, "download-" + id, (progress, eta, line) -> {
                // yt-dlp llega a 100 antes de terminar la conversión y el guardado.
                // La interfaz reserva 100 exclusivamente para un MP3 listo para reproducirse.
                sendDownload(id, Math.min(94, Math.round(progress * .94f)), "downloading", "Descargando…");
                return Unit.INSTANCE;
            });
            if (!target.isFile()) throw new Exception("La descarga no produjo un archivo MP3");
            sendDownload(id, 96, "processing", "Guardando MP3…");
            long duration = readDuration(target);
            Uri publicUri = copyToPublicMusic(target, title, id);
            tracks.edit()
                .putString(id + ".path", target.getAbsolutePath())
                .putString(id + ".title", title)
                .putString(id + ".wave", "[]")
                .putLong(id + ".duration", duration)
                .putString(id + ".publicUri", publicUri == null ? "" : publicUri.toString())
                .commit();
            sendDownload(id, 100, "ready", "MP3 listo");

            // La forma de onda se calcula después, sin mantener bloqueado el botón Play.
            String wave = extractWaveform(target, 180).toString();
            tracks.edit().putString(id + ".wave", wave).apply();
            sendDownload(id, 100, "waveform", "");
        } catch (Exception e) {
            sendDownload(id, 0, "error", e.getMessage() == null ? "Error de descarga" : e.getMessage());
        }
    }

    private void sendDownload(String id, int progress, String state, String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id); o.put("progress", progress); o.put("state", state); o.put("message", message);
            sendJs("window.nativeDownloadState&&window.nativeDownloadState(" + o + ")");
        } catch (Exception ignored) { }
    }

    private void startTrack(String id, String title) {
        String path = tracks.getString(id + ".path", "");
        if (path.isEmpty() || !new File(path).isFile()) {
            sendDownload(id, 0, "error", "Primero descarga esta canción");
            return;
        }
        try {
            if (player != null) { player.stop(); player.release(); }
            player = new MediaPlayer();
            player.setDataSource(path);
            player.prepare();
            playingId = id;
            playingTitle = title;
            loopA = 0;
            loopB = player.getDuration();
            loopEnabled = false;
            applyPlaybackParams();
            player.setOnCompletionListener(mp -> {
                if (loopEnabled) { mp.seekTo((int)loopA); mp.start(); }
                else sendPlayerState(mp.getDuration());
            });
            player.start();
            sendPlayerState(0);
        } catch (Exception e) {
            sendDownload(id, 0, "error", "No se pudo reproducir el archivo");
        }
    }

    private void applyPlaybackParams() {
        if (player == null) return;
        try {
            boolean wasPlaying = player.isPlaying();
            PlaybackParams params = player.getPlaybackParams();
            params.setSpeed(speed);
            params.setPitch(pitch);
            player.setPlaybackParams(params);
            if (!wasPlaying) player.pause();
        } catch (Exception ignored) { }
    }

    private void deleteTrack(String id) {
        try {
            if (id.equals(playingId)) runOnUiThread(() -> {
                if (player != null) { player.stop(); player.release(); player = null; }
                playingId = ""; playingTitle = "";
            });
            String path = tracks.getString(id + ".path", "");
            if (!path.isEmpty()) new File(path).delete();
            String uri = tracks.getString(id + ".publicUri", "");
            if (!uri.isEmpty()) getContentResolver().delete(Uri.parse(uri), null, null);
            tracks.edit().remove(id + ".path").remove(id + ".title").remove(id + ".wave")
                .remove(id + ".duration").remove(id + ".publicUri").apply();
            sendDownload(id, 0, "removed", "Audio eliminado");
        } catch (Exception ignored) { }
    }

    private Uri copyToPublicMusic(File source, String title, String id) throws Exception {
        String old = tracks.getString(id + ".publicUri", "");
        if (!old.isEmpty()) try { getContentResolver().delete(Uri.parse(old), null, null); } catch (Exception ignored) { }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, safeTitle(title) + ".mp3");
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg");
        values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Cuaderno Musical");
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("No se pudo crear la carpeta pública de música");
        try (FileInputStream in = new FileInputStream(source); OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new Exception("No se pudo guardar el archivo público");
            byte[] buffer = new byte[128 * 1024]; int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
        values.clear(); values.put(MediaStore.Audio.Media.IS_PENDING, 0);
        getContentResolver().update(uri, values, null, null);
        return uri;
    }

    private long readDuration(File file) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(file.getAbsolutePath());
            String value = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? 0 : Long.parseLong(value);
        } catch (Exception e) { return 0; }
        finally { try { r.release(); } catch (Exception ignored) { } }
    }

    private JSONArray extractWaveform(File file, int buckets) {
        float[] peaks = new float[buckets];
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int track = -1; MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; format = f; break; }
            }
            if (track < 0 || format == null) return new JSONArray();
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 1;
            String mime = format.getString(MediaFormat.KEY_MIME);
            extractor.selectTrack(track);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0); codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer input = codec.getInputBuffer(inIndex);
                        int size = input == null ? -1 : extractor.readSampleData(input, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outIndex >= 0) {
                    ByteBuffer out = codec.getOutputBuffer(outIndex);
                    if (out != null && info.size > 1) {
                        out.order(ByteOrder.LITTLE_ENDIAN);
                        int start = Math.max(0, Math.min(buckets - 1, (int)(info.presentationTimeUs * buckets / Math.max(1, durationUs))));
                        int samples = info.size / 2;
                        out.position(info.offset); out.limit(info.offset + info.size);
                        for (int i = 0; i < samples && out.remaining() >= 2; i++) {
                            int bucket = Math.min(buckets - 1, start + (i * Math.max(1, buckets / 80)) / Math.max(1, samples));
                            float amp = Math.abs((float)out.getShort() / 32768f);
                            if (amp > peaks[bucket]) peaks[bucket] = amp;
                        }
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outIndex, false);
                }
            }
        } catch (Exception ignored) { }
        finally {
            try { extractor.release(); } catch (Exception ignored) { }
            if (codec != null) try { codec.stop(); codec.release(); } catch (Exception ignored) { }
        }
        float max = .001f;
        for (float p : peaks) max = Math.max(max, p);
        JSONArray result = new JSONArray();
        for (float p : peaks) result.put(Float.valueOf(Math.max(.04f, p / max)));
        return result;
    }

    private String safeId(String id) { return id.replaceAll("[^A-Za-z0-9_-]", "_"); }
    private String safeTitle(String title) {
        String clean = title.replaceAll("[\\\\/:*?\"<>|]", " ").replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) clean = "Canción";
        return clean.length() > 90 ? clean.substring(0, 90).trim() : clean;
    }

    @Override public void onBackPressed() {
        web.evaluateJavascript("window.closeOverlay&&window.closeOverlay()", value -> {
            if ("false".equals(value)) super.onBackPressed();
        });
    }

    @Override protected void onDestroy() {
        timer.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        if (player != null) { try { player.release(); } catch (Exception ignored) { } }
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
