package com.ecrharv.harvester.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.protocol.RedirectLocations;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Component
public class LibrusHttpClient {

    // Entry point that triggers the OAuth redirect chain
    private static final String PORTAL_RODZINA_URL = "https://synergia.librus.pl/loguj/portalRodzina";
    private static final String API_BASE_URL       = "https://api.librus.pl";
    private static final String DEFAULT_OAUTH_URL  = "https://api.librus.pl/OAuth/Authorization?client_id=46";

    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

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
        return HttpClients.custom()
                .setDefaultCookieStore(cookieStore)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(30))
                        .setResponseTimeout(Timeout.ofSeconds(60))
                        .build())
                .setDefaultHeaders(List.of(
                        new BasicHeader(HttpHeaders.USER_AGENT, USER_AGENT),
                        new BasicHeader(HttpHeaders.REFERER,
                                "https://portal.librus.pl/rodzina/synergia/loguj")))
                .build();
    }

    private void login(CloseableHttpClient client) throws IOException {
        // Step 1: GET portalRodzina → redirects to api.librus.pl/OAuth/Authorization?client_id=46
        String oauthUrl = resolveOAuthUrl(client);
        log.info("OAuth authorize URL: {}", oauthUrl);

        // Step 2: POST credentials → JSON {"goTo": "/OAuth/Authorization/..."}
        String goTo = postCredentials(client, oauthUrl);
        log.info("OAuth goTo: {}", goTo);

        // Step 3: GET goTo → redirects back to synergia.librus.pl, setting session cookies
        HttpGet grant = new HttpGet(API_BASE_URL + goTo);
        grant.addHeader(HttpHeaders.REFERER, oauthUrl);
        try (CloseableHttpResponse resp = client.execute(grant)) {
            log.info("OAuth grant response: {}", resp.getCode());
            EntityUtils.consume(resp.getEntity());
        }
    }

    private String resolveOAuthUrl(CloseableHttpClient client) throws IOException {
        HttpGet get = new HttpGet(PORTAL_RODZINA_URL);
        get.addHeader(HttpHeaders.REFERER, "https://portal.librus.pl/");

        HttpClientContext context = HttpClientContext.create();
        try (CloseableHttpResponse resp = client.execute(get, context)) {
            EntityUtils.consume(resp.getEntity());

            RedirectLocations locations = context.getRedirectLocations();
            if (locations != null) {
                List<URI> all = locations.getAll();
                if (!all.isEmpty()) return all.get(all.size() - 1).toString();
            }
            log.warn("No redirects from portalRodzina — using default OAuth URL");
            return DEFAULT_OAUTH_URL;
        }
    }

    private String postCredentials(CloseableHttpClient client, String oauthUrl) throws IOException {
        HttpPost post = new HttpPost(oauthUrl);
        post.setEntity(new UrlEncodedFormEntity(List.of(
                new BasicNameValuePair("action", "login"),
                new BasicNameValuePair("login",  username),
                new BasicNameValuePair("pass",   password)
        ), StandardCharsets.UTF_8));
        post.addHeader(HttpHeaders.REFERER, oauthUrl);

        try (CloseableHttpResponse resp = client.execute(post)) {
            String body;
            try {
                body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
            } catch (ParseException e) {
                throw new IOException("Failed to read login response", e);
            }
            log.debug("Login response ({}): {}", resp.getCode(),
                    body.substring(0, Math.min(300, body.length())));

            JsonNode node;
            try {
                node = new ObjectMapper().readTree(body);
            } catch (Exception e) {
                throw new IOException("Login response is not JSON: " +
                        body.substring(0, Math.min(300, body.length())), e);
            }

            JsonNode goTo = node.get("goTo");
            if (goTo == null || goTo.isNull() || goTo.asText().isBlank()) {
                throw new IOException("Login failed — no 'goTo' in response. " +
                        "Check credentials. Response: " +
                        body.substring(0, Math.min(300, body.length())));
            }
            return goTo.asText();
        }
    }
}
