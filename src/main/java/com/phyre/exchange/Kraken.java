package com.phyre.exchange;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigDecimal;

public class Kraken extends Exchange {
    private final Gson gson = new Gson();


    @Override
    protected void onUpdateMessage(String input) {
        try {
            JsonElement element = gson.fromJson(input, JsonElement.class);
            if (element.isJsonObject()) {
                return;
            }
            JsonArray updates = element.getAsJsonArray();
            updates.forEach(update -> {
                if (update.isJsonObject()) {
                    JsonObject updateObj = update.getAsJsonObject();
                    updateObj.entrySet().forEach(entry -> {
                        switch (entry.getKey()) {
                            case "as":
                            case "a":
                                updateAsks(entry.getValue().getAsJsonArray());
                                break;
                            case "bs":
                            case "b":
                                updateBids(entry.getValue().getAsJsonArray());
                                break;

                        }
                    });

                }
            });
        } catch (Exception e) {
            System.out.println("error while parsing update message: ");
            e.printStackTrace();
        }
    }


    private void updateAsks(JsonArray updates) {
        updates.forEach(update -> updateAsk(update.getAsJsonArray()));
    }

    private void updateBids(JsonArray updates) {
        updates.forEach(update -> updateBid(update.getAsJsonArray()));
    }

    private void updateAsk(JsonArray ask) {
        BigDecimal price = ask.get(0).getAsBigDecimal();
        BigDecimal amount = ask.get(1).getAsBigDecimal();
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            removeAsk(price);
        } else {
            updateAsks(price, amount);
        }
    }

    private void updateBid(JsonArray ask) {
        BigDecimal price = ask.get(0).getAsBigDecimal();
        BigDecimal amount = ask.get(1).getAsBigDecimal();
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            removeBid(price);
        } else {
            updateBids(price, amount);
        }
    }

    @Override
    protected String getSubscribeMessage() {
        return "{\n" +
                "  \"event\": \"subscribe\",\n" +
                "  \"pair\": [\n" +
                "    \"XBT/USD\"\n" +
                "  ],\n" +
                "  \"subscription\": {\n" +
                "    \"name\": \"book\"\n" +
                "  }\n" +
                "}";
    }

    @Override
    protected String getUri() {
        return "wss://ws.kraken.com";
    }

    @Override
    public String toString() {
        return "Kraken{}";
    }
}
