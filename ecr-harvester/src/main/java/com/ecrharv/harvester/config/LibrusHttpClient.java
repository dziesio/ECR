package com.ecrharv.harvester.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.protocol.RedirectLocations;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.util.Timeout;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
public class LibrusHttpClient {

    // Entry point that triggers the OAuth redirect chain
    private static final String PORTAL_RODZINA_URL = "https://synergia.librus.pl/loguj/portalRodzina";
    private static final String API_BASE_URL       = "https://api.librus.pl";
    private static final String DEFAULT_OAUTH_URL  = "https://api.librus.pl/OAuth/Authorization?client_id=46";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36";

    @Value("${librus.username}")
    private String username;

    @Value("${librus.password}")
    private String password;

    @Value("${scraper.proxy.list:}")
    private String proxyListRaw;

    @Value("${scraper.proxy.user:}")
    private String proxyUser;

    @Value("${scraper.proxy.pass:}")
    private String proxyPass;

    private List<HttpHost> proxies = List.of();

    @PostConstruct
    void initProxies() {
        if (proxyListRaw == null || proxyListRaw.isBlank()) return;
        proxies = Arrays.stream(proxyListRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> { String[] p = s.split(":"); return new HttpHost(p[0], Integer.parseInt(p[1])); })
                .toList();
        log.info("Proxy rotation enabled — {} proxies loaded", proxies.size());
    }

    private HttpHost pickProxy() {
        if (proxies.isEmpty()) return null;
        return proxies.get(ThreadLocalRandom.current().nextInt(proxies.size()));
    }

    private static final int PROXY_RETRIES = 3;

    public LibrusSession openSession() throws IOException {
        IOException lastEx = null;
        for (int attempt = 1; attempt <= PROXY_RETRIES; attempt++) {
            BasicCookieStore cookieStore = new BasicCookieStore();
            CloseableHttpClient client = buildClient(cookieStore);
            try {
                login(client);
                log.info("Librus session opened (attempt {})", attempt);
                return new LibrusSession(client);
            } catch (IOException e) {
                client.close();
                lastEx = e;
                boolean proxyError = e.getMessage() != null &&
                        (e.getMessage().contains("504") || e.getMessage().contains("502") ||
                         e.getMessage().contains("CONNECT") || e.getMessage().contains("proxy"));
                if (proxyError && attempt < PROXY_RETRIES) {
                    log.warn("Proxy error on attempt {}/{} — retrying with new connection: {}",
                            attempt, PROXY_RETRIES, e.getMessage());
                } else {
                    throw e;
                }
            }
        }
        throw lastEx;
    }

    // Only Host + Proxy-Authorization belong in CONNECT tunnel requests.
    // Strip every custom default header so the CONNECT looks like a plain HTTP client.
    private static final Set<String> CONNECT_STRIP_HEADERS = Set.of(
            "user-agent",
            "accept", "accept-language", "accept-encoding",
            "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform",
            "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site", "sec-fetch-user",
            "upgrade-insecure-requests", "referer"
    );

    private CloseableHttpClient buildClient(BasicCookieStore cookieStore) {
        HttpHost proxy = pickProxy();

        var builder = HttpClients.custom()
                .setDefaultCookieStore(cookieStore)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(30))
                        .setResponseTimeout(Timeout.ofSeconds(60))
                        .build())
                .setDefaultHeaders(List.of(
                        new BasicHeader(HttpHeaders.USER_AGENT,       USER_AGENT),
                        new BasicHeader(HttpHeaders.ACCEPT,           "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"),
                        new BasicHeader(HttpHeaders.ACCEPT_LANGUAGE,  "pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7"),
                        new BasicHeader(HttpHeaders.ACCEPT_ENCODING,  "gzip, deflate, br, zstd"),
                        new BasicHeader("Sec-CH-UA",                  "\"Chromium\";v=\"147\", \"Google Chrome\";v=\"147\", \"Not=A?Brand\";v=\"99\""),
                        new BasicHeader("Sec-CH-UA-Mobile",           "?0"),
                        new BasicHeader("Sec-CH-UA-Platform",         "\"macOS\""),
                        new BasicHeader("Sec-Fetch-Dest",             "document"),
                        new BasicHeader("Sec-Fetch-Mode",             "navigate"),
                        new BasicHeader("Sec-Fetch-Site",             "cross-site"),
                        new BasicHeader("Sec-Fetch-User",             "?1"),
                        new BasicHeader("Upgrade-Insecure-Requests",  "1"),
                        new BasicHeader(HttpHeaders.REFERER,          "https://portal.librus.pl/rodzina/synergia/loguj")))
                // Strip browser-only headers from CONNECT tunnel requests — proxies
                // don't understand them and some reject or mis-route such requests.
                .addRequestInterceptorFirst((request, entity, context) -> {
                    if ("CONNECT".equalsIgnoreCase(request.getMethod())) {
                        for (String h : CONNECT_STRIP_HEADERS) request.removeHeaders(h);
                    }
                });

        if (proxy != null) {
            var creds = new BasicCredentialsProvider();
            creds.setCredentials(new AuthScope(proxy),
                    new UsernamePasswordCredentials(proxyUser, proxyPass.toCharArray()));
            builder.setProxy(proxy).setDefaultCredentialsProvider(creds);
            log.info("Session using proxy {}:{}", proxy.getHostName(), proxy.getPort());
        }

        return builder.build();
    }

    private void login(CloseableHttpClient client) throws IOException {
        // Step 1: GET portalRodzina → follow redirects to OAuth login form; save URL + HTML
        String[] oauthPage = resolveOAuthPage(client);   // [0]=url, [1]=html
        String oauthUrl  = oauthPage[0];
        String loginHtml = oauthPage[1];
        log.info("OAuth authorize URL: {}", oauthUrl);

        // Step 2: Extract hidden form fields (CSRF tokens etc.) from the login page
        log.info("OAuth login page HTML (first 600): {}",
                loginHtml.substring(0, Math.min(600, loginHtml.length())));
        List<BasicNameValuePair> formParams = parseHiddenInputs(loginHtml);
        log.info("OAuth login form hidden fields: {}", formParams.stream().map(BasicNameValuePair::getName).toList());

        // Step 3: POST credentials → JSON {"goTo": "/OAuth/Authorization/..."}
        String goTo = postCredentials(client, oauthUrl, formParams);
        log.info("OAuth goTo: {}", goTo);

        // Step 4: GET goTo → redirects back to synergia.librus.pl, setting session cookies
        HttpGet grant = new HttpGet(API_BASE_URL + goTo);
        grant.addHeader(HttpHeaders.REFERER, oauthUrl);
        try (CloseableHttpResponse resp = client.execute(grant)) {
            log.info("OAuth grant response: {}", resp.getCode());
            EntityUtils.consume(resp.getEntity());
        }
    }

    // Returns [oauthUrl, loginPageHtml]
    private String[] resolveOAuthPage(CloseableHttpClient client) throws IOException {
        HttpGet get = new HttpGet(PORTAL_RODZINA_URL);
        get.addHeader(HttpHeaders.REFERER, "https://portal.librus.pl/");

        HttpClientContext context = HttpClientContext.create();
        try (CloseableHttpResponse resp = client.execute(get, context)) {
            String html;
            try {
                html = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
            } catch (ParseException e) {
                html = "";
            }

            String url = DEFAULT_OAUTH_URL;
            RedirectLocations locations = context.getRedirectLocations();
            if (locations != null) {
                List<URI> all = locations.getAll();
                if (!all.isEmpty()) url = all.get(all.size() - 1).toString();
            }
            if (DEFAULT_OAUTH_URL.equals(url)) {
                log.warn("No redirects from portalRodzina — using default OAuth URL");
            }
            return new String[]{url, html};
        }
    }

    private List<BasicNameValuePair> parseHiddenInputs(String html) {
        List<BasicNameValuePair> params = new ArrayList<>();
        params.add(new BasicNameValuePair("action", "login"));
        if (html == null || html.isBlank()) return params;
        try {
            for (var input : Jsoup.parse(html).select("form input[type=hidden]")) {
                String name = input.attr("name").trim();
                if (!name.isBlank() && !name.equals("action")) {
                    params.add(new BasicNameValuePair(name, input.attr("value")));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse OAuth login form: {}", e.getMessage());
        }
        return params;
    }

    private String postCredentials(CloseableHttpClient client, String oauthUrl,
                                   List<BasicNameValuePair> hiddenParams) throws IOException {
        List<BasicNameValuePair> allParams = new ArrayList<>(hiddenParams);
        allParams.add(new BasicNameValuePair("login", username));
        allParams.add(new BasicNameValuePair("pass",  password));

        HttpPost post = new HttpPost(oauthUrl);
        post.setEntity(new UrlEncodedFormEntity(allParams, StandardCharsets.UTF_8));
        post.addHeader(HttpHeaders.REFERER,  oauthUrl);
        post.addHeader("X-Requested-With",   "XMLHttpRequest");
        post.setHeader(HttpHeaders.ACCEPT,   "application/json, */*;q=0.5");

        try (CloseableHttpResponse resp = client.execute(post)) {
            String body;
            try {
                body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
            } catch (ParseException e) {
                throw new IOException("Failed to read login response", e);
            }
            log.info("Login response ({}) body (first 1000): {}", resp.getCode(),
                    body.substring(0, Math.min(1000, body.length())));

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
