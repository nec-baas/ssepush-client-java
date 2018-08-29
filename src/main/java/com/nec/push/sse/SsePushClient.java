/*
 * NEC Mobile Backend Platform: SSE Push Client
 *
 * Copyright (c) 2013-2018, NEC Corporation.
 */
package com.nec.push.sse;

import jersey.repackaged.com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;
import org.glassfish.jersey.media.sse.*;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.StatusType;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import static javax.ws.rs.core.Response.Status.OK;

/**
 * SSE Push 受信クライアントクラス。
 * <p>
 * SSE Push サーバへの接続(Pushメッセージ受信)/切断機能を提供する。
 *
 * @since 4.0.0
 */
public class SsePushClient {
    private static final Logger mLogger = Logger.getLogger(SsePushClient.class.getSimpleName());

    private final Map<String, SsePushEventCallback> mRegisterEvent = new ConcurrentHashMap<>();
    /*package*/ volatile SsePushClientCallback mClientCallback;

    private volatile String mUri;
    private volatile String mUsername;
    private volatile String mPassword;

    /*package*/ static class ClientBuilderFactory {
        ClientBuilder createBuilder() {
            return ClientBuilder.newBuilder();
        }
    }

    /**
     * SSE Push サーバへの接続
     */
    interface Connector {
        /**
         * SSE Push サーバに接続する。
         * @return EventInput。接続失敗時は null。
         */
        EventInput connect();
    }

    /**
     * Connector デフォルト実装
     */
    class DefaultConnector implements Connector {
        @Override
        public EventInput connect() {
            stopHeartbeatMonitoring();

            Response response;
            try {
                response = makeWebTarget(mUri, mUsername, mPassword).request().get();
            } catch (ProcessingException | WebApplicationException e) {
                mLogger.warning("Failed to connect: " + e.getMessage());
                printThrowable(e);
                notifyError(-1, "sse open error: " + e.getMessage());
                return null;
            }

            StatusType statusType = response.getStatusInfo();

            if (statusType.getStatusCode() != OK.getStatusCode()) {
                notifyError(statusType.getStatusCode(), statusType.getReasonPhrase());
                return null;
            }

            EventInput eventInput;
            try {
                eventInput = response.readEntity(EventInput.class);
            } catch (ProcessingException | IllegalStateException e) {
                mLogger.warning("Failed to read entity: " + e.getMessage());
                return null;
            }

            if (eventInput == null || eventInput.isClosed()) {
                notifyError(-1, "sse open error.: No event input.");
                return null;
            }

            return eventInput;
        }
    }

    /*package*/ Connector mConnector = new DefaultConnector();

    /*package*/ ClientBuilderFactory mClientBuilderFactory = new ClientBuilderFactory();

    private final ExecutorService mEventMonitorExecutor
            = Executors.newSingleThreadExecutor(daemonThreadFactory("EventMonitorThread-%d"));

    private final AtomicReference<EventMonitor> mEventMonitor = new AtomicReference<>(null);

    private final ExecutorService mHeartbeatMonitorExecutor
            = Executors.newSingleThreadExecutor(daemonThreadFactory("HeartbeatMonitorThread-%d"));

    private final AtomicLong mLastHeartbeat = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong mHeartbeatInterval = new AtomicLong(0L);
    private final AtomicReference<HeartbeatMonitor> mHeartbeatMonitor = new AtomicReference<>();

    private final ExecutorService mCloseExecutor
            = Executors.newSingleThreadExecutor(daemonThreadFactory("CloseThread-%d"));

    private static ThreadFactory daemonThreadFactory(String nameFormat) {
        return
                new ThreadFactoryBuilder()
                        .setDaemon(true)
                        .setNameFormat(nameFormat)
                        .build();
    }

    /**
     * ログレベルを設定する
     * @param level ログレベル
     * @since 4.0.1
     */
    public static void setLogLevel(Level level) {
        mLogger.setLevel(level);
    }

    /**
     * SSE Push サーバと接続し、受信を開始する。
     *
     * @param uri      SSE Push サーバのURI
     * @param username 認証用ユーザ名
     * @param password 認証用パスワード
     * @param callback コールバック
     */
    public synchronized void open(String uri, String username, String password, SsePushClientCallback callback) {
        if (callback == null || uri == null) {
            throw new IllegalArgumentException("uri & callback must not be null.");
        }

        if (mEventMonitor.get() != null) {
            throw new IllegalStateException("Not idle state");
        }
        mLogger.info("open() uri=" + uri);

        mUri = uri;
        mUsername = username;
        mPassword = password;
        mClientCallback = callback;

        EventMonitor eventMonitor = new EventMonitor();
        mEventMonitor.set(eventMonitor);
        mEventMonitorExecutor.submit(eventMonitor);
    }

    /**
     * SSE Push サーバとの接続を切断する。
     */
    public synchronized void close() {
        mLogger.info("close()");

        EventMonitor eventMonitor = mEventMonitor.getAndSet(null);
        if (eventMonitor != null) {
            eventMonitor.cancel();
        }

        stopHeartbeatMonitoring();

        notifyClose();
    }

    private synchronized void notifyOpen() {
        mLogger.info("sse opened");
        if (mClientCallback != null) {
            mClientCallback.onOpen();
        }
    }

    private synchronized void notifyClose() {
        mLogger.info("sse closed");
        if (mClientCallback != null) {
            mClientCallback.onClose();
            mClientCallback = null;
        }
    }

    private synchronized void notifyError(int statusCode, String errorInfo) {
        mLogger.warning("sse error: " + errorInfo);
        if (mClientCallback != null) {
            mClientCallback.onError(statusCode, errorInfo);
        }
    }

    /**
     * 指定したイベントタイプの受信コールバックを登録する。
     *
     * @param event    イベントタイプ
     * @param callback コールバック
     */
    public void registerEvent(String event, SsePushEventCallback callback) {
        mRegisterEvent.put(event, callback);
    }

    /**
     * ハートビート間隔を設定する
     * @param heartbeatInterval ハートビート間隔。
     * @param timeUnit 時間単位
     * @since 4.0.1
     */
    public void setHeartbeatInterval(long heartbeatInterval, TimeUnit timeUnit) {
        if (heartbeatInterval < 0L) {
            throw new IllegalArgumentException("Negative heartbeat interval.");
        }

        mLogger.info(String.format("heartbeatInterval = %d [%s]", heartbeatInterval, timeUnit.toString()));

        /* ハートビート間隔は、内部的にはミリ秒で管理する */
        mHeartbeatInterval.set(timeUnit.toMillis(heartbeatInterval));
        startHeartbeatMonitoring();
    }

    /**
     * ハートビート監視を開始する
     */
    private void startHeartbeatMonitoring() {
        mLastHeartbeat.set(System.currentTimeMillis());

        HeartbeatMonitor heartbeatMonitor = new HeartbeatMonitor();

        mHeartbeatMonitorExecutor.submit(heartbeatMonitor);

        HeartbeatMonitor oldMonitor = mHeartbeatMonitor.getAndSet(heartbeatMonitor);
        if (oldMonitor != null) {
            oldMonitor.cancel();
        }
    }

    /**
     * ハートビート監視を停止する
     */
    private void stopHeartbeatMonitoring() {
        HeartbeatMonitor heartbeatMonitor = mHeartbeatMonitor.get();
        if (heartbeatMonitor != null) {
            heartbeatMonitor.cancel();
        }
    }

    /**
     * HTTP リクエスト用 WebTarget 作成
     *
     * @param uri URI
     * @param username Username
     * @param password Password
     * @return WebTarget
     */
    private WebTarget makeWebTarget(String uri, String username, String password) {
        Client client = mClientBuilderFactory.createBuilder().register(SseFeature.class).build();
        WebTarget target = client.target(uri);
        if (username != null && password != null) {
            target = target.register(HttpAuthenticationFeature.basic(username, password));
        }
        return target;
    }

    /**
     * 自動再接続用リトライタイマ
     */
    static class RetryTimer {
        int retryTime = 1;

        RetryTimer() {
            reset();
        }

        void reset() {
            retryTime = 1;
        }

        /**
         * リトライ値をインクリメントする。
         * リトライタイマ値は、'1','2','4','8','16','32',...'128','128'...(秒)とする。
         */
        void increment() {
            if (retryTime < 128) {
                retryTime *= 2;
            }
        }

        /**
         * 次回接続開始までsleepする。
         */
        void waitUntilReOpen() {
            try {
                Thread.sleep(retryTime * 1000L);
            } catch (InterruptedException e) {
                reset();
            }
            increment();
        }
    }

    /**
     * Throwableを再帰的にprintする
     */
    private static void printThrowable(Throwable e) {
        StringBuilder sb = new StringBuilder(e.toString());

        while (e.getCause() != null) {
            e = e.getCause();

            sb.append("\n|--Cause: ").append(e.toString());
        }

        mLogger.warning(sb.toString());
    }

    /**
     * 最終ハートビート時刻を確認する。
     * ハートビートが lost している場合は、onHeartbeatLost コールバックを呼び出す。
     */
    private void checkLastHeartbeat() {
        if (mEventMonitor.get() == null) {
            return;
        }

        mLogger.info("checkLastHeartbeat()");

        if (mLastHeartbeat.get() == Long.MIN_VALUE) {
            return;
        }

        if (mHeartbeatInterval.get() == 0L) {
            return;
        }

        if (mLastHeartbeat.get() + (mHeartbeatInterval.get() * 2) < System.currentTimeMillis()) {
            mLogger.warning("sse heartbeat lost");
            mClientCallback.onHeartbeatLost();
        } else {
            mLogger.fine("sse heartbeat ok");
        }
    }

    private boolean isHeartbeat(InboundEvent inboundEvent) {
        return "".equals(inboundEvent.getComment())
                && inboundEvent.getName() == null
                && inboundEvent.isEmpty();
    }

    /**
     * Event Monitor。
     * SSE Push サーバへの接続とメッセージポーリングを行う。
     * {@link #cancel()}が呼ばれるまで実行を継続する。
     */
    private class EventMonitor implements Runnable {
        private final AtomicBoolean isCanceled = new AtomicBoolean(false);
        private final AtomicReference<Thread> myThread = new AtomicReference<>();

        private final AtomicReference<EventInput> eventInput = new AtomicReference<>();

        @Override
        public void run() {
            mLogger.info("EventMonitor.run(): started.");
            myThread.set(Thread.currentThread());

            while (!isCanceled.get()) {
                EventInput conn = connect();
                if (conn != null) {
                    eventInput.set(conn);
                    notifyOpen();
                    startHeartbeatMonitoring();

                    poll();
                }
            }

            mLogger.info("EventMonitor.run(): finish.");
        }

        private EventInput connect() {
            mLogger.info("EventMonitor.connect(): started.");

            RetryTimer retryTimer = new RetryTimer();

            while (!isCanceled.get()) {
                EventInput eventInput = mConnector.connect();
                if (eventInput != null) {
                    return eventInput;
                }

                if (isCanceled.get()) break;

                mLogger.info("Waiting for reconnect.");
                retryTimer.waitUntilReOpen();
            }

            mLogger.fine("Give up connecting. EventMonitor has been canceled.");
            return null;
        }

        private void poll() {
            try {
                mLogger.info("EventMonitor.poll(): started.");

                while (!isCanceled.get()) {
                    InboundEvent inboundEvent;
                    try {
                        inboundEvent = eventInput.get().read();
                    } catch (RuntimeException e) {
                        if (!isCanceled.get()) {
                            printThrowable(e);
                            notifyError(-1, "sse polling error: " + e.getMessage());
                        }
                        break;
                    }

                    if (inboundEvent == null) {
                        // connection has been closed
                        notifyError(-1, "connection has been closed.");
                        break;
                    }

                    if (isHeartbeat(inboundEvent)) {
                        mLogger.finer("sse heartbeat received.");
                        mLastHeartbeat.set(System.currentTimeMillis());
                        continue;
                    }

                    String event = inboundEvent.getName();

                    if (event == null) {
                        mLogger.info("sse event: no name");
                        continue;
                    }

                    SsePushEventCallback callback = mRegisterEvent.get(event);
                    if (callback == null) {
                        mLogger.info("sse event: no matching event type: " + event);
                        continue;
                    }

                    SsePushEvent sse = new SsePushEvent();
                    sse.setId(inboundEvent.getId());
                    sse.setEvent(inboundEvent.getName());
                    sse.setData(inboundEvent.readData(String.class));
                    sse.setOrigin(mUri);
                    callback.onMessage(sse);
                }
            } finally {
                mLogger.info("EventMonitor.poll() finished.");
                if (!eventInput.get().isClosed()) {
                    eventInput.get().close();
                }
            }
        }

        /**
         * 実行をキャンセルする
         */
        private void cancel() {
            isCanceled.set(true);

            final EventInput ev = this.eventInput.getAndSet(null);
            if (ev != null) {
                mCloseExecutor.submit(new Runnable() {
                    @Override
                    public void run() {
                        ev.close();
                    }
                });
            }

            Thread t = myThread.get();
            if (t != null) {
                t.interrupt();
            }
        }
    }

    /**
     * ハートビートモニタ
     */
    private class HeartbeatMonitor implements Runnable {
        private final AtomicReference<Thread> myThread = new AtomicReference<>();
        private final AtomicBoolean isCanceled = new AtomicBoolean(false);

        @Override
        public void run() {
            myThread.set(Thread.currentThread());

            if (mHeartbeatInterval.get() == 0L) {
                mLogger.info("Heartbeat interval is not set.");
                return;
            }

            mLogger.info("Start heartbeat monitor. interval = " + mHeartbeatInterval.get());

            while (!isCanceled.get()) {
                checkLastHeartbeat();

                try {
                    long sleep = mHeartbeatInterval.get() * 2;
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    mLogger.info("Stop heartbeat monitor.");
                }
            }
        }

        /**
         * 実行をキャンセルする
         */
        private void cancel() {
            isCanceled.set(true);
            Thread t = myThread.get();
            if (t != null) {
                t.interrupt();
            }
        }
    }
}
