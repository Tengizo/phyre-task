package com.phyre.websocketClient.handshake;

import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class ClientHandshake extends Handshake {

    private String resourceDescriptor = "*";
}
