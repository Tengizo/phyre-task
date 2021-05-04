package com.phyre;

import com.phyre.exchange.Bitfinex;
import com.phyre.exchange.Exchange;
import com.phyre.exchange.Kraken;
import com.phyre.exchange.OrderBook;

import java.util.Arrays;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        List<Exchange> exchangeList = Arrays.asList(new Bitfinex(), new Kraken());
        OrderBook orderBook = new OrderBook(exchangeList);
        List<Thread> threads = orderBook.start();

        threads.forEach(t -> { //wait for exchange threads
            try {
                t.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });

    }

}
