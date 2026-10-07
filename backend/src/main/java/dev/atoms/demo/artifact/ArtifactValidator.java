package dev.atoms.demo.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import dev.atoms.demo.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import org.jsoup.Jsoup;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

@Component
public class ArtifactValidator {
    public static final int SOURCE_LIMIT = 120 * 1024;
    public static final int STATE_LIMIT = 256 * 1024;
    private static final Set<String> FILES = Set.of("view.html", "styles.css", "main.js");
    // No URL attributes, forms, inline events, foreignObject, or nested browsing contexts.
    // Source: https://jsoup.org/apidocs/org/jsoup/safety/Cleaner#isValidBodyHtml(java.lang.String)
    private final Cleaner cleaner = new Cleaner(new Safelist()
            .addTags("main", "section", "article", "header", "footer", "nav", "aside", "div", "span", "p",
                    "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "dl", "dt", "dd", "br", "hr",
                    "strong", "em", "b", "i", "small", "pre", "code", "blockquote", "button", "input", "label",
                    "textarea", "select", "option", "fieldset", "legend", "table", "thead", "tbody", "tfoot",
                    "tr", "th", "td", "caption", "details", "summary", "progress", "output",
                    "svg", "g", "path", "circle", "rect", "line", "polyline", "polygon", "ellipse", "text")
            .addAttributes(":all", "id", "class", "title", "role", "aria-label", "aria-labelledby", "aria-describedby",
                    "aria-live", "aria-hidden", "aria-checked", "aria-pressed", "aria-expanded", "aria-controls", "hidden", "tabindex")
            .addAttributes("input", "type", "value", "placeholder", "checked", "disabled", "min", "max", "step", "maxlength", "name")
            .addAttributes("button", "type", "disabled")
            .addAttributes("label", "for").addAttributes("textarea", "placeholder", "rows", "cols", "maxlength", "disabled")
            .addAttributes("select", "disabled", "multiple").addAttributes("option", "value", "selected", "disabled")
            .addAttributes("th", "scope", "colspan", "rowspan").addAttributes("td", "colspan", "rowspan")
            .addAttributes("details", "open").addAttributes("progress", "value", "max")
            .addAttributes("svg", "viewbox", "width", "height", "fill", "stroke", "xmlns")
            .addAttributes("g", "fill", "stroke", "transform")
            .addAttributes("path", "d", "fill", "stroke", "stroke-width", "transform")
            .addAttributes("circle", "cx", "cy", "r", "fill", "stroke", "stroke-width")
            .addAttributes("rect", "x", "y", "width", "height", "rx", "fill", "stroke")
            .addAttributes("line", "x1", "y1", "x2", "y2", "stroke", "stroke-width")
            .addAttributes("polyline", "points", "fill", "stroke").addAttributes("polygon", "points", "fill", "stroke")
            .addAttributes("ellipse", "cx", "cy", "rx", "ry", "fill", "stroke")
            .addAttributes("text", "x", "y", "fill"));

    public Artifact parse(JsonNode input) {
        fields(input, Set.of("formatVersion", "manifest", "files"));
        integerOne(input.path("formatVersion"));
        var manifest = input.path("manifest");
        fields(manifest, Set.of("title", "summary", "runtime", "stateSchemaVersion", "initialState"));
        String title = text(manifest.path("title"), 120);
        String summary = text(manifest.path("summary"), 2000);
        if (!manifest.path("runtime").isTextual() || !"web-static-v1".equals(manifest.path("runtime").textValue())) throw invalid();
        integerOne(manifest.path("stateSchemaVersion"));
        JsonNode initial = manifest.path("initialState");
        if (!initial.isObject() || bytes(initial.toString()) > STATE_LIMIT) throw invalid();
        var files = input.path("files");
        fields(files, FILES);
        var sources = new TreeMap<String, String>();
        int total = 0;
        for (String file : FILES) {
            JsonNode value = files.path(file);
            if (!value.isTextual()) throw invalid();
            String source = value.textValue();
            total += bytes(source);
            if (total > SOURCE_LIMIT || source.indexOf('\0') >= 0) throw invalid();
            sources.put(file, source);
        }
        body(sources.get("view.html"));
        return new Artifact(1, new Artifact.Manifest(title, summary, "web-static-v1", 1, initial.deepCopy()), sources);
    }

    public String body(String source) {
        if (bytes(source) > SOURCE_LIMIT || !cleaner.isValidBodyHtml(source)) throw invalid();
        // isValid is not a substitute for normalizing parser/browser differences before rendering.
        var document = cleaner.clean(Jsoup.parseBodyFragment(source));
        document.outputSettings().prettyPrint(false);
        return document.body().html();
    }
    private void fields(JsonNode value, Set<String> expected) {
        var actual = new HashSet<String>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!value.isObject() || !actual.equals(expected)) throw invalid();
    }
    private void integerOne(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() != 1) throw invalid();
    }
    private String text(JsonNode value, int max) {
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > max
                || value.textValue().codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return value.textValue();
    }
    private int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private ApiException invalid() {
        return new ApiException(422, "INVALID_ARTIFACT", "产物格式不支持：仅允许受限 HTML、CSS、经典脚本，源码总量 120 KiB，初始数据 256 KiB。");
    }
}
