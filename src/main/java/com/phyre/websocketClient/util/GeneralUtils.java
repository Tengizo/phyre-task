package com.phyre.websocketClient.util;

import java.net.URI;

public class GeneralUtils {
    public static String getUriPath(URI uri) {
        String path;

        String part1 = uri.getRawPath();
        String part2 = uri.getRawQuery();
        if (part1 == null || part1.length() == 0) {
            path = "/";
        } else {
            path = part1;
        }
        if (part2 != null) {
            path += '?' + part2;
        }
        return path;
    }
}
