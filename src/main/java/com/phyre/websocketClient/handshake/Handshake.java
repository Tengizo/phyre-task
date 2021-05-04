package com.phyre.websocketClient.handshake;

import java.util.Collections;
import java.util.Iterator;
import java.util.TreeMap;

public abstract class Handshake {
    private byte[] content;

    /**
     * Attribute for the http fields and values
     */
    private final TreeMap<String, String> map;

    /**
     * Constructor for handshake implementation
     */
    public Handshake() {
        map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    }

    public Iterator<String> iterateHttpFields() {
        return Collections.unmodifiableSet(map.keySet()).iterator();// Safety first
    }

    public String getFieldValue(String name) {
        String s = map.get(name);
        if (s == null) {
            return "";
        }
        return s;
    }

    
    public byte[] getContent() {
        return content;
    }

    
    public void setContent(byte[] content) {
        this.content = content;
    }

    
    public void put(String name, String value) {
        map.put(name, value);
    }

    
    public boolean hasFieldValue(String name) {
        return map.containsKey(name);
    }
}
