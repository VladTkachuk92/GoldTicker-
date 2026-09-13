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

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TradingViewFeed feed;
    private TextView priceView;
    private Long previousPriceCents;
    private static final int PRICE_UP = Color.rgb(111, 181, 145);
    private static final int PRICE_DOWN = Color.rgb(205, 124, 124);
    private final Runnable resetPriceColor = () -> {
        if (!isDestroyed() && priceView != null) priceView.setTextColor(Color.WHITE);
    };

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

        buildUi();
        hideSystemBars();
        feed = new TradingViewFeed(new TradingViewFeed.Listener() {
            @Override public void onPrice(double price) { showPrice(price); }
            @Override public void onDisconnected() {
                handler.removeCallbacks(resetPriceColor);
                previousPriceCents = null;
                priceView.setText("—");
                priceView.setTextColor(Color.WHITE);
            }
        });
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
        // Single-line mode otherwise measures against a scrolling width,
        // which prevents auto-size from fitting the full price to the screen.
        priceView.setHorizontallyScrolling(false);
        // Keep auto-fitting, then shrink uniformly around the screen centre.
        priceView.setScaleX(0.85f);
        priceView.setScaleY(0.85f);

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

    private void showPrice(double price) {
        long cents = Math.round(price * 100.0);
        if (previousPriceCents != null && cents != previousPriceCents.longValue()) {
            priceView.setTextColor(cents > previousPriceCents ? PRICE_UP : PRICE_DOWN);
            handler.removeCallbacks(resetPriceColor);
            handler.postDelayed(resetPriceColor, 2000);
        }
        previousPriceCents = cents;
        DecimalFormat df = new DecimalFormat("#,##0.00",
                DecimalFormatSymbols.getInstance(Locale.US));
        priceView.setText(df.format(cents / 100.0));
    }

    @Override protected void onStart() {
        super.onStart();
        feed.start();
    }

    @Override protected void onStop() {
        feed.stop();
        super.onStop();
    }

    private void hideSystemBars() {
        Window window = getWindow();
        // Ensure PhoneWindow has created its decor before requesting insets.
        View decor = window.getDecorView();

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
            decor.setSystemUiVisibility(
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
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        feed.destroy();
        super.onDestroy();
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
