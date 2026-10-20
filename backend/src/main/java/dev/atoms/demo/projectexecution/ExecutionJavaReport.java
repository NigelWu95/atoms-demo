package dev.atoms.demo.projectexecution;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.stream.*;

/** Numeric evidence only. The caller must establish report freshness and file/stop identity first. */
final class ExecutionJavaReport {
    static final int FILE_MAX_BYTES = 1 << 20, TOTAL_MAX_BYTES = 16 << 20, MAX_FILES = 512, MAX_TESTS = 10000;
    record Summary(int files, int tests) {}

    static Summary parse(List<byte[]> reports) throws IOException {
        try {
            if (reports == null || reports.isEmpty() || reports.size() > MAX_FILES) throw invalid();
            long bytes = 0;
            for (byte[] report : reports) {
                if (report == null || report.length == 0 || report.length > FILE_MAX_BYTES
                        || (bytes += report.length) > TOTAL_MAX_BYTES) throw invalid();
            }
            int tests = 0;
            for (byte[] report : reports) if ((tests += suite(report)) > MAX_TESTS) throw invalid();
            return new Summary(reports.size(), tests);
        } catch (IOException | XMLStreamException | RuntimeException error) { throw invalid(); }
    }

    // Surefire 3.5.6 format, including nested output and rerun/flaky failures:
    // https://github.com/apache/maven-surefire/blob/surefire-3.5.6/maven-surefire-common/src/main/java/org/apache/maven/plugin/surefire/report/StatelessXmlReporter.java
    private static int suite(byte[] bytes) throws XMLStreamException, IOException {
        // Built-in JDK provider, no schema fetching, DTDs or entity expansion.
        // https://docs.oracle.com/en/java/javase/17/docs/api/java.xml/javax/xml/stream/XMLInputFactory.html
        var factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setXMLResolver((publicId, systemId, base, namespace) -> { throw new XMLStreamException(); });
        var xml = factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
        try {
            var elements = new ArrayDeque<String>();
            int expected = -1, observed = 0, events = 0;
            boolean properties = false;
            while (xml.hasNext()) {
                if (++events > 100_000) throw invalid();
                int event = xml.next();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) throw invalid();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = xml.getLocalName(), parent = elements.peek();
                    if (xml.getNamespaceURI() != null && !xml.getNamespaceURI().isEmpty()) throw invalid();
                    if (parent == null) {
                        if (!name.equals("testsuite") || expected != -1) throw invalid();
                        expected = count(xml, "tests");
                        if (expected == 0 || count(xml, "failures") != 0 || count(xml, "errors") != 0
                                || count(xml, "skipped") != 0) throw invalid();
                    } else if (parent.equals("testsuite")) {
                        if (name.equals("testcase")) {
                            if (++observed > MAX_TESTS) throw invalid();
                            String test = xml.getAttributeValue(null, "name");
                            if (test == null || test.isBlank()) throw invalid();
                        } else if (name.equals("properties")) {
                            if (properties) throw invalid();
                            properties = true;
                        } else if (!output(name)) throw invalid();
                    } else if (parent.equals("properties")) {
                        if (!name.equals("property")) throw invalid();
                    } else if (!parent.equals("testcase") || !output(name)) {
                        throw invalid(); // Includes skipped/failure/error/rerun/flaky and unknown nested elements.
                    }
                    elements.push(name);
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    elements.pop();
                } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                        && !xml.isWhiteSpace() && !output(elements.peek())) {
                    throw invalid();
                }
            }
            if (!elements.isEmpty() || expected <= 0 || observed != expected) throw invalid();
            return observed;
        } finally { xml.close(); }
    }

    private static boolean output(String name) { return "system-out".equals(name) || "system-err".equals(name); }
    private static int count(XMLStreamReader xml, String key) throws IOException {
        String value = xml.getAttributeValue(null, key);
        if (value == null || !value.matches("[0-9]{1,5}")) throw invalid();
        int count = Integer.parseInt(value);
        if (count > MAX_TESTS) throw invalid();
        return count;
    }
    private static IOException invalid() { return new IOException("EXECUTION_REPORT_INVALID"); }
}
