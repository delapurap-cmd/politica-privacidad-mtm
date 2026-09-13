package com.camiloperdomo.ensayoaudio;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.slider.Slider;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import kotlin.Unit;
import java.io.File;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String[] IDS = {
        "IhnOpwOMHgk","KbhC9y_ny1Y","fcHtRIaxeT4","95QZvA7swMU","l9kXym1doYA",
        "UoeTkpNF2X4","zzIOxHIZEis","kRt2sRyup6A","T_FkEw27XJ0","EzqPPbf1qUk",
        "qM1yOg1GhiY","LOxckiTN3r4","AsL9lKmyg-c","FvYwvQt6jO0","F3blUCzTLPo",
        "0koktesg5Ak","D1IxOiAy2lY","mqOCHYhRaGY","ARR3gkzX8I0","qgU-rmenGAg",
        "AwqSQCluPRg","8Zdhan166z0","Ea655Ji3kKE","MIMVSOiIEzM","3oBGQ8ec_7Q",
        "YglZ5BzPbYs","lw5DYMfy9Xg","Pev2i1f1DWg"
    };

    private final List<CheckBox> checks = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout trackList;
    private ProgressBar progress;
    private TextView status, pitchValue;
    private MaterialButton download;
    private float pitch = 0f;
    private File outputDir;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        outputDir = new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Ensayos");
        outputDir.mkdirs();
        buildUi();
        worker.execute(() -> {
            try {
                YoutubeDL.getInstance().init(getApplication());
                FFmpeg.getInstance().init(getApplication());
                runOnUiThread(() -> status.setText("Lista para descargar"));
            } catch (Exception e) {
                runOnUiThread(() -> showError("No se pudo iniciar el motor: " + e.getMessage()));
            }
        });
    }

    private void buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xff101114);

        TextView title = text("Ensayo Audio", 27, 0xffffffff);
        title.setTypeface(null, 1);
        root.addView(title);
        TextView subtitle = text("28 pistas listas · audio MP3", 14, 0xffaeb3bd);
        subtitle.setPadding(0, dp(2), 0, dp(12));
        root.addView(subtitle);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton all = button("Seleccionar todas");
        MaterialButton none = button("Limpiar");
        actions.addView(all, new LinearLayout.LayoutParams(0, dp(46), 1));
        actions.addView(none, new LinearLayout.LayoutParams(0, dp(46), 1));
        root.addView(actions);

        ScrollView scroll = new ScrollView(this);
        trackList = new LinearLayout(this);
        trackList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(trackList);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollParams.topMargin = dp(8);
        root.addView(scroll, scrollParams);

        for (int i = 0; i < IDS.length; i++) addTrack(i, IDS[i]);
        all.setOnClickListener(v -> checks.forEach(c -> c.setChecked(true)));
        none.setOnClickListener(v -> checks.forEach(c -> c.setChecked(false)));

        MaterialCardView controls = new MaterialCardView(this);
        controls.setCardBackgroundColor(0xff1b1d22);
        controls.setRadius(dp(16));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(10), pad, dp(10));
        pitchValue = text("Cambio de tono: original", 14, 0xffffffff);
        Slider slider = new Slider(this);
        slider.setValueFrom(-6); slider.setValueTo(6); slider.setStepSize(1); slider.setValue(0);
        slider.addOnChangeListener((s, value, fromUser) -> {
            pitch = value;
            pitchValue.setText(value == 0 ? "Cambio de tono: original" :
                "Cambio de tono: " + (value > 0 ? "+" : "") + (int)value + " semitonos");
        });
        box.addView(pitchValue); box.addView(slider);
        controls.addView(box);
        root.addView(controls);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100); progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(6)));
        status = text("Preparando motor…", 13, 0xffaeb3bd);
        status.setPadding(0, dp(5), 0, dp(5)); root.addView(status);
        download = button("DESCARGAR SELECCIONADAS");
        root.addView(download, new LinearLayout.LayoutParams(-1, dp(54)));
        download.setOnClickListener(v -> startDownloads());
        setContentView(root);
    }

    private void addTrack(int index, String id) {
        CheckBox check = new CheckBox(this);
        check.setText(String.format(Locale.US, "%02d  ·  %s", index + 1, id));
        check.setTextColor(0xfff2f3f5); check.setTextSize(15); check.setChecked(true);
        check.setPadding(dp(5), dp(7), dp(5), dp(7));
        checks.add(check); trackList.addView(check);
    }

    private void startDownloads() {
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < checks.size(); i++) if (checks.get(i).isChecked()) selected.add(IDS[i]);
        if (selected.isEmpty()) { Toast.makeText(this, "Selecciona al menos una pista", Toast.LENGTH_SHORT).show(); return; }
        download.setEnabled(false); progress.setVisibility(View.VISIBLE); progress.setProgress(0);
        worker.execute(() -> downloadAll(selected));
    }

    private void downloadAll(List<String> ids) {
        int completed = 0, failed = 0;
        for (String id : ids) {
            final int position = completed + failed + 1;
            runOnUiThread(() -> status.setText("Descargando " + position + " de " + ids.size() + "…"));
            try {
                String base = new File(outputDir, id).getAbsolutePath();
                YoutubeDLRequest req = new YoutubeDLRequest("https://youtu.be/" + id);
                req.addOption("--no-playlist");
                req.addOption("-x");
                req.addOption("--audio-format", "mp3");
                req.addOption("--audio-quality", "0");
                req.addOption("--embed-metadata");
                if (pitch != 0) {
                    double factor = Math.pow(2.0, pitch / 12.0);
                    req.addOption("--postprocessor-args", "ffmpeg:-af asetrate=44100*" + factor +
                        ",aresample=44100,atempo=" + (1.0 / factor));
                }
                req.addOption("-o", base + ".%(ext)s");
                YoutubeDL.getInstance().execute(req, id, (p, eta, line) -> {
                    runOnUiThread(() -> progress.setProgress(Math.max(0, Math.min(100, (int)(float)p))));
                    return Unit.INSTANCE;
                });
                completed++;
            } catch (Exception e) { failed++; }
        }
        int ok = completed, bad = failed;
        runOnUiThread(() -> {
            download.setEnabled(true); progress.setVisibility(View.GONE);
            status.setText(ok + " listas" + (bad > 0 ? " · " + bad + " con error" : ""));
            new AlertDialog.Builder(this).setTitle("Proceso terminado")
                .setMessage("Audios guardados dentro de la carpeta de la app.\n\nCorrectos: " + ok + "\nErrores: " + bad)
                .setPositiveButton("Listo", null).show();
        });
    }

    private TextView text(String value, float size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); return t;
    }
    private MaterialButton button(String value) { MaterialButton b = new MaterialButton(this); b.setText(value); return b; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void showError(String message) { status.setText(message); download.setEnabled(false); }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
