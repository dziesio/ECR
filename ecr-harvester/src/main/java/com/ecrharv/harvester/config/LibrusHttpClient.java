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
import org.apache.hc.client5.http.impl.cookie.BasicClientCookie;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class LibrusHttpClient {

    private static final String API_BASE_URL      = "https://api.librus.pl";
    private static final String PORTAL_RODZINA_URL = "https://synergia.librus.pl/loguj/portalRodzina";
    private static final String PORTAL_REFERER    = "https://portal.librus.pl/";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";

    @Value("${librus.username}")
    private String username;

    @Value("${librus.password}")
    private String password;

    // DeviceCookie value extracted from a real browser session — bypasses bot challenge on api.librus.pl.
    // Get it from DevTools → Application → Cookies → api.librus.pl → DeviceCookie.
    @Value("${librus.device-cookie:}")
    private String deviceCookie;

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

    private static final int PROXY_RETRIES = 12;

    public LibrusSession openSession() throws IOException {
        IOException lastEx = null;
        for (int attempt = 1; attempt <= PROXY_RETRIES; attempt++) {
            BasicCookieStore cookieStore = new BasicCookieStore();
            seedDeviceCookie(cookieStore);
            CloseableHttpClient client = buildClient(cookieStore);
            try {
                login(client);
                log.info("Librus session opened (attempt {})", attempt);
                return new LibrusSession(client);
            } catch (IOException e) {
                client.close();
                lastEx = e;
                // Don't retry credential failures — wrong password won't improve with a new IP.
                boolean credentialError = e.getMessage() != null &&
                        e.getMessage().contains("no 'goTo' in response");
                boolean retryable = !proxies.isEmpty() && !credentialError;
                if (retryable && attempt < PROXY_RETRIES) {
                    log.warn("Retryable error on attempt {}/{} — rotating proxy connection: {}",
                            attempt, PROXY_RETRIES, e.getMessage());
                } else {
                    throw e;
                }
            }
        }
        throw lastEx;
    }

    // Pre-seed DeviceCookie so api.librus.pl serves the login form instead of a bot challenge.
    private void seedDeviceCookie(BasicCookieStore store) {
        if (deviceCookie == null || deviceCookie.isBlank()) {
            log.warn("librus.device-cookie not set — bot challenge likely");
            return;
        }
        BasicClientCookie c = new BasicClientCookie("DeviceCookie", deviceCookie);
        c.setDomain("api.librus.pl");
        c.setPath("/");
        store.addCookie(c);
        log.debug("DeviceCookie pre-seeded for api.librus.pl");
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
                        new BasicHeader(HttpHeaders.ACCEPT,           "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"),
                        new BasicHeader(HttpHeaders.ACCEPT_LANGUAGE,  "en-GB,en-US;q=0.9,en;q=0.8"),
                        new BasicHeader(HttpHeaders.ACCEPT_ENCODING,  "gzip, deflate, br, zstd"),
                        new BasicHeader("Sec-CH-UA",                  "\"Chromium\";v=\"148\", \"Google Chrome\";v=\"148\", \"Not/A)Brand\";v=\"99\""),
                        new BasicHeader("Sec-CH-UA-Mobile",           "?0"),
                        new BasicHeader("Sec-CH-UA-Platform",         "\"macOS\""),
                        new BasicHeader("Sec-Fetch-Dest",             "iframe"),
                        new BasicHeader("Sec-Fetch-Mode",             "navigate"),
                        new BasicHeader("Sec-Fetch-Site",             "same-site"),
                        new BasicHeader("Upgrade-Insecure-Requests",  "1"),
                        new BasicHeader(HttpHeaders.REFERER,          PORTAL_REFERER)))
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
        // Step 1: GET portalRodzina → redirects to full OAuth URL with state param → login form HTML
        String[] oauthPage = resolveOAuthPage(client);   // [0]=url, [1]=html
        String oauthUrl  = oauthPage[0];
        String loginHtml = oauthPage[1];
        log.info("OAuth authorize URL: {}", oauthUrl);
        log.info("OAuth login page HTML (first 2000): {}",
                loginHtml.substring(0, Math.min(2000, loginHtml.length())));

        // Step 2: Extract hidden form fields and x-baner token from the page
        List<BasicNameValuePair> formParams = parseHiddenInputs(loginHtml);
        log.info("OAuth login form hidden fields: {}", formParams.stream().map(BasicNameValuePair::getName).toList());
        String xBaner = extractXBaner(loginHtml);
        log.info("x-baner extracted from page: {}", xBaner != null ? xBaner : "(not found — using fallback)");

        // Step 3: POST credentials → JSON {"goTo": "/OAuth/Authorization/..."}
        String goTo = postCredentials(client, oauthUrl, formParams, xBaner);
        log.info("OAuth goTo: {}", goTo);

        // Step 4: GET goTo → redirects back to synergia.librus.pl, setting session cookies
        HttpGet grant = new HttpGet(API_BASE_URL + goTo);
        grant.addHeader(HttpHeaders.REFERER, oauthUrl);
        try (CloseableHttpResponse resp = client.execute(grant)) {
            log.info("OAuth grant response: {}", resp.getCode());
            EntityUtils.consume(resp.getEntity());
        }
    }

    // portalRodzina sets up OAuth state and redirects to the full OAuth URL with response_type/scope/state params.
    // Going directly to ?client_id=46 without those params returns invalid_request.
    private String[] resolveOAuthPage(CloseableHttpClient client) throws IOException {
        HttpGet get = new HttpGet(PORTAL_RODZINA_URL);
        get.addHeader(HttpHeaders.REFERER, PORTAL_REFERER);

        HttpClientContext context = HttpClientContext.create();
        try (CloseableHttpResponse resp = client.execute(get, context)) {
            String html;
            try {
                html = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
            } catch (ParseException e) {
                html = "";
            }
            String url = PORTAL_RODZINA_URL;
            RedirectLocations locations = context.getRedirectLocations();
            if (locations != null) {
                List<URI> all = locations.getAll();
                if (!all.isEmpty()) url = all.get(all.size() - 1).toString();
            }
            log.info("portalRodzina final URL after redirects: {}", url);
            if (html.contains("Web Page Blocked") || html.contains("Attack ID")) {
                throw new IOException("WAF block on proxy IP — rotating");
            }
            return new String[]{url, html};
        }
    }

    // x-baner is a token embedded in the OAuth page JS, sent as a request header on credential POST.
    // Pattern: uppercase letters and underscores, typically two segments separated by _.
    private static final Pattern XBANER_PATTERN = Pattern.compile("[A-Z]{5,}_[A-Z]{5,}");
    private static final String  XBANER_FALLBACK = "DBEDIFJDHMHHEJMFMHF_EKKMEHDJHIGII";

    private String extractXBaner(String html) {
        if (html == null || html.isBlank()) return null;
        try {
            // Search inside <script> tags for the token pattern
            for (var script : Jsoup.parse(html).select("script")) {
                String src = script.data();
                Matcher m = XBANER_PATTERN.matcher(src);
                while (m.find()) {
                    String candidate = m.group();
                    // Reject short/common JS identifiers — real token is 30+ chars
                    if (candidate.length() >= 30) return candidate;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract x-baner from page: {}", e.getMessage());
        }
        return null;
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

    private String postCredentials(CloseableHttpClient client,
                                   String oauthUrl,
                                   List<BasicNameValuePair> hiddenParams,
                                   String xBaner) throws IOException {
        List<BasicNameValuePair> allParams = new ArrayList<>(hiddenParams);
        allParams.add(new BasicNameValuePair("login", username));
        allParams.add(new BasicNameValuePair("pass",  password));

        HttpPost post = new HttpPost(oauthUrl);
        post.setEntity(new UrlEncodedFormEntity(allParams, StandardCharsets.UTF_8));
        post.setHeader(HttpHeaders.ACCEPT,    "application/json, text/javascript, */*; q=0.01");
        post.setHeader(HttpHeaders.REFERER,   oauthUrl);
        post.setHeader("Origin",              API_BASE_URL);
        post.setHeader("X-Requested-With",    "XMLHttpRequest");
        post.setHeader("X-Baner",             xBaner != null ? xBaner : XBANER_FALLBACK);
        post.setHeader("Sec-Fetch-Dest",      "empty");
        post.setHeader("Sec-Fetch-Mode",      "cors");
        post.setHeader("Sec-Fetch-Site",      "same-origin");
        post.removeHeaders("Sec-Fetch-User");

        try (CloseableHttpResponse resp = client.execute(post)) {
            String body;
            try {
                body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
            } catch (ParseException e) {
                throw new IOException("Failed to read login response", e);
            }
            var ct = resp.getFirstHeader("Content-Type");
            log.info("Login response HTTP {} Content-Type={} body: [{}]",
                    resp.getCode(), ct != null ? ct.getValue() : "none", body);

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
