package com.vlad.goldticker;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView priceView;
    private boolean requestRunning = false;

    private static final String PAYLOAD =
            "{\"symbols\":{\"tickers\":[\"OANDA:XAUUSD\"]},\"columns\":[\"close\"]}";

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            if (!requestRunning) fetchPrice();
            handler.postDelayed(this, 750);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        hideSystemBars();
        buildUi();
        handler.post(pollTask);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        priceView = new TextView(this);
        priceView.setText("—");
        priceView.setTextColor(Color.WHITE);
        priceView.setGravity(Gravity.CENTER);
        priceView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        priceView.setIncludeFontPadding(false);
        priceView.setSingleLine(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            priceView.setAutoSizeTextTypeUniformWithConfiguration(
                    90, 500, 2, TypedValue.COMPLEX_UNIT_SP
            );
        } else {
            priceView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 220);
        }

        int pad = dp(24);
        priceView.setPadding(pad, 0, pad, 0);

        root.addView(priceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        root.setOnClickListener(v -> hideSystemBars());
        setContentView(root);
    }

    private void fetchPrice() {
        requestRunning = true;
        executor.execute(() -> {
            Double price = request("https://scanner.tradingview.com/global/scan");
            if (price == null) {
                price = request("https://scanner.tradingview.com/forex/scan");
            }

            final Double finalPrice = price;
            handler.post(() -> {
                requestRunning = false;
                if (finalPrice != null && priceView != null) {
                    DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.US);
                    DecimalFormat df = new DecimalFormat("#,##0.00", symbols);
                    priceView.setText(df.format(finalPrice));
                }
            });
        });
    }

    private Double request(String endpoint) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(endpoint);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 Android GoldTicker");

            byte[] body = PAYLOAD.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(body);
            }

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) return null;

            InputStream in = connection.getInputStream();
            StringBuilder result = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) result.append(line);
            }

            JSONObject root = new JSONObject(result.toString());
            JSONArray data = root.optJSONArray("data");
            if (data == null || data.length() == 0) return null;

            JSONArray d = data.getJSONObject(0).optJSONArray("d");
            if (d == null || d.length() == 0 || d.isNull(0)) return null;

            return d.getDouble(0);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void hideSystemBars() {
        Window window = getWindow();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
