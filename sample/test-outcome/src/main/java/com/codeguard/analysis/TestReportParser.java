package com.codeguard.analysis;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads test results produced by the build. JUnit XML is parsed with a hardened StAX reader because the
 * XML is produced by untrusted PR code: DTDs and external entities are disabled and sizes are bounded.
 */
public final class TestReportParser {
    private TestReportParser() {}

    public record Suite(String name, int tests, int failures, int errors, int skipped) {}
    public record Failed(String suite, String name, String message) {}

    /** source: JUNIT_XML, CONSOLE, NONE */
    public record Results(int tests, int failures, int errors, int skipped, List<Suite> suites, List<Failed> failed, String source) {
        public static Results none() { return new Results(0, 0, 0, 0, List.of(), List.of(), "NONE"); }
        public boolean found() { return !"NONE".equals(source); }
        public int passed() { return Math.max(0, tests - failures - errors - skipped); }
    }

    private static final int MAX_FAILED = 200;

    /** Parse many JUnit XML streams into one aggregate result. Unparseable streams are skipped. */
    public static Results parseJUnit(List<InputStream> streams) {
        int tests = 0, failures = 0, errors = 0, skipped = 0;
        List<Suite> suites = new ArrayList<>();
        List<Failed> failed = new ArrayList<>();
        boolean any = false;
        for (InputStream in : streams) {
            try {
                XMLInputFactory f = XMLInputFactory.newFactory();
                f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
                f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
                f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
                XMLStreamReader r = f.createXMLStreamReader(in);
                String suiteName = "";
                String caseName = null, caseClass = null;
                int suiteTests = 0, suiteFail = 0, suiteErr = 0, suiteSkip = 0;
                boolean inSuite = false, countedFromAttrs = false;
                int caseTests = 0, caseFail = 0, caseErr = 0, caseSkip = 0;
                boolean depthOne = false;
                int suiteDepth = 0;
                while (r.hasNext()) {
                    int ev = r.next();
                    if (ev == XMLStreamConstants.START_ELEMENT) {
                        String n = r.getLocalName();
                        if (n.equals("testsuite")) {
                            suiteDepth++;
                            if (suiteDepth == 1) {
                                inSuite = true; any = true;
                                suiteName = nz(r.getAttributeValue(null, "name"));
                                suiteTests = toInt(r.getAttributeValue(null, "tests"));
                                suiteFail = toInt(r.getAttributeValue(null, "failures"));
                                suiteErr = toInt(r.getAttributeValue(null, "errors"));
                                suiteSkip = toInt(r.getAttributeValue(null, "skipped"));
                                suites.add(new Suite(suiteName, suiteTests, suiteFail, suiteErr, suiteSkip));
                                tests += suiteTests; failures += suiteFail; errors += suiteErr; skipped += suiteSkip;
                            }
                        } else if (n.equals("testcase") && inSuite) {
                            caseName = nz(r.getAttributeValue(null, "name"));
                            caseClass = nz(r.getAttributeValue(null, "classname"));
                        } else if ((n.equals("failure") || n.equals("error")) && caseName != null && failed.size() < MAX_FAILED) {
                            failed.add(new Failed(caseClass.isEmpty() ? suiteName : caseClass, caseName,
                                    Code.truncate(nz(r.getAttributeValue(null, "message")), 200)));
                        }
                    } else if (ev == XMLStreamConstants.END_ELEMENT) {
                        String n = r.getLocalName();
                        if (n.equals("testsuite")) { suiteDepth--; if (suiteDepth == 0) inSuite = false; }
                        else if (n.equals("testcase")) caseName = null;
                    }
                }
                r.close();
            } catch (Exception ignored) {
                // corrupt or hostile report: ignore this file, never fail the review
            } finally {
                try { in.close(); } catch (Exception ignored) { }
            }
        }
        return any ? new Results(tests, failures, errors, skipped, suites, failed, "JUNIT_XML") : Results.none();
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static int toInt(String s) {
        try { return s == null ? 0 : Math.max(0, Math.min(1_000_000, (int) Double.parseDouble(s.trim()))); } catch (Exception e) { return 0; }
    }

    // ------------------------------------------------------------------ console output

    private static final Pattern MAVEN = Pattern.compile("Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");
    private static final Pattern JEST = Pattern.compile("(?m)^\\s*Tests:\\s+(.*?)\\s+(\\d+) total");
    private static final Pattern PYTEST_LINE = Pattern.compile("(?m)^(?:=+\\s*)?((?:\\d+ (?:passed|failed|errors?|skipped|xfailed|xpassed|warnings?|deselected)(?:,\\s*)?)+)\\s+in\\s+[\\d.]+s");
    private static final Pattern UNITTEST_RAN = Pattern.compile("(?m)^Ran (\\d+) tests? in [\\d.]+s");
    private static final Pattern UNITTEST_RESULT = Pattern.compile("(?m)^(OK|FAILED)(?: \\(([^)]*)\\))?\\s*$");
    private static final Pattern UNITTEST_FAIL = Pattern.compile("(?m)^(?:FAIL|ERROR): (\\w+) \\(([^)]+)\\)");
    private static final Pattern PYTEST_FAIL = Pattern.compile("(?m)^FAILED (\\S+?)(?: - (.*))?$");
    private static final Pattern CARGO = Pattern.compile("test result: \\w+\\. (\\d+) passed; (\\d+) failed; (\\d+) ignored");
    private static final Pattern GO_FAIL = Pattern.compile("(?m)^--- FAIL: ");
    private static final Pattern GO_PASS = Pattern.compile("(?m)^--- PASS: ");

    public static Results parseConsole(String out) {
        if (out == null || out.isBlank()) return Results.none();
        Matcher m = MAVEN.matcher(out);
        int t = -1, f = 0, e = 0, s = 0;
        while (m.find()) { t = Integer.parseInt(m.group(1)); f = Integer.parseInt(m.group(2)); e = Integer.parseInt(m.group(3)); s = Integer.parseInt(m.group(4)); }
        if (t >= 0) return new Results(t, f, e, s, List.of(), List.of(), "CONSOLE");

        // Maven can exit successfully even when Surefire executes zero tests.
        if (out.matches("(?s).*\\bNo tests to run\\b.*")) {
            return new Results(0, 0, 0, 0, List.of(), List.of(), "CONSOLE");
        }
        Matcher j = JEST.matcher(out);
        if (j.find()) {
            int total = Integer.parseInt(j.group(2));
            return new Results(total, count(j.group(1), "failed"), 0, count(j.group(1), "skipped") + count(j.group(1), "todo"), List.of(), List.of(), "CONSOLE");
        }
        Matcher p = PYTEST_LINE.matcher(out);
        String last = null;
        while (p.find()) last = p.group(1);
        if (last != null) {
            int passed = count(last, "passed"), failed = count(last, "failed"), err = count(last, "error") + count(last, "errors");
            int skip = count(last, "skipped");
            List<Failed> names = new ArrayList<>();
            Matcher pf = PYTEST_FAIL.matcher(out);
            while (pf.find() && names.size() < MAX_FAILED) {
                String id = pf.group(1);
                int sep = id.indexOf("::");
                names.add(new Failed(sep < 0 ? "" : id.substring(0, sep), sep < 0 ? id : id.substring(sep + 2),
                        Code.truncate(pf.group(2) == null ? "" : pf.group(2), 200)));
            }
            return new Results(passed + failed + err + skip, failed, err, skip, List.of(), names, "CONSOLE");
        }
        Matcher ur = UNITTEST_RAN.matcher(out);
        if (ur.find()) {
            int ran = Integer.parseInt(ur.group(1));
            int fails = 0, errs = 0, skips = 0;
            Matcher res = UNITTEST_RESULT.matcher(out);
            String detail = "";
            while (res.find()) detail = res.group(2) == null ? "" : res.group(2);
            Matcher kv = Pattern.compile("(failures|errors|skipped)=(\\d+)").matcher(detail);
            while (kv.find()) {
                int v = Integer.parseInt(kv.group(2));
                if (kv.group(1).equals("failures")) fails = v; else if (kv.group(1).equals("errors")) errs = v; else skips = v;
            }
            List<Failed> names = new ArrayList<>();
            Matcher uf = UNITTEST_FAIL.matcher(out);
            while (uf.find() && names.size() < MAX_FAILED) names.add(new Failed(uf.group(2), uf.group(1), ""));
            return new Results(ran, fails, errs, skips, List.of(), names, "CONSOLE");
        }
        Matcher c = CARGO.matcher(out);
        int ct = 0, cf = 0, ci = 0; boolean cargo = false;
        while (c.find()) { cargo = true; ct += Integer.parseInt(c.group(1)) + Integer.parseInt(c.group(2)); cf += Integer.parseInt(c.group(2)); ci += Integer.parseInt(c.group(3)); }
        if (cargo) return new Results(ct, cf, 0, ci, List.of(), List.of(), "CONSOLE");
        int gf = 0, gp = 0;
        Matcher g1 = GO_FAIL.matcher(out); while (g1.find()) gf++;
        Matcher g2 = GO_PASS.matcher(out); while (g2.find()) gp++;
        if (gf + gp > 0) return new Results(gf + gp, gf, 0, 0, List.of(), List.of(), "CONSOLE");
        return Results.none();

    }

    private static int count(String text, String word) {
        Matcher m = Pattern.compile("(\\d+) " + word).matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** Detects output that means "the build could not even fetch dependencies" so the reason is shown honestly. */
    public static boolean looksLikeOfflineDependencyFailure(String out) {
        if (out == null) return false;
        String o = out.toLowerCase();
        return o.contains("could not resolve dependencies") || o.contains("unknownhostexception") || o.contains("temporary failure in name resolution")
                || o.contains("could not transfer artifact") || o.contains("network is unreachable") || o.contains("enotfound")
                || o.contains("could not resolve host") || o.contains("failed to resolve");
    }
}
