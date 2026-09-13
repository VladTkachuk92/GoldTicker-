package com.vlad.goldticker;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Guest quote stream; all connection state and UI callbacks belong to the main thread. */
final class TradingViewFeed {
    interface Listener {
        void onPrice(double price);
        void onDisconnected();
    }

    private static final String SYMBOL = "OANDA:XAUUSD";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS).build();
    private final Listener listener;
    private WebSocket socket;
    private boolean active;
    private int generation;
    private int retrySeconds = 1;
    private long connectedAt;
    private long lastMessageAt;
    private boolean receivedPrice;
    private String session;
    private final StringBuilder buffer = new StringBuilder();
    private final Runnable reconnect = this::connect;
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!active) return;
            long now = SystemClock.elapsedRealtime();
            if (now - lastMessageAt > 45000 || (!receivedPrice && now - connectedAt > 25000)) {
                retry();
            } else {
                handler.postDelayed(this, 5000);
            }
        }
    };

    TradingViewFeed(Listener listener) { this.listener = listener; }

    void start() {
        if (active) return;
        active = true;
        retrySeconds = 1;
        connect();
    }

    void stop() {
        active = false;
        generation++;
        handler.removeCallbacksAndMessages(null);
        if (socket != null) socket.cancel();
        socket = null;
        buffer.setLength(0);
        listener.onDisconnected();
    }

    void destroy() {
        stop();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    private void connect() {
        if (!active) return;
        final int attempt = ++generation;
        buffer.setLength(0);
        receivedPrice = false;
        connectedAt = lastMessageAt = SystemClock.elapsedRealtime();
        session = "qs_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Request request = new Request.Builder()
                .url("wss://data.tradingview.com/socket.io/websocket?from=chart&type=chart")
                .header("Origin", "https://www.tradingview.com").build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            private void dispatch(Runnable action) {
                handler.post(() -> { if (active && generation == attempt) action.run(); });
            }
            @Override public void onOpen(WebSocket ws, Response response) {
                dispatch(() -> {
                    send("set_auth_token", "unauthorized_user_token");
                    send("quote_create_session", session);
                    send("quote_set_fields", session, "lp", "lp_time", "update_mode");
                    send("quote_add_symbols", session, SYMBOL);
                });
            }
            @Override public void onMessage(WebSocket ws, String message) {
                dispatch(() -> receive(message));
            }
            @Override public void onFailure(WebSocket ws, Throwable error, Response response) {
                dispatch(() -> retry());
            }
            @Override public void onClosing(WebSocket ws, int code, String reason) {
                dispatch(() -> retry());
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                dispatch(() -> retry());
            }
        });
        handler.postDelayed(watchdog, 5000);
    }

    private static String frame(String payload) {
        return "~m~" + payload.length() + "~m~" + payload;
    }

    private void send(String method, String... arguments) {
        try {
            JSONObject packet = new JSONObject();
            packet.put("m", method);
            JSONArray params = new JSONArray();
            for (String value : arguments) params.put(value);
            packet.put("p", params);
            if (socket != null && !socket.send(frame(packet.toString()))) retry();
        } catch (Exception error) { retry(); }
    }

    private void receive(String message) {
        lastMessageAt = SystemClock.elapsedRealtime();
        buffer.append(message);
        try {
            if (buffer.length() > 262144) throw new IllegalArgumentException("Frame too large");
            while (buffer.length() >= 3) {
                if (!buffer.substring(0, 3).equals("~m~")) throw new IllegalArgumentException("Frame header");
                int separator = buffer.indexOf("~m~", 3);
                if (separator < 0) return;
                int length = Integer.parseInt(buffer.substring(3, separator));
                if (length < 0 || length > 262144) throw new IllegalArgumentException("Frame length");
                int start = separator + 3;
                if (buffer.length() - start < length) return;
                String payload = buffer.substring(start, start + length);
                buffer.delete(0, start + length);
                if (payload.startsWith("~h~")) {
                    if (socket != null && !socket.send(frame(payload))) { retry(); return; }
                    continue;
                }
                JSONObject packet = new JSONObject(payload);
                String method = packet.optString("m");
                if (method.endsWith("error")) { retry(); return; }
                if (!"qsd".equals(method)) continue;
                JSONArray params = packet.optJSONArray("p");
                if (params == null || !session.equals(params.optString(0))) continue;
                JSONObject quote = params.optJSONObject(1);
                if (quote == null || !SYMBOL.equals(quote.optString("n"))) continue;
                if (!"ok".equals(quote.optString("s"))) { retry(); return; }
                JSONObject values = quote.optJSONObject("v");
                if (values == null || !values.has("lp")) continue;
                double price = values.optDouble("lp", Double.NaN);
                if (Double.isNaN(price) || Double.isInfinite(price) || price <= 0) continue;
                if (!receivedPrice) Log.i("GoldTickerStream", "quote_received");
                receivedPrice = true;
                retrySeconds = 1;
                listener.onPrice(price);
            }
        } catch (Exception error) { retry(); }
    }

    private void retry() {
        if (!active) return;
        generation++;
        handler.removeCallbacks(watchdog);
        handler.removeCallbacks(reconnect);
        if (socket != null) socket.cancel();
        socket = null;
        buffer.setLength(0);
        listener.onDisconnected();
        handler.postDelayed(reconnect, retrySeconds * 1000L);
        retrySeconds = Math.min(30, retrySeconds * 2);
    }
}
