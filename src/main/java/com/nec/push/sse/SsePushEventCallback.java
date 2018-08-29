/*
 * NEC Mobile Backend Platform: SSE Push Client
 *
 * Copyright (c) 2013-2018, NEC Corporation.
 */
package com.nec.push.sse;

/**
 * SSE Push サーバとの通信用コールバック。
 * @since 4.0.0
 */
public interface SsePushEventCallback {
    /**
     * Push メッセージを受信した場合に呼び出される。<br>
     * @param event 受信イベント。
     */
    void onMessage(SsePushEvent event);
}
