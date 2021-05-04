package com.phyre.websocketClient.handshake;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
public class ServerHandshake extends Handshake {
    private short httpStatus;
    private String httpStatusMessage;
    private HandshakeState state;

    public ServerHandshake(HandshakeState state) {
        this.state = state;
    }

    public boolean matched() {
        return state == HandshakeState.MATCHED;
    }
}
