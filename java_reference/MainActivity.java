package com.example;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pure Java, Zero-Dependency Implementation for Android 7.1.2 (API 25) / Snapdragon 210.
 * Can be used directly as com.example.MainActivity in a pure Java project.
 */
public class MainActivity extends Activity {

    public static final byte MAGIC_BYTE = (byte) 0xAA;
    public static final byte EVENT_PING = 0x00;
    public static final byte EVENT_MOVE = 0x01;
    public static final byte EVENT_BUTTON = 0x02;
    public static final byte EVENT_SCROLL = 0x03;

    public static final short BTN_LEFT = 1;
    public static final short BTN_RIGHT = 2;
    public static final short BTN_MIDDLE = 3;

    public static final short STATE_RELEASE = 0;
    public static final short STATE_PRESS = 1;
    public static final short STATE_CLICK = 2;

    private TouchTrackpadView trackpadView;
    private TrackpadTcpServer server;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (trackpadView != null) {
                trackpadView.postInvalidate();
            }
            refreshHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen awake permanently
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        applyImmersiveSticky();

        server = new TrackpadTcpServer(8081);
        trackpadView = new TouchTrackpadView(this, server);
        setContentView(trackpadView);

        server.start();
        refreshHandler.postDelayed(refreshRunnable, 1000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveSticky();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveSticky();
        }
    }

    private void applyImmersiveSticky() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        refreshHandler.removeCallbacks(refreshRunnable);
        if (server != null) {
            server.stop();
        }
    }

    // =========================================================================
    // Background TCP Server (Zero per-packet heap allocations)
    // =========================================================================
    public static class TrackpadTcpServer {
        private final int port;
        private final AtomicBoolean isRunning = new AtomicBoolean(false);
        private ServerSocket serverSocket;
        private Socket clientSocket;
        private OutputStream outputStream;
        private final Object sendLock = new Object();
        private final java.util.concurrent.ArrayBlockingQueue<Long> packetQueue = new java.util.concurrent.ArrayBlockingQueue<>(512);

        public volatile boolean isClientConnected = false;
        public volatile String connectedClientIp = null;
        public volatile long packetsSentTotal = 0;

        public TrackpadTcpServer(int port) {
            this.port = port;
        }

        public void start() {
            if (isRunning.getAndSet(true)) return;

            Thread serverThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runServer();
                }
            }, "TrackpadServerThread");
            serverThread.setPriority(Thread.NORM_PRIORITY);
            serverThread.start();

            Thread senderThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runSender();
                }
            }, "TrackpadSenderThread");
            senderThread.setPriority(Thread.MAX_PRIORITY);
            senderThread.start();
        }

        public void stop() {
            isRunning.set(false);
            closeClient();
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (Exception ignored) {}
            packetQueue.clear();
        }

        private void runServer() {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress("0.0.0.0", port));

                while (isRunning.get()) {
                    try {
                        Socket socket = serverSocket.accept();
                        socket.setTcpNoDelay(true);
                        socket.setSendBufferSize(4096);
                        socket.setSoTimeout(0);

                        synchronized (sendLock) {
                            closeClient();
                            clientSocket = socket;
                            outputStream = socket.getOutputStream();
                            isClientConnected = true;
                            connectedClientIp = socket.getInetAddress().getHostAddress();
                            packetQueue.clear();
                        }

                        byte[] dummy = new byte[64];
                        while (isRunning.get() && socket.isConnected() && !socket.isClosed()) {
                            if (socket.getInputStream().read(dummy) == -1) break;
                        }
                    } catch (IOException ignored) {
                    } finally {
                        synchronized (sendLock) {
                            closeClient();
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("TrackpadServer", "Server fatal error: " + e.getMessage());
            }
        }

        private void runSender() {
            byte[] buffer = new byte[8];
            while (isRunning.get()) {
                try {
                    Long packed = packetQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (packed == null) continue;
                    OutputStream out;
                    synchronized (sendLock) {
                        out = outputStream;
                    }
                    if (out != null) {
                        long p = packed;
                        buffer[0] = (byte) ((p >>> 56) & 0xFFL);
                        buffer[1] = (byte) ((p >>> 48) & 0xFFL);
                        buffer[2] = (byte) ((p >>> 40) & 0xFFL);
                        buffer[3] = (byte) ((p >>> 32) & 0xFFL);
                        buffer[4] = (byte) ((p >>> 24) & 0xFFL);
                        buffer[5] = (byte) ((p >>> 16) & 0xFFL);
                        buffer[6] = (byte) ((p >>> 8) & 0xFFL);
                        buffer[7] = (byte) (p & 0xFFL);

                        out.write(buffer, 0, 8);
                        if (packetQueue.isEmpty()) {
                            out.flush();
                        }
                        packetsSentTotal++;
                    }
                } catch (InterruptedException ignored) {
                    break;
                } catch (IOException e) {
                    synchronized (sendLock) {
                        closeClient();
                    }
                }
            }
        }

        private void closeClient() {
            isClientConnected = false;
            connectedClientIp = null;
            packetQueue.clear();
            try {
                if (outputStream != null) outputStream.close();
            } catch (Exception ignored) {}
            outputStream = null;

            try {
                if (clientSocket != null) clientSocket.close();
            } catch (Exception ignored) {}
            clientSocket = null;
        }

        public boolean sendPacket(byte eventType, short code, short val1, short val2) {
            if (!isClientConnected) return false;
            long p = ((long) MAGIC_BYTE & 0xFFL) << 56;
            p |= ((long) eventType & 0xFFL) << 48;
            p |= ((long) code & 0xFFFFL) << 32;
            p |= ((long) val1 & 0xFFFFL) << 16;
            p |= ((long) val2 & 0xFFFFL);

            if (!packetQueue.offer(p)) {
                packetQueue.poll();
                packetQueue.offer(p);
            }
            return true;
        }
    }

    // =========================================================================
    // Custom View for High-Precision Touch Input & Pure Black UI
    // =========================================================================
    public static class TouchTrackpadView extends View {
        private final TrackpadTcpServer server;
        private float sensitivity = 1.20f;
        private boolean showButtonBar = true;
        private boolean showHud = true;

        private int activePointerId = MotionEvent.INVALID_POINTER_ID;
        private float lastX, lastY, startX, startY;
        private long downTime;
        private float totalMovement;
        private float accumDx, accumDy;

        private long lastTapUpTime = 0;
        private float lastTapX, lastTapY;
        private boolean isDraggingWithDoubleTap = false;

        private long twoFingerDownTime = 0;
        private float lastScrollY = 0;
        private float twoFingerMovement = 0;
        private float accumScrollY = 0;

        private final RectF leftButtonRect = new RectF();
        private final RectF middleButtonRect = new RectF();
        private final RectF rightButtonRect = new RectF();
        private final RectF hudToggleRect = new RectF();
        private final RectF sensMinusRect = new RectF();
        private final RectF sensPlusRect = new RectF();

        private int activeBottomButtonPointerId = MotionEvent.INVALID_POINTER_ID;
        private short activeBottomButtonCode = 0;

        private final Paint statusDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint subTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint buttonBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint buttonFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint buttonPressedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint buttonTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public TouchTrackpadView(Context context, TrackpadTcpServer server) {
            super(context);
            this.server = server;
            init();
        }

        public TouchTrackpadView(Context context, AttributeSet attrs) {
            super(context, attrs);
            this.server = null;
            init();
        }

        private void init() {
            setBackgroundColor(Color.BLACK);
            textPaint.setColor(Color.parseColor("#9E9E9E"));
            textPaint.setTextSize(34f);
            subTextPaint.setColor(Color.parseColor("#616161"));
            subTextPaint.setTextSize(26f);
            buttonBorderPaint.setColor(Color.parseColor("#262626"));
            buttonBorderPaint.setStyle(Paint.Style.STROKE);
            buttonBorderPaint.setStrokeWidth(3f);
            buttonFillPaint.setColor(Color.parseColor("#141414"));
            buttonFillPaint.setStyle(Paint.Style.FILL);
            buttonPressedPaint.setColor(Color.parseColor("#1E3A2F"));
            buttonPressedPaint.setStyle(Paint.Style.FILL);
            buttonTextPaint.setColor(Color.parseColor("#757575"));
            buttonTextPaint.setTextSize(32f);
            buttonTextPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            float btnTop = h - 160f;
            float midWidth = 140f;
            float sideWidth = (w - midWidth) / 2f;
            leftButtonRect.set(0, btnTop, sideWidth, h);
            middleButtonRect.set(sideWidth, btnTop, sideWidth + midWidth, h);
            rightButtonRect.set(sideWidth + midWidth, btnTop, w, h);
            hudToggleRect.set(16f, 16f, 160f, 80f);
            sensMinusRect.set(w - 240f, 16f, w - 160f, 80f);
            sensPlusRect.set(w - 140f, 16f, w - 60f, 80f);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            int pointerIndex = event.getActionIndex();
            int pointerId = event.getPointerId(pointerIndex);
            int pointerCount = event.getPointerCount();
            float cx = event.getX(pointerIndex);
            float cy = event.getY(pointerIndex);

            if (action == MotionEvent.ACTION_DOWN) {
                if (hudToggleRect.contains(cx, cy)) {
                    showHud = !showHud;
                    invalidate();
                    return true;
                }
                if (showHud) {
                    if (sensMinusRect.contains(cx, cy)) {
                        sensitivity = Math.max(0.25f, sensitivity - 0.1f);
                        invalidate();
                        return true;
                    }
                    if (sensPlusRect.contains(cx, cy)) {
                        sensitivity = Math.min(4.0f, sensitivity + 0.1f);
                        invalidate();
                        return true;
                    }
                }
                if (showButtonBar && cy >= leftButtonRect.top) {
                    activeBottomButtonPointerId = pointerId;
                    if (leftButtonRect.contains(cx, cy)) activeBottomButtonCode = BTN_LEFT;
                    else if (middleButtonRect.contains(cx, cy)) activeBottomButtonCode = BTN_MIDDLE;
                    else if (rightButtonRect.contains(cx, cy)) activeBottomButtonCode = BTN_RIGHT;
                    if (activeBottomButtonCode > 0 && server != null) {
                        server.sendPacket(EVENT_BUTTON, activeBottomButtonCode, STATE_PRESS, (short) 0);
                        invalidate();
                        return true;
                    }
                }
            }

            if (pointerId == activeBottomButtonPointerId) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
                    if (activeBottomButtonCode > 0 && server != null) {
                        server.sendPacket(EVENT_BUTTON, activeBottomButtonCode, STATE_RELEASE, (short) 0);
                        activeBottomButtonCode = 0;
                    }
                    activeBottomButtonPointerId = MotionEvent.INVALID_POINTER_ID;
                    invalidate();
                    return true;
                }
                return true;
            }

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    activePointerId = pointerId;
                    lastX = cx; lastY = cy;
                    startX = cx; startY = cy;
                    downTime = SystemClock.uptimeMillis();
                    totalMovement = 0; accumDx = 0; accumDy = 0;

                    long timeSinceLast = downTime - lastTapUpTime;
                    float distFromLast = (float) Math.hypot(startX - lastTapX, startY - lastTapY);
                    if (timeSinceLast < 280 && distFromLast < 36f) {
                        isDraggingWithDoubleTap = true;
                        if (server != null) server.sendPacket(EVENT_BUTTON, BTN_LEFT, STATE_PRESS, (short) 0);
                    } else {
                        isDraggingWithDoubleTap = false;
                    }
                    break;

                case MotionEvent.ACTION_POINTER_DOWN:
                    if (pointerCount == 2) {
                        twoFingerDownTime = SystemClock.uptimeMillis();
                        lastScrollY = (event.getY(0) + event.getY(1)) / 2f;
                        twoFingerMovement = 0; accumScrollY = 0;
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (pointerCount == 1 && activePointerId != MotionEvent.INVALID_POINTER_ID) {
                        int idx = event.findPointerIndex(activePointerId);
                        if (idx != -1) {
                            float px = event.getX(idx);
                            float py = event.getY(idx);
                            if (!showButtonBar || py < leftButtonRect.top) {
                                float rawDx = (px - lastX) * sensitivity;
                                float rawDy = (py - lastY) * sensitivity;
                                lastX = px; lastY = py;
                                accumDx += rawDx; accumDy += rawDy;
                                int stepDx = (int) accumDx;
                                int stepDy = (int) accumDy;
                                if (stepDx != 0 || stepDy != 0) {
                                    accumDx -= stepDx; accumDy -= stepDy;
                                    totalMovement += Math.abs(rawDx) + Math.abs(rawDy);
                                    if (server != null) server.sendPacket(EVENT_MOVE, (short) 0, (short) stepDx, (short) stepDy);
                                }
                            }
                        }
                    } else if (pointerCount >= 2) {
                        float currScrollY = (event.getY(0) + event.getY(1)) / 2f;
                        float deltaY = currScrollY - lastScrollY;
                        lastScrollY = currScrollY;
                        accumScrollY += deltaY;
                        twoFingerMovement += Math.abs(deltaY);
                        while (accumScrollY >= 16f) {
                            if (server != null) server.sendPacket(EVENT_SCROLL, (short) 0, (short) 1, (short) 0);
                            accumScrollY -= 16f;
                        }
                        while (accumScrollY <= -16f) {
                            if (server != null) server.sendPacket(EVENT_SCROLL, (short) 0, (short) -1, (short) 0);
                            accumScrollY += 16f;
                        }
                    }
                    break;

                case MotionEvent.ACTION_POINTER_UP:
                    if (pointerCount == 2) {
                        long duration = SystemClock.uptimeMillis() - twoFingerDownTime;
                        if (duration < 230 && twoFingerMovement < 18f && server != null) {
                            server.sendPacket(EVENT_BUTTON, BTN_RIGHT, STATE_CLICK, (short) 0);
                        }
                    }
                    break;

                case MotionEvent.ACTION_UP:
                    long upTime = SystemClock.uptimeMillis();
                    if (isDraggingWithDoubleTap) {
                        if (server != null) server.sendPacket(EVENT_BUTTON, BTN_LEFT, STATE_RELEASE, (short) 0);
                        isDraggingWithDoubleTap = false;
                        lastTapUpTime = 0;
                    } else if (pointerCount == 1 && (upTime - downTime) < 230 && totalMovement < 18f) {
                        if (server != null) server.sendPacket(EVENT_BUTTON, BTN_LEFT, STATE_CLICK, (short) 0);
                        lastTapUpTime = upTime;
                        lastTapX = startX; lastTapY = startY;
                    } else {
                        lastTapUpTime = 0;
                    }
                    activePointerId = MotionEvent.INVALID_POINTER_ID;
                    break;

                case MotionEvent.ACTION_CANCEL:
                    if (isDraggingWithDoubleTap && server != null) {
                        server.sendPacket(EVENT_BUTTON, BTN_LEFT, STATE_RELEASE, (short) 0);
                        isDraggingWithDoubleTap = false;
                    }
                    activePointerId = MotionEvent.INVALID_POINTER_ID;
                    activeBottomButtonPointerId = MotionEvent.INVALID_POINTER_ID;
                    break;
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            boolean isConnected = server != null && server.isClientConnected;
            statusDotPaint.setColor(isConnected ? Color.parseColor("#00E676") : Color.parseColor("#FF5252"));
            canvas.drawCircle(40f, 48f, 10f, statusDotPaint);

            if (showHud) {
                String title = isConnected ? "Connected: " + server.connectedClientIp : "Waiting for Linux host...";
                canvas.drawText(title, 66f, 56f, textPaint);
                String info = "Port 8081 (adb forward) | Pkts: " + (server != null ? server.packetsSentTotal : 0);
                canvas.drawText(info, 66f, 96f, subTextPaint);
                canvas.drawText(String.format("Speed: %.1fx", sensitivity), getWidth() - 420f, 56f, textPaint);
                buttonFillPaint.setColor(Color.parseColor("#212121"));
                canvas.drawRoundRect(sensMinusRect, 8f, 8f, buttonFillPaint);
                canvas.drawText("-", sensMinusRect.centerX(), sensMinusRect.centerY() + 10f, buttonTextPaint);
                canvas.drawRoundRect(sensPlusRect, 8f, 8f, buttonFillPaint);
                canvas.drawText("+", sensPlusRect.centerX(), sensPlusRect.centerY() + 10f, buttonTextPaint);
            }

            if (showButtonBar) {
                canvas.drawRect(leftButtonRect, activeBottomButtonCode == BTN_LEFT ? buttonPressedPaint : buttonFillPaint);
                canvas.drawRect(leftButtonRect, buttonBorderPaint);
                canvas.drawText("LEFT CLICK", leftButtonRect.centerX(), leftButtonRect.centerY() + 10f, buttonTextPaint);

                canvas.drawRect(middleButtonRect, activeBottomButtonCode == BTN_MIDDLE ? buttonPressedPaint : buttonFillPaint);
                canvas.drawRect(middleButtonRect, buttonBorderPaint);
                canvas.drawText("MID", middleButtonRect.centerX(), middleButtonRect.centerY() + 10f, buttonTextPaint);

                canvas.drawRect(rightButtonRect, activeBottomButtonCode == BTN_RIGHT ? buttonPressedPaint : buttonFillPaint);
                canvas.drawRect(rightButtonRect, buttonBorderPaint);
                canvas.drawText("RIGHT CLICK", rightButtonRect.centerX(), rightButtonRect.centerY() + 10f, buttonTextPaint);
            }
        }
    }
}
