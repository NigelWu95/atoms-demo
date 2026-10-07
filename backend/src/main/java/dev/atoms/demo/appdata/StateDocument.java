package dev.atoms.demo.appdata;

import com.fasterxml.jackson.databind.JsonNode;

public record StateDocument(JsonNode value, long revision, int stateSchemaVersion) {}
