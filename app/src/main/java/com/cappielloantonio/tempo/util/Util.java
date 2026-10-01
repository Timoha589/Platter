package com.cappielloantonio.tempo.util;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

public class Util {
    public static <T> Predicate<T> distinctByKey(Function<? super T, Object> keyExtractor) {
        try {
            Map<Object, Boolean> uniqueMap = new ConcurrentHashMap<>();
            return t -> uniqueMap.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
        } catch (NullPointerException exception) {
            return null;
        }
    }

    public static String toPascalCase(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }

        StringBuilder pascalCase = new StringBuilder();

        char newChar;
        boolean toUpper = false;
        char[] charArray = name.toCharArray();

        for (int ctr = 0; ctr <= charArray.length - 1; ctr++) {
            if (ctr == 0) {
                newChar = Character.toUpperCase(charArray[ctr]);
                pascalCase = new StringBuilder(Character.toString(newChar));
                continue;
            }

            if (charArray[ctr] == '_') {
                toUpper = true;
                continue;
            }

            if (toUpper) {
                newChar = Character.toUpperCase(charArray[ctr]);
                pascalCase.append(newChar);
                toUpper = false;
                continue;
            }

            pascalCase.append(charArray[ctr]);
        }

        return pascalCase.toString();
    }

    public static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException ex) {
            return value;
        }
    }

    /**
     * Appends {@code key=value} to a Subsonic query string, URL-encoding the
     * value and emitting the leading "?" for the first parameter that is
     * actually present. Everything the server sends back to us — a cleartext
     * password, a username, an item id — can contain characters that change
     * the meaning of a URL, so nothing may be spliced in raw.
     */
    public static void appendQueryParam(StringBuilder query, String key, String value) {
        if (value == null) return;

        query.append(query.length() == 0 ? '?' : '&')
                .append(key)
                .append('=')
                .append(encode(value));
    }

    /**
     * The authentication + client parameters every Subsonic endpoint takes,
     * as a ready-to-append query string.
     */
    public static StringBuilder authenticationQuery(Map<String, String> params) {
        StringBuilder query = new StringBuilder();

        appendQueryParam(query, "u", params.get("u"));
        appendQueryParam(query, "p", params.get("p"));
        appendQueryParam(query, "s", params.get("s"));
        appendQueryParam(query, "t", params.get("t"));
        appendQueryParam(query, "v", params.get("v"));
        appendQueryParam(query, "c", params.get("c"));

        return query;
    }
}
