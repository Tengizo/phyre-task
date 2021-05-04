package com.phyre.exchange;


import com.phyre.websocketClient.WebsocketClient;

import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.util.Comparator;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

public abstract class Exchange {
    private WebsocketClient wsClient;
    private Runnable onUpdate;
    private final SortedMap<BigDecimal, BigDecimal> bids;
    private final SortedMap<BigDecimal, BigDecimal> asks;

    private final ReentrantLock bidLock = new ReentrantLock();
    private final ReentrantLock askLock = new ReentrantLock();

    protected Exchange() {
        bids = new TreeMap<>(bidComparator());
        asks = new TreeMap<>(askComparator());
    }


    Comparator<BigDecimal> bidComparator() {
        return (p1, p2) -> p1.compareTo(p2) * (-1);
    }

    Comparator<BigDecimal> askComparator() {
        return (p1, p2) -> p1.compareTo(p2) * (-1);
    }

    public Thread start() throws URISyntaxException {
        this.wsClient = new WebsocketClient(getUri());
        wsClient.onOpen((clientHandshake, serverHandshake) -> {
            System.out.println(this + "Connected to websocket server, subscribing to books");
            wsClient.send(getSubscribeMessage());
        });
        wsClient.onMessage(this::onUpdateMessage);
        wsClient.onError(err -> {
            System.out.println("Error occurred on socket connection: ");
            err.printStackTrace();
        });
        wsClient.onClose(reason -> System.out.println("Closing socket: " + reason));
        return wsClient.connect();
    }

    public void stop() {
        if (wsClient != null)
            wsClient.close();
    }


    protected void updateBids(BigDecimal price, BigDecimal amount) {
        this.bidLock.lock();
        bids.put(price, amount);
        this.bidLock.unlock();
        publishUpdate();
    }

    protected void updateAsks(BigDecimal price, BigDecimal amount) {
        this.askLock.lock();
        asks.put(price, amount);
        this.askLock.unlock();
        publishUpdate();
    }

    protected void removeBid(BigDecimal price) {
        this.bidLock.lock();
        bids.remove(price);
        this.bidLock.unlock();
        publishUpdate();
    }

    protected void removeAsk(BigDecimal price) {
        this.askLock.lock();
        asks.remove(price);
        this.askLock.unlock();
        publishUpdate();
    }

    public ReentrantLock getBidReadLock() {
        return bidLock;
    }

    public ReentrantLock getAskReadLock() {
        return askLock;
    }

    public SortedMap<BigDecimal, BigDecimal> getBids() {
        return bids;
    }

    public SortedMap<BigDecimal, BigDecimal> getAsks() {
        return asks;
    }

    private void publishUpdate() {
        if (this.onUpdate != null) {
            this.onUpdate.run();
        }

    }

    public void onUpdate(Runnable onUpdate) {
        this.onUpdate = onUpdate;
    }

    protected abstract void onUpdateMessage(String input);


    protected abstract String getSubscribeMessage();

    protected abstract String getUri();

}
