package com.ecrharv.harvester.scraping;

/**
 * Single source of truth for every Librus-specific string used during scraping:
 * portal URLs, login form IDs, CSS selectors, Polish label keys, and
 * attendance status codes.
 *
 * If Librus updates its UI, this is the only file that needs to change.
 */
public final class LibrusKeys {

    private LibrusKeys() {}

    /** Domain fragment used to confirm successful post-login redirect. */
    public static final String DOMAIN_SYNERGIA = "synergia.librus.pl";

    // ── Login form element IDs ────────────────────────────────────────────────

    public static final String LOGIN_FIELD_ID  = "Login";
    public static final String PASS_FIELD_ID   = "Pass";
    public static final String SUBMIT_BTN_ID   = "LoginBtn";

    // ── Cookie / GDPR consent — tried in order before login form ─────────────
    // XPath covers the most common Polish portal consent button texts.
    public static final String XPATH_COOKIE_CONSENT =
        "//button[contains(., 'Zgadzam') or contains(., 'Akceptuj') or " +
        "contains(., 'Accept') or contains(., 'zgadzam') or contains(., 'akceptuj')]";

    // ── Portal landing page — dropdown flow before the login form ─────────────
    /** The Synergia dropdown toggle on portal.librus.pl/rodzina. */
    public static final String SEL_SYNERGIA_BTN   = "a.btn-synergia-top";
    /** "Zaloguj" link that appears inside the Synergia dropdown. */
    public static final String XPATH_ZALOGUJ_LINK = "//a[normalize-space()='Zaloguj']";

    // ── CSS selectors ─────────────────────────────────────────────────────────

    // Student info box (present on the grades page)
    public static final String SEL_STUDENT_INFO = "div.container-icon p";

    // Grades
    public static final String SEL_GRADES_TABLE = "table.decorated";
    public static final String SEL_GRADES_ROWS  = "table.decorated tbody tr";
    public static final String SEL_GRADE_SPAN   = "span.ocenaCzastkowa, a.ocenaCzastkowa";

    // Messages list
    public static final String SEL_MSG_TABLE    = "table.decorated";
    public static final String SEL_MSG_ROWS     = "table.decorated tbody tr";
    public static final String SEL_MSG_LINK     = "td a";

    // Message detail page
    public static final String SEL_MSG_DETAIL_CONTAINER = ".container-message, table.stretch";
    public static final String SEL_MSG_DETAIL_CONTENT   = ".container-message-content";

    // Announcements — each announcement is a self-contained table on the list page (no detail page)
    public static final String SEL_ANN_TABLE     = "table.decorated";
    public static final String ANN_LABEL_AUTHOR  = "Dodał";
    public static final String ANN_LABEL_ROLE    = "Stanowisko";
    public static final String ANN_LABEL_DATE    = "Data publikacji";
    public static final String ANN_LABEL_CONTENT = "Treść";

    // Attendance
    public static final String SEL_ATTENDANCE_TABLE = "table.center";
    public static final String SEL_ATTENDANCE_ROWS  = "table.center tbody tr";

    // ── Grade tooltip attribute keys (Polish labels inside the title attr) ────

    public static final String GRADE_KEY_CATEGORY = "Kategoria:";
    public static final String GRADE_KEY_WEIGHT   = "Waga:";
    public static final String GRADE_KEY_DATE      = "Data:";
    public static final String GRADE_KEY_TEACHER   = "Nauczyciel:";

    // ── Fallback/default values ───────────────────────────────────────────────

    public static final String DEFAULT_CATEGORY = "Nieznana";
    public static final String DEFAULT_UNKNOWN  = "Nieznany";

    // ── Message detail page — table row labels ────────────────────────────────

    public static final String MSG_LABEL_SENDER  = "Nadawca";
    public static final String MSG_LABEL_SUBJECT = "Temat";
    public static final String MSG_LABEL_DATE    = "Data wysłania";

    /** Folder suffix for received messages. */
    public static final String MSG_INBOX_SUFFIX = "/5";

    /** Folder suffix for sent messages. */
    public static final String MSG_SENT_SUFFIX = "/6";

    /** Fragment matched against message hrefs to confirm they point to message items. */
    public static final String MSG_HREF_FRAGMENT = "wiadomosci";

    /** Regex that extracts the numeric message ID from a Librus message URL. */
    public static final String MSG_ID_PATTERN = ".*/([0-9]+).*";

    // ── Attendance status codes (Librus Polish abbreviations / full words) ────

    public static final String ATTENDANCE_PRESENT_SHORT = "ob";
    public static final String ATTENDANCE_PRESENT_FULL  = "obecność";
    public static final String ATTENDANCE_LATE_SHORT    = "sp";
    public static final String ATTENDANCE_LATE_FULL     = "spóźnienie";
    public static final String ATTENDANCE_EXCUSED_SHORT = "u";
    public static final String ATTENDANCE_EXCUSED_FULL  = "usprawiedliwienie";
}
