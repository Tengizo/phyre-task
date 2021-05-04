package com.phyre.exchange;

import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * OrderBook takes exchanges and keeps track of all of them
 */
public class OrderBook {
    private final List<Exchange> exchanges;
    private TreeMap<BigDecimal, BigDecimal> bids = new TreeMap<>();
    private TreeMap<BigDecimal, BigDecimal> asks = new TreeMap<>();

    public OrderBook(List<Exchange> exchanges) {
        this.exchanges = exchanges;
        initEmptyBidAsk();
    }

    private Comparator<BigDecimal> bidComparator() {
        return (p1, p2) -> p1.compareTo(p2) * (-1);
    }

    private Comparator<BigDecimal> askComparator() {
        return (p1, p2) -> p1.compareTo(p2) * (-1);
    }

    private void initEmptyBidAsk() {
        bids = new TreeMap<>(bidComparator());
        asks = new TreeMap<>(askComparator());
    }


    public List<Thread> start() {
        return exchanges.stream().map(exchange -> {
            exchange.onUpdate(this::aggregate);
            try {
                return exchange.start();
            } catch (URISyntaxException e) {
                System.out.println("error on exchange: ");
                e.printStackTrace();
                return null;
            }
        }).collect(Collectors.toList());
    }

    public void stop() {
        exchanges.forEach(Exchange::stop);
    }

    private synchronized void aggregate() {
        initEmptyBidAsk();
        for (Exchange exchange : exchanges) {
            exchange.getBidReadLock().lock();
            for (BigDecimal price : exchange.getBids().keySet()) {
                if (this.bids.containsKey(price)) {
                    this.bids.put(price, this.bids.get(price).add(exchange.getBids().get(price)));
                } else {
                    this.bids.put(price, exchange.getBids().get(price));
                }
            }
            exchange.getBidReadLock().unlock();
            exchange.getAskReadLock().lock();
            for (BigDecimal price : exchange.getAsks().keySet()) {
                if (this.asks.containsKey(price)) {
                    this.asks.put(price, this.asks.get(price).add(exchange.getAsks().get(price)));
                } else {
                    this.asks.put(price, exchange.getAsks().get(price));
                }
            }
            exchange.getAskReadLock().unlock();
        }
        if (this.bids.size() > 0 && this.asks.size() > 0)
            print();
    }

    private void print() {

        System.out.println(this);
        System.out.println("***************************");
    }


    @Override
    public String toString() {
        return "Exchange{ \n" +
                "asks: \n" + treeMapToString(asks) +
                "\n bids: \n" + treeMapToString(bids) +
                "\n Best bid: " + entryToString(bids.firstKey(), bids.firstEntry().getValue()) + "\n" +
                "Best ask: " + entryToString(asks.lastKey(), asks.lastEntry().getValue()) +
                "\n } "
                ;
    }

    String treeMapToString(TreeMap<BigDecimal, BigDecimal> treeMap) {
        StringBuilder treeMapString = new StringBuilder();
        treeMapString.append("[ \n ");
        treeMap.forEach((price, amount) -> treeMapString
                .append(entryToString(price, amount))
                .append("\n"));
        treeMapString.append("\n ]");
        return treeMapString.toString();
    }

    String entryToString(BigDecimal price, BigDecimal amount) {

        return "  [" + price + ", " + amount + " ]";
    }
}
