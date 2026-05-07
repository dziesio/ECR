package com.ecrharv.harvester.scraping;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public final class BritishCouncilKeys {

    private BritishCouncilKeys() {}

    // ─── URLs ────────────────────────────────────────────────────────────────
    public static final String URL_LOGIN         = "https://learninghub.britishcouncil.org/d2l/login";
    public static final String URL_HOME_FRAGMENT = "/d2l/home";
    public static final String URL_COURSE_BASE   = "https://learninghub.britishcouncil.org/d2l/home/";

    // ─── Login form ──────────────────────────────────────────────────────────
    public static final String SEL_USERNAME = "#userName, input[name='userName'], input[type='email']";
    public static final String SEL_PASSWORD = "#password, input[name='password'], input[type='password']";
    public static final String SEL_SUBMIT   = "button.d2l-button[primary], button[primary]";

    // ─── News widgets — one of these should appear on the course home page ───────
    // d2l-class-stream-widget is the activity stream; d2l-news / d2l-news-list are the news feed
    public static final String SEL_NEWS_WIDGET =
        "d2l-news-widget, d2l-news-list, d2l-news, d2l-class-stream-widget, [data-widget-type='news']";

    // ─── JS: extract logged-in user name + ou ID from the navbar (light DOM) ───
    // Name is in .d2l-navigation-s-personal-menu-text (slot content → light DOM).
    // ou ID is in the profile link href query param: ?ou=6923
    public static final String JS_EXTRACT_USER_INFO =
        "(function() {" +
        "  var nameEl = document.querySelector('.d2l-navigation-s-personal-menu-text');" +
        "  var name = nameEl ? nameEl.textContent.trim() : '';" +
        "  var profileLink = document.querySelector('.d2l-personal-tools-list a[href*=\"profile_edit\"]');" +
        "  var href = profileLink ? profileLink.getAttribute('href') : '';" +
        "  var match = href.match(/[?&]ou=(\\d+)/);" +
        "  var id = match ? match[1] : '';" +
        "  return JSON.stringify({name: name, id: id});" +
        "})()";

    // ─── JS: find first enrolled course orgUnitId — multi-strategy ──────────
    // Strategy 1: enrollment card id attr (slotted light-DOM children of d2l-my-courses)
    // Strategy 2: any <a href="/d2l/home/{id}"> link in light DOM
    // Strategy 3: same searches recursed into every open shadow root
    public static final String JS_FIND_COURSE_ID =
        "(function() {" +
        "  var currentId = (window.location.pathname.match(/\\/d2l\\/home\\/(\\d+)/) || [])[1] || '';" +
        "  function fromCard(root) {" +
        "    var cards = root.querySelectorAll('d2l-my-courses-enrollment-card');" +
        "    for (var i = 0; i < cards.length; i++) {" +
        "      var id = cards[i].id || cards[i].getAttribute('id') || '';" +
        "      if (id.startsWith('enrollment-card-')) {" +
        "        var oid = id.replace('enrollment-card-','');" +
        "        if (oid !== currentId) return oid;" +
        "      }" +
        "    }" +
        "    return null;" +
        "  }" +
        "  function fromLink(root) {" +
        "    var links = root.querySelectorAll('a[href]');" +
        "    for (var i = 0; i < links.length; i++) {" +
        "      var m = (links[i].getAttribute('href')||'').match(/\\/d2l\\/home\\/(\\d+)/);" +
        "      if (m && m[1] !== currentId) return m[1];" +
        "    }" +
        "    return null;" +
        "  }" +
        "  function pierce(root, depth) {" +
        "    if (!root || depth > 15) return null;" +
        "    var r = fromCard(root) || fromLink(root);" +
        "    if (r) return r;" +
        "    var all = root.querySelectorAll('*');" +
        "    for (var i = 0; i < all.length; i++) {" +
        "      if (all[i].shadowRoot) {" +
        "        var res = pierce(all[i].shadowRoot, depth+1);" +
        "        if (res) return res;" +
        "      }" +
        "    }" +
        "    return null;" +
        "  }" +
        "  return pierce(document, 0);" +
        "})()";

    // ─── JS: async fallback — D2L enrollments API, course offerings only ─────────
    // Filters out departments / org-roots by checking OrgUnit.Type contains "course"
    public static final String JS_FIND_COURSE_ID_ASYNC =
        "var cb = arguments[arguments.length-1];" +
        "var currentId = (window.location.pathname.match(/\\/d2l\\/home\\/(\\d+)/) || [])[1] || '';" +
        "fetch('/d2l/api/lp/1.0/enrollments/myenrollments/?pageSize=50',{credentials:'include'})" +
        "  .then(function(r){" +
        "    if(!r.ok){return r.text().then(function(t){cb('HTTP_'+r.status+':'+t.substring(0,200));});}" +
        "    return r.json().then(function(d){" +
        "      var items = d.Items||d.items||[];" +
        "      for(var i=0;i<items.length;i++){" +
        "        var ou   = items[i].OrgUnit||items[i].orgUnit||{};" +
        "        var id   = String(ou.Id||ou.id||'');" +
        "        var type = ou.Type||ou.type||{};" +
        "        var code = String(type.Code||type.code||'').toLowerCase();" +
        "        var name = String(type.Name||type.name||'').toLowerCase();" +
        "        var isCourse = code.indexOf('course')>=0||name.indexOf('course')>=0;" +
        "        if(id && id!==currentId && isCourse){cb(id);return;}" +
        "      }" +
        "      var summary = 'keys='+Object.keys(d).join(',')+'|items='+items.map(function(i){" +
        "        var o=i.OrgUnit||{};return o.Id+'/'+((o.Type||{}).Code||'?');}).join(',');" +
        "      cb('NO_COURSE:'+summary);" +
        "    });" +
        "  }).catch(function(e){cb('ERR:'+String(e));});";

    // ─── JS: fetch native D2L News items for current course via REST API ────────
    // Uses session cookies Selenium already holds. Returns JSON array of news items,
    // "HTTP_{status}:{body}" on API error, or "ERR:{msg}" on network failure.
    // Author is pre-extracted into _author by trying every known D2L field path.
    public static final String JS_FETCH_NEWS_ASYNC =
        "var cb = arguments[arguments.length-1];" +
        "var m = window.location.pathname.match(/\\/d2l\\/home\\/(\\d+)/);" +
        "var orgId = m ? m[1] : '';" +
        "if (!orgId) { cb('ERR:no_orgId_in_url'); return; }" +
        "function extractAuthor(item) {" +
        "  var nested = ['CreatedBy','Author','User','Instructor','Creator','Publisher','Owner'];" +
        "  for (var i = 0; i < nested.length; i++) {" +
        "    var o = item[nested[i]];" +
        "    if (o && typeof o === 'object') {" +
        "      var f = (o.FirstName || '').trim(), l = (o.LastName || '').trim();" +
        "      if (f || l) return (f + ' ' + l).trim();" +
        "      var d = (o.DisplayName || o.Name || '').trim();" +
        "      if (d) return d;" +
        "    }" +
        "  }" +
        "  var f = (item.AuthorFirstName || '').trim(), l = (item.AuthorLastName || '').trim();" +
        "  return (f + ' ' + l).trim();" +
        "}" +
        "fetch('/d2l/api/le/1.0/' + orgId + '/news/', {credentials:'include'})" +
        "  .then(function(r) {" +
        "    if (!r.ok) { return r.text().then(function(t) { cb('HTTP_' + r.status + ':' + t.substring(0,300)); }); }" +
        "    return r.json().then(function(d) {" +
        "      var arr = Array.isArray(d) ? d : (d.Objects || d.objects || d.Items || d.items || []);" +
        "      arr.forEach(function(item) { item._author = extractAuthor(item); });" +
        "      cb(JSON.stringify(arr));" +
        "    });" +
        "  }).catch(function(e) { cb('ERR:' + String(e)); });";

    // ─── JS: poll shadow DOM for d2l-cs-activity elements (15 s timeout) ─────
    // Resolves to "READY:{count}" once activities appear, or "TIMEOUT" if none appear.
    public static final String JS_WAIT_FOR_ACTIVITIES_ASYNC =
        "var cb = arguments[arguments.length-1];" +
        "var deadline = Date.now() + 15000;" +
        "function pierce(root, depth) {" +
        "  if (!root || depth > 15) return 0;" +
        "  var n = root.querySelectorAll('d2l-cs-activity').length;" +
        "  if (n > 0) return n;" +
        "  var all = root.querySelectorAll('*');" +
        "  for (var i = 0; i < all.length; i++) {" +
        "    if (all[i].shadowRoot) { var k = pierce(all[i].shadowRoot, depth+1); if (k > 0) return k; }" +
        "  }" +
        "  return 0;" +
        "}" +
        "function check() {" +
        "  var count = pierce(document, 0);" +
        "  if (count > 0) { cb('READY:' + count); }" +
        "  else if (Date.now() >= deadline) { cb('TIMEOUT'); }" +
        "  else { setTimeout(check, 1000); }" +
        "}" +
        "check();";

    // ─── JS async: collect posts from d2l-cs-activity.activity (ActivityStreams 2.0) ──
    public static final String JS_PIERCE_POSTS_ASYNC =
        "var cb = arguments[arguments.length-1];" +
        "var posts = [];" +
        "var seenIds = new Set();" +
        "function textFromHtml(html) {" +
        "  if (!html) return '';" +
        "  var d = document.createElement('div'); d.innerHTML = html;" +
        "  return (d.textContent || '').replace(/\\s+/g,' ').trim();" +
        "}" +
        "function pierce(root, depth) {" +
        "  if (!root || depth > 15) return;" +
        "  root.querySelectorAll('d2l-cs-activity').forEach(function(el) {" +
        "    var actRaw = el.activity;" +
        "    if (!actRaw) return;" +
        "    var act = {};" +
        "    try { act = typeof actRaw === 'string' ? JSON.parse(actRaw) : actRaw; } catch(e) { return; }" +
        "    var obj     = act.object || {};" +
        "    var uuidRe  = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;" +
        "    var uuidSrc = (obj.id || act.id || (obj.replies && obj.replies.id) || '');" +
        "    var uuidM   = uuidSrc.match(uuidRe);" +
        "    var articleId = uuidM ? uuidM[0] : '';" +
        "    if (!articleId || seenIds.has(articleId)) return;" +
        "    seenIds.add(articleId);" +
        "    var content = obj.content || obj.editableContent || '';" +
        "    var plain   = textFromHtml(content);" +
        "    var title   = (obj.name || obj.title || plain.substring(0, 80) || '(no title)').trim();" +
        "    var date    = act.published || obj.published || '';" +
        "    var actorRaw = act.actor || {};" +
        "    var author  = typeof actorRaw === 'object' ? (actorRaw.name || actorRaw.displayName || '') : '';" +
        "    posts.push({id:articleId, title:title, content:content, author:author, date:date});" +
        "  });" +
        "  root.querySelectorAll('*').forEach(function(el) {" +
        "    if (el.shadowRoot) pierce(el.shadowRoot, depth + 1);" +
        "  });" +
        "}" +
        "pierce(document, 0);" +
        "cb(JSON.stringify(posts));";

    // ─── Date formats used in D2L Brightspace ────────────────────────────────
    public static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssX",     Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss",      Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM yyyy h:mm a",         Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM d, yyyy h:mm a",        Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a",         Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm",           Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm",           Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM yyyy",                Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM d, yyyy",               Locale.ENGLISH)
    );
}
