/*
 * NEC Mobile Backend Platform: SSE Push Client
 *
 * Copyright (c) 2013-2018, NEC Corporation.
 */
package com.nec.push.sse;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * SSE Push 受信メッセージクラス。
 *
 * SSE Push サーバから受信したメッセージを格納する。
 *
 * @since 4.0.0
 */
@Getter
@Setter(AccessLevel.PACKAGE)
public class SsePushEvent {
    // イベントID
    /**
     * -- GETTER --
     * イベントIDを取得する。
     */
    private String id = null;

    // イベントタイプ
    /**
     * -- GETTER --
     * イベントタイプを取得する。
     */
    private String event = null;

    // データ
    /**
     * -- GETTER --
     * データを取得する。
     */
    private String data = null;

    // SSE Push サーバのURI
    /**
     * -- GETTER --
     * SSE Push サーバのURIを取得する。
     */
    private String origin = null;
}
