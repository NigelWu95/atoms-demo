package dev.atoms.standalone;

import com.fasterxml.jackson.databind.JsonNode;

record StateDocument(JsonNode value, long revision, int stateSchemaVersion) {
    StateDocument { value = value.deepCopy(); }
    @Override public JsonNode value() { return value.deepCopy(); }
}
