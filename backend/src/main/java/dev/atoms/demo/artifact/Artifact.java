package dev.atoms.demo.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

public record Artifact(int formatVersion, Manifest manifest, Map<String, String> files) {
    public record Manifest(String title, String summary, String runtime, int stateSchemaVersion, JsonNode initialState) {}
}
