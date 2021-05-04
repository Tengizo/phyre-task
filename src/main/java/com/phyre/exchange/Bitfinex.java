package com.phyre.exchange;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.math.BigDecimal;

public class Bitfinex extends Exchange {
    private final Gson gson = new Gson();

    @Override
    protected void onUpdateMessage(String input) {
        try {
            JsonElement element = gson.fromJson(input, JsonElement.class);
            if (element.isJsonObject()) {
                return;
            }

            JsonArray updates = element.getAsJsonArray();
            if (updates.get(1).isJsonPrimitive() && "hb".equals(updates.get(1).getAsString())) { //skipping heartbeat
                return;
            }
            JsonArray values = updates.get(1).getAsJsonArray();
            if (values.get(0).isJsonArray()) { //is snapshot
                for (int i = 0; i < values.size(); i++) {
                    updateOne(values.get(i).getAsJsonArray()); // first element is channel id.
                }
            } else {
                updateOne(values);
            }
        } catch (Exception e) {
            System.out.println("error while parsing update message: ");
            e.printStackTrace();
        }
    }


    private void updateOne(JsonArray update) {
        BigDecimal price = update.get(0).getAsBigDecimal();
        int count = update.get(1).getAsInt();
        BigDecimal amount = update.get(2).getAsBigDecimal();
        if (amount.compareTo(BigDecimal.ZERO) > 0) {
            if (count > 0) {
                updateBids(price, amount.abs());
            } else if (count == 0) {
                removeBid(price);
            }
        } else if (amount.compareTo(BigDecimal.ZERO) < 0) {
            if (count > 0) {
                updateAsks(price, amount.abs());
            } else if (count == 0) {
                removeAsk(price);
            }
        }
    }

    @Override
    protected String getSubscribeMessage() {
        return "{ \"event\": \"subscribe\", \"channel\": \"book\", \"symbol\": \"tBTCUSD\" }";
    }

    @Override
    protected String getUri() {
        return "wss://api-pub.bitfinex.com/ws/2";
    }


    @Override
    public String toString() {
        return "Bitfinex";
    }


}
