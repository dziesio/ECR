package com.ecrharv.harvester.config;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class LibrusSession implements Closeable {

    private static final String REFERER = "https://synergia.librus.pl/";

    private final CloseableHttpClient client;

    LibrusSession(CloseableHttpClient client) {
        this.client = client;
    }

    public Document getPage(String url) throws IOException {
        HttpGet get = new HttpGet(url);
        get.addHeader(HttpHeaders.REFERER, REFERER);
        try (CloseableHttpResponse resp = client.execute(get)) {
            try {
                String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
                return Jsoup.parse(body, url);
            } catch (ParseException e) {
                throw new IOException("Failed to parse response from " + url, e);
            }
        }
    }

    @Override
    public void close() throws IOException {
        client.close();
    }
}
