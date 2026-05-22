package com.acard.acard.net;

/**
 * 全局STOMP连接状态
 */
public enum ConnectionState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}