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
public interface SsePushClientCallback {
    /**
     * SSE Push サーバとの接続に成功した場合に呼び出される。<br>
     */
    void onOpen();

    /**
     * SSE Push サーバとの接続がクローズされた場合に呼び出される。<br>
     */
    void onClose();

    /**
     * エラーが発生した場合に呼び出される。<br>
     * @param statusCode ステータスコード。
     * @param errorInfo エラー理由。
     */
    void onError(int statusCode, String errorInfo);

    /**
     * ハートビート喪失時に呼び出される
     */
    void onHeartbeatLost();
}
