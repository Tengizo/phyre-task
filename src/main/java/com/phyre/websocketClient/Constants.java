package com.phyre.websocketClient;

public interface Constants {
    /**
     * Default  port
     */
    int DEFAULT_PORT = 80;
    /**
     * Default ssl port
     */
    int DEFAULT_WSS_PORT = 443;

    /**
     * Handshake specific field for the upgrade
     */
    String UPGRADE = "Upgrade";

    /**
     * Handshake specific field for the connection
     */
    String CONNECTION = "Connection";
    /**
     * Handshake specific field for the key
     */
    String SEC_WEB_SOCKET_KEY = "Sec-WebSocket-Key";
    String SEC_WEB_SOCKET_EXTENSIONS = "Sec-WebSocket-Extensions";
    String SEC_WEB_SOCKET_PROTOCOLS = "Sec-WebSocket-Protocol";
    String SEC_WEB_SOCKET_VERSION = "Sec-WebSocket-Version";
    String SEC_WEB_SOCKET_VERSION_VALUE = "13";

    String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
}
