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
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.util.Timeout;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class BritishCouncilHttpClient {

    private static final String BASE_URL         = "https://learninghub.britishcouncil.org";
    private static final String LOGIN_URL         = BASE_URL + "/d2l/login";
    private static final String ENROLLMENTS_URL   = BASE_URL + "/d2l/api/lp/1.0/enrollments/myenrollments/?pageSize=50";
    private static final String NEWS_URL_TEMPLATE = BASE_URL + "/d2l/api/le/1.0/%s/news/";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36";

    @Value("${bc.username:}")
    private String username;

    @Value("${bc.password:}")
    private String password;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public BcSession openSession() throws IOException {
        BasicCookieStore cookieStore = new BasicCookieStore();
        CloseableHttpClient client = buildClient(cookieStore);
        try {
            login(client);
            log.info("BC session opened");
            return new BcSession(client, this);
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
                        new BasicHeader(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9"),
                        new BasicHeader(HttpHeaders.ACCEPT_ENCODING, "gzip, deflate, br")))
                .build();
    }

    private void login(CloseableHttpClient client) throws IOException {
        // Step 1: GET login page — collect hidden CSRF/state fields
        HttpGet getLogin = new HttpGet(LOGIN_URL);
        getLogin.addHeader(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,*/*;q=0.8");
        String loginHtml = body(client.execute(getLogin));

        Document doc = Jsoup.parse(loginHtml, LOGIN_URL);
        // Find form by username field presence — D2L omits method="post" or varies case
        Element form = doc.selectFirst("form:has(input[name=userName])");
        if (form == null) form = doc.selectFirst("form:has(input[name=username])");
        if (form == null) form = doc.selectFirst("form:has(input[type=password])");
        if (form == null) {
            log.error("BC login page title: '{}', forms: {}, head: {}",
                    doc.title(),
                    doc.select("form").stream().map(f -> f.attr("action") + "/" + f.attr("method")).toList(),
                    loginHtml.substring(0, Math.min(500, loginHtml.length())));
            throw new IOException("BC login page contains no recognisable login form");
        }
        log.info("BC login form action='{}' method='{}'", form.attr("action"), form.attr("method"));

        String action = form.absUrl("action");
        if (action.isBlank()) action = BASE_URL + form.attr("action");

        List<BasicNameValuePair> params = new ArrayList<>();
        for (Element hidden : form.select("input[type=hidden]")) {
            String name = hidden.attr("name");
            if (!name.isBlank()) params.add(new BasicNameValuePair(name, hidden.attr("value")));
        }
        params.add(new BasicNameValuePair("userName", username));
        params.add(new BasicNameValuePair("password", password));

        // Step 2: POST credentials
        HttpPost post = new HttpPost(action);
        post.setEntity(new UrlEncodedFormEntity(params, StandardCharsets.UTF_8));
        post.addHeader(HttpHeaders.REFERER, LOGIN_URL);
        post.addHeader(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,*/*;q=0.8");

        try (CloseableHttpResponse resp = client.execute(post)) {
            int status = resp.getCode();
            EntityUtils.consume(resp.getEntity());
            log.info("BC login POST → {}", status);
            if (status >= 400) {
                throw new IOException("BC login failed — HTTP " + status);
            }
        }
    }

    String findFirstCourseOrgId(CloseableHttpClient client) throws IOException {
        HttpGet get = new HttpGet(ENROLLMENTS_URL);
        get.addHeader(HttpHeaders.ACCEPT, "application/json");

        String raw = body(client.execute(get));
        log.info("BC enrollments raw (first 500): {}", raw.substring(0, Math.min(500, raw.length())));
        JsonNode root = objectMapper.readTree(raw);
        JsonNode items = root.path("Items");
        if (items.isMissingNode()) items = root.path("items");

        for (JsonNode item : items) {
            JsonNode ou   = nodeOf(item, "OrgUnit", "orgUnit");
            String  id    = textOf(ou, "Id", "id");
            JsonNode type = nodeOf(ou, "Type", "type");
            String  code  = textOf(type, "Code", "code").toLowerCase();
            String  name  = textOf(type, "Name", "name").toLowerCase();
            if (!id.isBlank() && (code.contains("course") || name.contains("course"))) {
                log.info("BC enrolled course orgUnitId={}", id);
                return id;
            }
        }
        log.warn("BC enrollments API returned no course entries");
        return null;
    }

    List<JsonNode> fetchNewsItems(CloseableHttpClient client, String orgId) throws IOException {
        HttpGet get = new HttpGet(String.format(NEWS_URL_TEMPLATE, orgId));
        get.addHeader(HttpHeaders.ACCEPT, "application/json");
        get.addHeader(HttpHeaders.REFERER, BASE_URL + "/d2l/home/" + orgId);

        JsonNode root = objectMapper.readTree(body(client.execute(get)));
        List<JsonNode> items = new ArrayList<>();
        JsonNode arr = root.isArray() ? root
                : !root.path("Objects").isMissingNode() ? root.path("Objects")
                : root.path("Items");
        for (JsonNode item : arr) items.add(item);
        log.info("BC news API returned {} item(s) for orgId={}", items.size(), orgId);
        return items;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String body(CloseableHttpResponse resp) throws IOException {
        try (resp) {
            try {
                return EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
            } catch (ParseException e) {
                throw new IOException("Failed to read BC response body", e);
            }
        }
    }

    private JsonNode nodeOf(JsonNode parent, String... keys) {
        for (String k : keys) {
            JsonNode n = parent.path(k);
            if (!n.isMissingNode()) return n;
        }
        return objectMapper.nullNode();
    }

    private String textOf(JsonNode parent, String... keys) {
        for (String k : keys) {
            String v = parent.path(k).asText("").trim();
            if (!v.isBlank() && !"null".equals(v)) return v;
        }
        return "";
    }

    // ── Session ──────────────────────────────────────────────────────────────

    public static class BcSession implements Closeable {
        private final CloseableHttpClient client;
        private final BritishCouncilHttpClient bc;

        BcSession(CloseableHttpClient client, BritishCouncilHttpClient bc) {
            this.client = client;
            this.bc     = bc;
        }

        public String findFirstCourseOrgId() throws IOException {
            return bc.findFirstCourseOrgId(client);
        }

        public List<JsonNode> fetchNewsItems(String orgId) throws IOException {
            return bc.fetchNewsItems(client, orgId);
        }

        @Override
        public void close() throws IOException {
            client.close();
        }
    }
}
