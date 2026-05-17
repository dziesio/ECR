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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class BritishCouncilHttpClient {

    private static final String BASE_URL            = "https://learninghub.britishcouncil.org";
    private static final String LOGIN_URL            = BASE_URL + "/d2l/login";
    private static final String ENROLLMENTS_URL      = BASE_URL + "/d2l/api/lp/1.0/enrollments/myenrollments/?pageSize=50";
    private static final String XSRF_URL             = BASE_URL + "/d2l/lp/auth/xsrf-tokens";
    private static final String OAUTH2_URL           = BASE_URL + "/d2l/lp/auth/oauth2/token";
    private static final String HOME_URL_TEMPLATE    = BASE_URL + "/d2l/home/%s";
    private static final String ACTIVITY_FEED_BASE   = "https://prd.activityfeed.eu-west-1.brightspace.com";
    // /article returns teacher news/announcements specifically
    private static final String ARTICLE_URL_TEMPLATE = ACTIVITY_FEED_BASE + "/api/v1/d2l:orgUnit:%s/article";

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
            String token = fetchActivityToken(client);
            log.info("BC session opened; activity token: {}", token != null ? "obtained" : "NONE");
            return new BcSession(client, token, this);
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
        HttpGet getLogin = new HttpGet(LOGIN_URL);
        getLogin.addHeader(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,*/*;q=0.8");
        String loginHtml = body(client.execute(getLogin));

        Document doc = Jsoup.parse(loginHtml, LOGIN_URL);
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

        // D2L sets form action via JS — hardcode the standard login service path.
        String action = BASE_URL + "/d2l/lp/auth/login/login.d2l";

        List<BasicNameValuePair> params = new ArrayList<>();
        for (Element hidden : form.select("input[type=hidden]")) {
            String name = hidden.attr("name");
            if (!name.isBlank()) params.add(new BasicNameValuePair(name, hidden.attr("value")));
        }
        params.add(new BasicNameValuePair("userName", username));
        params.add(new BasicNameValuePair("password", password));

        HttpPost post = new HttpPost(action);
        post.setEntity(new UrlEncodedFormEntity(params, StandardCharsets.UTF_8));
        post.addHeader(HttpHeaders.REFERER, LOGIN_URL);
        post.addHeader(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,*/*;q=0.8");

        try (CloseableHttpResponse resp = client.execute(post)) {
            int status = resp.getCode();
            EntityUtils.consume(resp.getEntity());
            log.info("BC login POST → {}", status);
            if (status >= 400) throw new IOException("BC login failed — HTTP " + status);
        }
    }

    // Replicates what d2lfetch-auth.js does in the browser:
    // 1. GET /d2l/lp/auth/xsrf-tokens → referrerToken (acts as CSRF token)
    // 2. POST /d2l/lp/auth/oauth2/token with X-Csrf-Token header and body scope=*:*:*
    //    → returns {"access_token": "eyJ..."} JWT for the activity feed microservice
    private String fetchActivityToken(CloseableHttpClient client) {
        try {
            // Step 1: get XSRF/referrer token
            HttpGet xsrfGet = new HttpGet(XSRF_URL);
            xsrfGet.addHeader(HttpHeaders.ACCEPT, "application/json");
            String xsrfRaw = body(client.execute(xsrfGet));
            log.info("BC xsrf-tokens: {}", xsrfRaw.substring(0, Math.min(200, xsrfRaw.length())));

            String csrfToken = textOf(objectMapper.readTree(xsrfRaw), "referrerToken");
            if (csrfToken.isBlank()) {
                log.warn("BC: no referrerToken in xsrf response");
                return null;
            }

            // Step 2: POST OAuth2 token with X-Csrf-Token (NOT X-Requested-With) and body scope=*:*:*
            HttpPost tokenPost = new HttpPost(OAUTH2_URL);
            tokenPost.addHeader(HttpHeaders.ACCEPT, "application/json");
            tokenPost.addHeader(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded");
            tokenPost.addHeader("X-Csrf-Token", csrfToken);
            tokenPost.setEntity(new UrlEncodedFormEntity(
                    List.of(new BasicNameValuePair("scope", "*:*:*")), StandardCharsets.UTF_8));

            String tokenRaw = body(client.execute(tokenPost));
            log.info("BC oauth2/token: {}", tokenRaw.substring(0, Math.min(300, tokenRaw.length())));

            String accessToken = textOf(objectMapper.readTree(tokenRaw), "access_token", "accessToken");
            if (!accessToken.isBlank()) {
                log.info("BC activity feed JWT obtained");
                return accessToken;
            }

            log.warn("BC: no access_token in oauth2 response");
            return null;
        } catch (Exception e) {
            log.error("BC fetchActivityToken failed: {}", e.getMessage());
            return null;
        }
    }

    List<String> findCourseOrgIds(CloseableHttpClient client) throws IOException {
        HttpGet get = new HttpGet(ENROLLMENTS_URL);
        get.addHeader(HttpHeaders.ACCEPT, "application/json");

        JsonNode root = objectMapper.readTree(body(client.execute(get)));
        JsonNode items = root.path("Items");
        if (items.isMissingNode()) items = root.path("items");

        List<String> ids = new ArrayList<>();
        for (JsonNode item : items) {
            JsonNode ou   = nodeOf(item, "OrgUnit", "orgUnit");
            String  id    = textOf(ou, "Id", "id");
            JsonNode type = nodeOf(ou, "Type", "type");
            String  code  = textOf(type, "Code", "code").toLowerCase();
            String  name  = textOf(type, "Name", "name").toLowerCase();
            if (!id.isBlank() && (code.contains("course") || code.contains("group")
                    || name.contains("course") || name.contains("group"))) {
                log.info("BC candidate orgUnit id={} type={}", id, textOf(type, "Name", "name"));
                ids.add(id);
            }
        }
        if (ids.isEmpty()) log.warn("BC enrollments API returned no course/group entries");
        return ids;
    }

    List<JsonNode> fetchNewsItems(CloseableHttpClient client, String token, String orgId) throws IOException {
        String articleUrl = String.format(ARTICLE_URL_TEMPLATE, orgId);
        String homeUrl    = String.format(HOME_URL_TEMPLATE, orgId);

        HttpGet get = new HttpGet(articleUrl);
        get.addHeader(HttpHeaders.ACCEPT, "application/json");
        get.addHeader(HttpHeaders.REFERER, homeUrl);
        get.addHeader("Origin", BASE_URL);
        if (token != null) get.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);

        String raw;
        int status;
        try (CloseableHttpResponse resp = client.execute(get)) {
            status = resp.getCode();
            raw = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
            if (raw == null) raw = "";
        } catch (ParseException e) {
            throw new IOException("Failed to read BC article response", e);
        }

        log.info("BC article orgId={} status={} first 500: [{}]",
                orgId, status, raw.substring(0, Math.min(500, raw.length())));

        if (status != 200 || raw.isBlank()) return List.of();

        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (Exception e) {
            log.warn("BC article not valid JSON for orgId={}", orgId);
            return List.of();
        }
        if (root == null || root.isMissingNode()) return List.of();

        List<JsonNode> items = new ArrayList<>();
        JsonNode arr = root.isArray()                              ? root
                : !root.path("orderedItems").isMissingNode() ? root.path("orderedItems")
                : !root.path("items").isMissingNode()        ? root.path("items")
                : !root.path("Items").isMissingNode()        ? root.path("Items")
                : !root.path("Objects").isMissingNode()      ? root.path("Objects")
                : root.path("objects");
        if (!arr.isMissingNode() && arr.isArray()) {
            for (JsonNode item : arr) items.add(item);
        }
        log.info("BC article returned {} item(s) for orgId={}", items.size(), orgId);
        return items;
    }

    // ── Actor resolution ─────────────────────────────────────────────────────

    private static final Pattern USERS_ID_PATTERN =
            Pattern.compile("\\.users\\.api\\.brightspace\\.com/(\\d+)");

    // Resolves an ActivityStreams actor URL to a human name. Never throws.
    // Results are cached per session to avoid duplicate network calls.
    String resolveActorName(CloseableHttpClient client, String token,
                            String actorUrl, Map<String, String> cache) {
        if (actorUrl == null || actorUrl.isBlank()) return null;
        if (cache.containsKey(actorUrl)) {
            String v = cache.get(actorUrl);
            return v.isBlank() ? null : v;
        }
        String name = doResolveActorName(client, token, actorUrl);
        cache.put(actorUrl, name != null ? name : "");
        return name;
    }

    private String doResolveActorName(CloseableHttpClient client, String token, String actorUrl) {
        // Pattern A: .users.api.brightspace.com/{userId} → LP users endpoint on the LMS
        Matcher m = USERS_ID_PATTERN.matcher(actorUrl);
        if (m.find()) {
            try {
                HttpGet get = new HttpGet(BASE_URL + "/d2l/api/lp/1.0/users/" + m.group(1));
                get.addHeader(HttpHeaders.ACCEPT, "application/json");
                JsonNode json = objectMapper.readTree(body(client.execute(get)));
                String first = textOf(json, "FirstName", "firstName");
                String last  = textOf(json, "LastName",  "lastName");
                String name  = (first + " " + last).trim();
                if (!name.isBlank()) { log.info("BC actor LP users/{} → {}", m.group(1), name); return name; }
                String display = textOf(json, "DisplayName", "displayName");
                if (!display.isBlank()) return display;
            } catch (Exception e) { log.debug("BC LP users lookup {}: {}", m.group(1), e.getMessage()); }
        }

        // Pattern B: enrolled-user or any other Brightspace URL → call directly with Bearer token
        try {
            HttpGet get = new HttpGet(actorUrl);
            get.addHeader(HttpHeaders.ACCEPT, "application/json");
            if (token != null) get.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            String raw = body(client.execute(get));
            log.info("BC actor URL {} → first 300: [{}]", actorUrl,
                    raw.substring(0, Math.min(300, raw.length())));
            JsonNode json = objectMapper.readTree(raw);

            // Standard flat JSON fields
            String first = textOf(json, "firstName", "FirstName", "givenName");
            String last  = textOf(json, "lastName",  "LastName",  "familyName");
            String name  = (first + " " + last).trim();
            if (!name.isBlank()) return name;
            String display = textOf(json, "name", "displayName", "DisplayName", "preferredName");
            if (!display.isBlank()) return display;

            // Siren hypermedia format: entities[]{class/rel, properties{name}}
            // e.g. enrollments.api.brightspace.com/enrolled-user/... returns Siren
            name = extractNameFromSiren(json);
            if (name != null) return name;
        } catch (Exception e) { log.debug("BC actor URL fetch {}: {}", actorUrl, e.getMessage()); }

        return null;
    }

    // Parses a Siren hypermedia response to extract a display name.
    // Siren entities use class arrays and nested properties rather than flat fields.
    private String extractNameFromSiren(JsonNode json) {
        JsonNode entities = json.path("entities");
        if (entities.isMissingNode() || !entities.isArray()) return null;

        String firstName = "", lastName = "";
        for (JsonNode entity : entities) {
            JsonNode clsNode = entity.path("class");
            if (!clsNode.isArray()) continue;

            List<String> classes = new ArrayList<>();
            for (JsonNode c : clsNode) classes.add(c.asText(""));

            String propName = entity.path("properties").path("name").asText("").trim();
            if (propName.isBlank()) continue;

            // rel-based: https://api.brightspace.com/rels/display-name
            JsonNode relNode = entity.path("rel");
            if (relNode.isArray()) {
                for (JsonNode r : relNode) {
                    if (r.asText("").contains("display-name")) {
                        log.info("BC Siren display-name → {}", propName);
                        return propName;
                    }
                }
            }
            // class-based fallback
            if (classes.contains("display") && classes.contains("name")) {
                log.info("BC Siren display-name (class) → {}", propName);
                return propName;
            }
            if (classes.contains("first") && classes.contains("name")) firstName = propName;
            if (classes.contains("last")  && classes.contains("name")) lastName  = propName;
        }

        String combined = (firstName + " " + lastName).trim();
        return combined.isBlank() ? null : combined;
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
        private final String token;
        private final BritishCouncilHttpClient bc;
        private final Map<String, String> actorNameCache = new HashMap<>();

        BcSession(CloseableHttpClient client, String token, BritishCouncilHttpClient bc) {
            this.client = client;
            this.token  = token;
            this.bc     = bc;
        }

        public List<String> findCourseOrgIds() throws IOException {
            return bc.findCourseOrgIds(client);
        }

        public List<JsonNode> fetchNewsItems(String orgId) throws IOException {
            return bc.fetchNewsItems(client, token, orgId);
        }

        /** Resolves an ActivityStreams actor URL to a human name; returns null if not resolvable. */
        public String resolveActorName(String actorUrl) {
            return bc.resolveActorName(client, token, actorUrl, actorNameCache);
        }

        @Override
        public void close() throws IOException {
            client.close();
        }
    }
}
