package com.phyre.websocketClient;

/**
 * Enum which represents the state a websocket may be in
 */
public enum ReadyState {
    NOT_YET_CONNECTED, OPEN, CLOSING, CLOSED;

    public boolean isOpen() {
        return this == OPEN;
    }

    public boolean isClosing() {
        return this == CLOSING;
    }

    public boolean isClosed() {
        return this == CLOSED;
    }
}