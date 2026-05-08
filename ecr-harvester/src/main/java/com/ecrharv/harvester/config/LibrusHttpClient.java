package com.ecrharv.harvester.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.util.Timeout;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class LibrusHttpClient {

    private static final String LOGIN_URL  = "https://synergia.librus.pl/loguj";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    @Value("${librus.username}")
    private String username;

    @Value("${librus.password}")
    private String password;

    public LibrusSession openSession() throws IOException {
        BasicCookieStore cookieStore = new BasicCookieStore();
        CloseableHttpClient client = buildClient(cookieStore);
        try {
            login(client);
            log.info("Librus session opened");
            return new LibrusSession(client);
        } catch (IOException e) {
            client.close();
            throw e;
        }
    }

    private CloseableHttpClient buildClient(BasicCookieStore cookieStore) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(30))
                .setResponseTimeout(Timeout.ofSeconds(60))
                .build();

        return HttpClients.custom()
                .setDefaultCookieStore(cookieStore)
                .setDefaultRequestConfig(requestConfig)
                .setDefaultHeaders(List.of(
                        new BasicHeader(HttpHeaders.USER_AGENT, USER_AGENT),
                        new BasicHeader(HttpHeaders.ACCEPT_LANGUAGE, "pl-PL,pl;q=0.9,en-US;q=0.8"),
                        new BasicHeader(HttpHeaders.ACCEPT,
                                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")))
                .build();
    }

    private void login(CloseableHttpClient client) throws IOException {
        Document loginPage = fetch(client, LOGIN_URL);

        Element form = loginPage.selectFirst("form");
        String action = (form != null && !form.attr("abs:action").isBlank())
                ? form.attr("abs:action")
                : LOGIN_URL;

        List<NameValuePair> formData = new ArrayList<>();
        if (form != null) {
            for (Element input : form.select("input[name]")) {
                String name  = input.attr("name");
                String value = switch (name) {
                    case "Login" -> username;
                    case "Pass"  -> password;
                    default      -> input.attr("value");
                };
                formData.add(new BasicNameValuePair(name, value));
            }
        }
        if (formData.stream().noneMatch(p -> "Login".equals(p.getName()))) {
            formData.add(new BasicNameValuePair("Login", username));
            formData.add(new BasicNameValuePair("Pass",  password));
        }

        HttpPost post = new HttpPost(action);
        post.setEntity(new UrlEncodedFormEntity(formData, StandardCharsets.UTF_8));
        post.addHeader(HttpHeaders.REFERER, LOGIN_URL);

        try (CloseableHttpResponse resp = client.execute(post)) {
            log.debug("Login POST → {}", resp.getCode());
            EntityUtils.consume(resp.getEntity());
        }
    }

    private Document fetch(CloseableHttpClient client, String url) throws IOException {
        try (CloseableHttpResponse resp = client.execute(new HttpGet(url))) {
            try {
                String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
                return Jsoup.parse(body, url);
            } catch (ParseException e) {
                throw new IOException("Failed to parse response from " + url, e);
            }
        }
    }
}
