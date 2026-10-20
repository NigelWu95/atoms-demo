package dev.atoms.demo.projectexecution;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import dev.atoms.demo.source.SourceTree;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Positive Maven model subset plus exact external approval. Never invokes Maven or source wrappers. */
final class ExecutionMavenPolicy {
    private static final String NS = "http://maven.apache.org/POM/4.0.0";
    private static final String WRAPPER = "backend/.mvn/wrapper/maven-wrapper.properties";
    private final String pomSha, wrapperSha, parent, policySha;
    private final Set<String> dependencies;
    record Verified(String pomSha256, String policySha256) {}

    ExecutionMavenPolicy(byte[] approvedPolicy) throws IOException {
        try {
            if (approvedPolicy == null || approvedPolicy.length > 65536) throw rejected();
            var json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(approvedPolicy);
            if (!json.isObject() || !json.path("schema").isInt() || json.path("schema").intValue() != 1) throw rejected();
            JsonNode maven = json.path("maven");
            if (!maven.isObject()) throw rejected();
            var allowed = Set.of("pomSha256", "wrapperPropertiesSha256", "parent", "dependencies");
            for (var keys = maven.fieldNames(); keys.hasNext();) if (!allowed.contains(keys.next())) throw rejected();
            pomSha = jsonText(maven, "pomSha256"); wrapperSha = jsonText(maven, "wrapperPropertiesSha256");
            parent = jsonText(maven, "parent");
            if (!pomSha.matches("[a-f0-9]{64}") || !wrapperSha.matches("[a-f0-9]{64}")
                    || !parent.matches("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[0-9][A-Za-z0-9_.-]*")
                    || parent.contains("SNAPSHOT")) throw rejected();
            JsonNode approved = maven.path("dependencies");
            if (!approved.isArray() || approved.isEmpty() || approved.size() > 64) throw rejected();
            var values = new HashSet<String>();
            for (var value : approved) {
                if (!value.isTextual() || !value.textValue().matches("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:(compile|runtime|test)")
                        || value.textValue().contains("SNAPSHOT") || !values.add(value.textValue())) throw rejected();
            }
            dependencies = Set.copyOf(values); policySha = hash(approvedPolicy);
        } catch (IOException | RuntimeException error) { throw new IOException("EXECUTION_DEPENDENCY_POLICY_INVALID"); }
    }

    Verified check(SourceTree tree) throws IOException {
        byte[] pom = null;
        for (var file : tree.files()) {
            String path = file.path(), lower = path.toLowerCase(Locale.ROOT);
            String name = lower.substring(lower.lastIndexOf('/') + 1);
            if (path.equals("backend/pom.xml")) {
                if (pom != null) throw rejected();
                pom = file.content();
            } else if (name.equals("pom.xml")) throw rejected();
            if (name.equals("settings.xml") || name.equals("toolchains.xml") || name.equals(".mavenrc")) throw rejected();
            if (Arrays.asList(lower.split("/")).contains(".mvn")) {
                if (!path.equals(WRAPPER) || !wrapperSha.equals(hash(file.content()))) throw rejected();
            }
        }
        if (pom == null || !pomSha.equals(hash(pom))) throw new IOException("EXECUTION_MAVEN_APPROVAL_REQUIRED");
        Element root = parse(pom);
        if (!root.getLocalName().equals("project")) throw rejected();
        var model = members(root, Set.of("modelVersion", "parent", "groupId", "artifactId", "version", "name",
                "packaging", "properties", "dependencies", "build", "profiles"));
        requireValue(model, "modelVersion", "4.0.0");
        if (model.containsKey("packaging")) requireValue(model, "packaging", "jar");
        coordinate(model);
        if (model.containsKey("name")) value(model.get("name"));
        var ancestor = members(required(model, "parent"), Set.of("groupId", "artifactId", "version", "relativePath"));
        if (!coordinate(ancestor).equals(parent)) throw rejected();
        requireValue(ancestor, "relativePath", "");
        var properties = members(required(model, "properties"), Set.of("java.version"));
        requireValue(properties, "java.version", "17");
        var actual = new HashSet<String>();
        for (Element dependency : list(required(model, "dependencies"), "dependency")) {
            var fields = members(dependency, Set.of("groupId", "artifactId", "version", "scope"));
            String gav = token(fields, "groupId") + ":" + token(fields, "artifactId") + ":"
                    + (fields.containsKey("version") ? token(fields, "version") : "managed");
            String scope = fields.containsKey("scope") ? token(fields, "scope") : "compile";
            if (!actual.add(gav + ":" + scope)) throw rejected();
        }
        if (!actual.equals(dependencies)) throw rejected();
        build(required(model, "build"));
        profiles(required(model, "profiles"));
        return new Verified(pomSha, policySha);
    }

    private static void build(Element element) throws IOException {
        var build = members(element, Set.of("finalName", "plugins"));
        requireValue(build, "finalName", "app");
        var plugins = new HashSet<String>();
        for (Element plugin : list(required(build, "plugins"), "plugin")) {
            var fields = members(plugin, Set.of("groupId", "artifactId", "configuration"));
            String id = token(fields, "groupId") + ":" + token(fields, "artifactId");
            if (!plugins.add(id)) throw rejected();
            if (id.equals("org.apache.maven.plugins:maven-surefire-plugin")) {
                var config = members(required(fields, "configuration"), Set.of("failIfNoTests"));
                requireValue(config, "failIfNoTests", "true");
            } else if (!id.equals("org.springframework.boot:spring-boot-maven-plugin") || fields.containsKey("configuration"))
                throw rejected();
        }
        if (!plugins.equals(Set.of("org.apache.maven.plugins:maven-surefire-plugin", "org.springframework.boot:spring-boot-maven-plugin")))
            throw rejected();
    }

    private static void profiles(Element element) throws IOException {
        var profiles = list(element, "profile");
        if (profiles.size() != 1) throw rejected();
        var profile = members(profiles.get(0), Set.of("id", "build"));
        requireValue(profile, "id", "full-project-package");
        var build = members(required(profile, "build"), Set.of("resources"));
        var resources = list(required(build, "resources"), "resource");
        if (resources.size() != 2) throw rejected();
        var directories = new HashSet<String>();
        for (var resource : resources) {
            var fields = members(resource, Set.of("directory", "filtering", "includes"));
            String directory = value(required(fields, "directory"));
            if (!directories.add(directory)) throw rejected();
            requireValue(fields, "filtering", "false");
            if (directory.equals("${full-project.resources}")) {
                var includes = list(required(fields, "includes"), "include");
                if (includes.size() != 1 || !value(includes.get(0)).equals("static/**")) throw rejected();
            } else if (!directory.equals("src/main/resources") || fields.containsKey("includes")) throw rejected();
        }
        if (!directories.equals(Set.of("src/main/resources", "${full-project.resources}"))) throw rejected();
    }

    private static Element parse(byte[] bytes) throws IOException {
        try {
            if (bytes.length > (2 << 20)) throw rejected();
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true); factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void error(SAXParseException error) throws SAXParseException { throw error; }
                @Override public void fatalError(SAXParseException error) throws SAXParseException { throw error; }
            });
            return builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
        } catch (Exception error) { throw rejected(); }
    }

    private static List<Element> elements(Element parent) throws IOException {
        if (parent == null || !NS.equals(parent.getNamespaceURI())) throw rejected();
        for (int i = 0; i < parent.getAttributes().getLength(); i++) {
            Node attribute = parent.getAttributes().item(i);
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())
                    && !(parent.getLocalName().equals("project") && XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI.equals(attribute.getNamespaceURI())
                    && attribute.getLocalName().equals("schemaLocation"))) throw rejected();
        }
        var result = new ArrayList<Element>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                if (!NS.equals(element.getNamespaceURI())) throw rejected();
                result.add(element);
            } else if (child.getNodeType() != Node.COMMENT_NODE
                    && (child.getNodeType() != Node.TEXT_NODE || !child.getTextContent().isBlank())) throw rejected();
        }
        return result;
    }
    private static Map<String, Element> members(Element element, Set<String> allowed) throws IOException {
        var members = new LinkedHashMap<String, Element>();
        for (var child : elements(element))
            if (!allowed.contains(child.getLocalName()) || members.putIfAbsent(child.getLocalName(), child) != null) throw rejected();
        return members;
    }
    private static List<Element> list(Element element, String tag) throws IOException {
        var entries = elements(element);
        if (entries.size() > 64 || entries.stream().anyMatch(entry -> !entry.getLocalName().equals(tag))) throw rejected();
        return entries;
    }
    private static String value(Element element) throws IOException {
        if (element == null || !NS.equals(element.getNamespaceURI()) || element.hasAttributes()) throw rejected();
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling())
            if (child.getNodeType() != Node.TEXT_NODE && child.getNodeType() != Node.CDATA_SECTION_NODE
                    && child.getNodeType() != Node.COMMENT_NODE) throw rejected();
        String result = element.getTextContent().strip();
        if (result.length() > 4096) throw rejected();
        return result;
    }
    private static Element required(Map<String, Element> members, String name) throws IOException {
        if (!members.containsKey(name)) throw rejected();
        return members.get(name);
    }
    private static void requireValue(Map<String, Element> members, String key, String expected) throws IOException {
        if (!value(required(members, key)).equals(expected)) throw rejected();
    }
    private static String token(Map<String, Element> members, String key) throws IOException {
        String value = value(required(members, key));
        if (!value.matches("[A-Za-z0-9_.-]+") || value.contains("SNAPSHOT")) throw rejected();
        return value;
    }
    private static String coordinate(Map<String, Element> members) throws IOException {
        return token(members, "groupId") + ":" + token(members, "artifactId") + ":" + token(members, "version");
    }
    private static String jsonText(JsonNode node, String field) throws IOException {
        if (!node.path(field).isTextual() || node.path(field).textValue().length() > 512) throw rejected();
        return node.path(field).textValue();
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static IOException rejected() { return new IOException("EXECUTION_MAVEN_POLICY_REJECTED"); }
}
