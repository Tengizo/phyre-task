package com.phyre.websocketClient.handshake;

import com.phyre.websocketClient.Constants;
import com.phyre.websocketClient.exceptions.IncompleteHandshakeException;
import com.phyre.websocketClient.exceptions.InvalidHandshakeException;
import com.phyre.websocketClient.util.Base64;
import com.phyre.websocketClient.util.ByteBufferUtils;
import com.phyre.websocketClient.util.Charsetfunctions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Locale;


public class Handshaker {
    private final Logger log = LoggerFactory.getLogger(Handshaker.class);

    private final SecureRandom reusableRandom = new SecureRandom();

    public ClientHandshake createClientHandshake(String host, String resourceDescriptor) {
        ClientHandshake handshake = new ClientHandshake();
        handshake.setResourceDescriptor(resourceDescriptor);
        handshake.put("Host", host);
        return addHeadersToClientHandshake(handshake);
    }

    public ByteBuffer toByteBuffer(ClientHandshake handshake) {
        StringBuilder bui = new StringBuilder(100);
        bui
                .append("GET ")
                .append(handshake.getResourceDescriptor())
                .append(" HTTP/1.1")
                .append("\r\n");
        Iterator<String> it = handshake.iterateHttpFields();
        while (it.hasNext()) {
            String fieldName = it.next();
            String fieldValue = handshake.getFieldValue(fieldName);
            bui.append(fieldName);
            bui.append(": ");
            bui.append(fieldValue);
            bui.append("\r\n");
        }
        bui.append("\r\n");
        byte[] httpHeader = Charsetfunctions.asciiBytes(bui.toString());
        ByteBuffer bytebuffer = ByteBuffer.allocate(httpHeader.length);
        bytebuffer.put(httpHeader);

        bytebuffer.flip();
        return bytebuffer;
    }

    public ServerHandshake validateServerHandshake(ClientHandshake clientHandshake, ByteBuffer socketBufferNew) {
        socketBufferNew.mark();
        HandshakeState handshakestate;
        try {
            ServerHandshake handshake = translateHandshake(socketBufferNew);
            handshakestate = validateHandshakeResponse(clientHandshake, handshake);
            handshake.setState(handshakestate);
            return handshake;
        } catch (InvalidHandshakeException e) {
            log.trace("Closing due to invalid handshake", e);
            return new ServerHandshake(HandshakeState.NOT_MATCHED);
        }
    }

    private HandshakeState validateHandshakeResponse(ClientHandshake request, ServerHandshake response) {
        if (!this.basicValidation(response)) {
            this.log.trace("acceptHandshakeAsClient - Missing/wrong upgrade or connection in handshake.");
            return HandshakeState.NOT_MATCHED;
        } else if (request.hasFieldValue("Sec-WebSocket-Key") && response.hasFieldValue("Sec-WebSocket-Accept")) {
            return checkSecurityChallenge(request, response);
        } else {
            this.log.trace("acceptHandshakeAsClient - Missing Sec-WebSocket-Key or Sec-WebSocket-Accept");
            return HandshakeState.NOT_MATCHED;
        }
    }

    private HandshakeState checkSecurityChallenge(ClientHandshake request, ServerHandshake response) {
        String secKeyAnswer = response.getFieldValue("Sec-WebSocket-Accept");
        String secKeyChallenge = request.getFieldValue("Sec-WebSocket-Key");
        secKeyChallenge = this.generateFinalKey(secKeyChallenge);
        if (!secKeyChallenge.equals(secKeyAnswer)) {
            return HandshakeState.NOT_MATCHED;
        }
        return HandshakeState.MATCHED;
    }

    private String generateFinalKey(String in) {
        String secKey = in.trim();
        String acc = secKey + Constants.GUID;
        MessageDigest sh1;
        try {
            sh1 = MessageDigest.getInstance("SHA1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return Base64.encodeBytes(sh1.digest(acc.getBytes()));
    }

    protected boolean basicValidation(ServerHandshake handshake) {
        return handshake.getFieldValue("Upgrade").equalsIgnoreCase("websocket")
                && handshake.getFieldValue("Connection").toLowerCase(Locale.ENGLISH).contains("upgrade");
    }

    public static ServerHandshake translateHandshake(ByteBuffer buf) throws InvalidHandshakeException {
        ServerHandshake handshake;

        String line = ByteBufferUtils.readStringLine(buf);
        if (line == null) {
            throw new IncompleteHandshakeException(buf.capacity() + 128);
        }

        String[] firstLineTokens = line.split(" ", 3);// eg. HTTP/1.1 101 Switching the Protocols
        if (firstLineTokens.length != 3) {
            throw new InvalidHandshakeException();
        }
        handshake = translateHandshakeHttpClient(firstLineTokens, line);

        line = ByteBufferUtils.readStringLine(buf);
        while (line != null && line.length() > 0) {
            String[] pair = line.split(":", 2);
            if (pair.length != 2) {
                throw new InvalidHandshakeException("not an http header");
            }
            // If the handshake contains already a specific key, append the new value
            if (handshake.hasFieldValue(pair[0])) {
                handshake.put(pair[0],
                        handshake.getFieldValue(pair[0]) + "; " + pair[1].replaceFirst("^ +", ""));
            } else {
                handshake.put(pair[0], pair[1].replaceFirst("^ +", ""));
            }
            line = ByteBufferUtils.readStringLine(buf);
        }
        if (line == null) {
            throw new IncompleteHandshakeException();
        }
        return handshake;
    }

    private static ServerHandshake translateHandshakeHttpClient(String[] firstLineTokens,
                                                                String line) throws InvalidHandshakeException {
        // translating/parsing the response from the SERVER
        if (!"101".equals(firstLineTokens[1])) {
            throw new InvalidHandshakeException(String
                    .format("Invalid status code received: %s Status line: %s", firstLineTokens[1], line));
        }
        if (!"HTTP/1.1".equalsIgnoreCase(firstLineTokens[0])) {
            throw new InvalidHandshakeException(String
                    .format("Invalid status line received: %s Status line: %s", firstLineTokens[0], line));
        }
        ServerHandshake handshake = new ServerHandshake();
        handshake.setHttpStatus(Short.parseShort(firstLineTokens[1]));
        handshake.setHttpStatusMessage(firstLineTokens[2]);
        return handshake;
    }

    private ClientHandshake addHeadersToClientHandshake(ClientHandshake handshake) {
        handshake.put(Constants.UPGRADE, "websocket");
        handshake.put(Constants.CONNECTION, Constants.UPGRADE); // to respond to a Connection keep alives
        byte[] random = new byte[16];
        reusableRandom.nextBytes(random);
        handshake.put(Constants.SEC_WEB_SOCKET_KEY, Base64.encodeBytes(random));
        handshake.put(Constants.SEC_WEB_SOCKET_VERSION, Constants.SEC_WEB_SOCKET_VERSION_VALUE);// overwriting the previous

        return handshake;
    }
}
