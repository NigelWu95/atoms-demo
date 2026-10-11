package dev.atoms.standalone;

import org.jsoup.Jsoup;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

/** Frozen web-static-v1 allowlist, mirrored from the platform ArtifactValidator. */
final class ApplicationHtml {
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

    String normalize(String source) {
        if (!cleaner.isValidBodyHtml(source)) throw ApplicationArtifactLoader.invalid();
        var document = cleaner.clean(Jsoup.parseBodyFragment(source));
        document.outputSettings().prettyPrint(false);
        return document.body().html();
    }
}
