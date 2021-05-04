package com.phyre.websocketClient;

import lombok.extern.slf4j.Slf4j;
import com.phyre.websocketClient.exceptions.InvalidDataException;
import com.phyre.websocketClient.exceptions.InvalidHandshakeException;
import com.phyre.websocketClient.exceptions.LimitExceededException;
import com.phyre.websocketClient.exceptions.WebsocketNotConnectedException;
import com.phyre.websocketClient.framing.Framedata;
import com.phyre.websocketClient.handshake.ClientHandshake;
import com.phyre.websocketClient.handshake.Handshaker;
import com.phyre.websocketClient.handshake.ServerHandshake;
import com.phyre.websocketClient.util.GeneralUtils;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
public class WebsocketClient implements Runnable {
    private final URI uri;
    private final Integer port = null;
    private Socket socket = null;
    private OutputStream oStream;
    private InputStream iStream;
    private final Draft_6455 draft = new Draft_6455();
    private final Handshaker handshaker = new Handshaker();
    private ClientHandshake clientHandshake;
    private ReadyState readyState = ReadyState.NOT_YET_CONNECTED;
    private final Object synchronizeWriteObject = new Object();
    private long lastPong = System.nanoTime();
    private Thread connectReadThread;
    public static final int RCVBUF = 16384;
    private BiConsumer<ClientHandshake, ServerHandshake> onOpen;
    private Consumer<String> onTextMessage;
    private Consumer<ByteBuffer> onBlobMessage;
    private Consumer<Exception> onError;
    private Consumer<String> onClose;


    public WebsocketClient(String uri) throws URISyntaxException {
        this.uri = new URI(uri);
    }

    public Thread connect() {
        if (this.connectReadThread != null) {
            throw new IllegalStateException("WebSocketClient objects are not reuseable");
        } else {
            log.info("Socket client connected");
            this.connectReadThread = new Thread(this);
            this.connectReadThread.setName("WebSocketConnectReadThread-" + this.connectReadThread.getId());
            this.connectReadThread.start();
            return this.connectReadThread;
        }
    }


    @Override
    public void run() {
        try {
            prepareSocket();
        } catch (Exception e) {
            log.error("error during websocket connection");
            onError(e);
            onClose(e.getMessage());
        }
        readIncoming();
    }


    private void prepareSocket() throws NoSuchAlgorithmException, IOException, KeyManagementException {
        socket = new Socket(Proxy.NO_PROXY);
        InetSocketAddress addr = new InetSocketAddress(InetAddress.getByName(uri.getHost()), getPort());
        socket.connect(addr, 0);

        if (isSSL()) {
            upgradeSocketToSSL();
        }
        iStream = socket.getInputStream();
        oStream = socket.getOutputStream();
        sendHandshake();

    }

    public void send(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Cannot send 'null' data to a WebSocketImpl.");
        }
        send(draft.createFrames(text));
    }

    private void send(Collection<Framedata> frames) {
        if (!readyState.isOpen()) {
            throw new WebsocketNotConnectedException();
        }
        if (frames == null) {
            throw new IllegalArgumentException();
        }
        List<ByteBuffer> outgoingFrames = frames.stream()
                .map(draft::createBinaryFrame)
                .collect(Collectors.toList());
        for (Framedata f : frames) {
            log.trace("send frame: {}", f);
            outgoingFrames.add(draft.createBinaryFrame(f));
        }
        write(outgoingFrames);
    }

    private void write(List<ByteBuffer> bufs) {
        synchronized (synchronizeWriteObject) {
            for (ByteBuffer b : bufs) {
                writeData(b);
            }
        }
    }

    private void readIncoming() {
        byte[] rawbuffer = new byte[RCVBUF];
        int readBytes;
        try {
            while (!readyState.isClosing() && !readyState.isClosed() && (readBytes = iStream.read(rawbuffer)) != -1) {
                decode(ByteBuffer.wrap(rawbuffer, 0, readBytes));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Error while closing");
            onError(e);
        } catch (InvalidHandshakeException e) {
            e.printStackTrace();
        }
        close("Client action");
        connectReadThread = null;
    }


    private void decode(ByteBuffer socketBuffer) throws InvalidHandshakeException {
        assert (socketBuffer.hasRemaining());
        if (readyState != ReadyState.NOT_YET_CONNECTED) {
            if (readyState == ReadyState.OPEN) {
                decodeFrames(socketBuffer);
            }
        } else { //isHandshake
            ServerHandshake serverHandshake = handshaker.validateServerHandshake(clientHandshake, socketBuffer);
            if (serverHandshake.matched()) {
                this.readyState = ReadyState.OPEN;
                this.onWebsocketOpen(clientHandshake, serverHandshake);
            } else {
                throw new InvalidHandshakeException();
            }
        }
    }

    private void decodeFrames(ByteBuffer socketBuffer) {
        List<Framedata> frames;
        try {
            frames = draft.translateFrame(socketBuffer);
            for (Framedata f : frames) {
                log.trace("matched frame: {}", f);
                draft.processFrame(this, f);
            }
        } catch (LimitExceededException e) {
            if (e.getLimit() == Integer.MAX_VALUE) {
                log.error("Closing due to invalid size of frame", e);
                onError(e);
            }
            this.readyState = ReadyState.CLOSED;
        } catch (InvalidDataException e) {
            log.error("Closing due to invalid data in frame", e);
            this.readyState = ReadyState.CLOSED;
        }
    }

    public void updateLastPong() {
        this.lastPong = System.nanoTime();
    }

    private void upgradeSocketToSSL()
            throws NoSuchAlgorithmException, KeyManagementException, IOException {
        SSLSocketFactory factory;
        SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
        sslContext.init(null, null, null);
        factory = sslContext.getSocketFactory();
        socket = factory.createSocket(socket, uri.getHost(), getPort(), true);
    }

    private void sendHandshake() {
        String path = GeneralUtils.getUriPath(uri);
        String host = uri.getHost() + (isDefaultPort() ? ":" + port : "");

        this.clientHandshake = handshaker.createClientHandshake(host, path);
        writeData(handshaker.toByteBuffer(this.clientHandshake));
    }


    private void writeData(ByteBuffer buffer) {
        try {
            oStream.write(buffer.array(), 0, buffer.limit());
            oStream.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void close() {
        this.close("Stopped by client");
    }
    protected void close(String reason) {
        try {
            this.readyState = ReadyState.CLOSED;
            closeSocket();
            draft.reset();
            oStream.close();
            iStream.close();
            onClose(reason);
        } catch (Exception e) {
            onError(e);
        }
    }

    private void closeSocket() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ex) {
            onError(ex);
        }
    }

    public int getPort() {
        if (isSSL()) {
            return Constants.DEFAULT_WSS_PORT;
        } else {
            return Constants.DEFAULT_PORT;
        }
    }

    public long getLastPong() {
        return this.lastPong;
    }

    public ReadyState getReadyState() {
        return readyState;
    }

    public boolean isDefaultPort() {
        return getPort() != Constants.DEFAULT_PORT && getPort() != Constants.DEFAULT_WSS_PORT;
    }

    public boolean isSSL() {
        return "wss".equals(uri.getScheme());
    }

    public void onOpen(BiConsumer<ClientHandshake, ServerHandshake> onOpen) {
        this.onOpen = onOpen;
    }

    public void onBlobMessage(Consumer<ByteBuffer> onMessage) {
        this.onBlobMessage = onMessage;
    }

    public void onMessage(Consumer<String> onMessage) {
        this.onTextMessage = onMessage;
    }

    public void onError(Consumer<Exception> onError) {
        this.onError = onError;
    }

    public void onClose(Consumer<String> onClose) {
        this.onClose = onClose;
    }

    void onWebsocketOpen(ClientHandshake request, ServerHandshake response) {
        this.onOpen.accept(request, response);
    }

    void onWebsocketMessage(String message) {
        this.onTextMessage.accept(message);
    }

    void onWebsocketMessage(ByteBuffer blob) {
        this.onBlobMessage.accept(blob);
    }

    void onError(Exception exception) {
        this.onError.accept(exception);
    }

    void onClose(String reason) {
        this.onClose.accept(reason);
    }
}
