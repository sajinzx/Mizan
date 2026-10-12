package com.sajin.mizan.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.Instrument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Reads and writes the genesis JSON of §5.8 (M6-T2). Public keys are base64 X.509 (44 bytes). Every
 * field is required; unknown fields are rejected so typos do not silently fall back to defaults.
 */
public final class GenesisJson {
  private static final ObjectMapper JSON = new ObjectMapper();

  private GenesisJson() {}

  public static GenesisConfig read(Path file) throws IOException {
    return parse(Files.readString(file));
  }

  public static GenesisConfig parse(String text) throws IOException {
    try {
      return build(JSON.readTree(text));
    } catch (IllegalArgumentException e) {
      throw new IOException("invalid genesis: " + e.getMessage(), e);
    }
  }

  private static GenesisConfig build(JsonNode root) throws IOException {
    expectFields(
        root,
        "genesis",
        "chainId",
        "stateRootVersion",
        "adminPubKey",
        "assets",
        "instruments",
        "validators",
        "block",
        "consensus");
    List<GenesisConfig.Asset> assets = new ArrayList<>();
    for (JsonNode a : array(root, "assets")) {
      expectFields(a, "asset", "id", "name");
      assets.add(new GenesisConfig.Asset(integer(a, "id"), text(a, "name")));
    }
    List<Instrument> instruments = new ArrayList<>();
    for (JsonNode i : array(root, "instruments")) {
      expectFields(
          i,
          "instrument",
          "id",
          "base",
          "quote",
          "tick",
          "lot",
          "makerBps",
          "takerBps",
          "maxPrice",
          "maxQty");
      instruments.add(
          new Instrument(
              integer(i, "id"),
              integer(i, "base"),
              integer(i, "quote"),
              longValue(i, "tick"),
              longValue(i, "lot"),
              integer(i, "makerBps"),
              integer(i, "takerBps"),
              longValue(i, "maxPrice"),
              longValue(i, "maxQty")));
    }
    List<GenesisConfig.Validator> validators = new ArrayList<>();
    for (JsonNode v : array(root, "validators")) {
      expectFields(v, "validator", "index", "pubKey", "host", "port");
      validators.add(
          new GenesisConfig.Validator(
              integer(v, "index"), key(v, "pubKey"), text(v, "host"), integer(v, "port")));
    }
    JsonNode b = root.get("block");
    expectFields(b, "block", "maxCommands", "maxIntervalMs", "maxInflight");
    JsonNode c = root.get("consensus");
    expectFields(c, "consensus", "proposeTimeoutMs", "timeoutDeltaMs");
    try {
      return new GenesisConfig(
          integer(root, "chainId"),
          integer(root, "stateRootVersion"),
          key(root, "adminPubKey"),
          assets,
          instruments,
          validators,
          new GenesisConfig.BlockParams(
              integer(b, "maxCommands"), longValue(b, "maxIntervalMs"), integer(b, "maxInflight")),
          new GenesisConfig.ConsensusParams(
              longValue(c, "proposeTimeoutMs"), longValue(c, "timeoutDeltaMs")));
    } catch (IllegalArgumentException e) {
      throw new IOException("invalid genesis: " + e.getMessage(), e);
    }
  }

  public static String toJson(GenesisConfig g) throws IOException {
    ObjectNode root = JSON.createObjectNode();
    Base64.Encoder b64 = Base64.getEncoder();
    root.put("chainId", g.chainId());
    root.put("stateRootVersion", g.stateRootVersion());
    root.put("adminPubKey", b64.encodeToString(g.adminPubKey()));
    ArrayNode assets = root.putArray("assets");
    g.assets().forEach(a -> assets.addObject().put("id", a.id()).put("name", a.name()));
    ArrayNode instruments = root.putArray("instruments");
    for (Instrument i : g.instruments()) {
      instruments
          .addObject()
          .put("id", i.id())
          .put("base", i.base())
          .put("quote", i.quote())
          .put("tick", i.tick())
          .put("lot", i.lot())
          .put("makerBps", i.makerBps())
          .put("takerBps", i.takerBps())
          .put("maxPrice", i.maxPrice())
          .put("maxQty", i.maxQty());
    }
    ArrayNode validators = root.putArray("validators");
    g.validators()
        .forEach(
            v ->
                validators
                    .addObject()
                    .put("index", v.index())
                    .put("pubKey", b64.encodeToString(v.pubKey()))
                    .put("host", v.host())
                    .put("port", v.port()));
    root.putObject("block")
        .put("maxCommands", g.block().maxCommands())
        .put("maxIntervalMs", g.block().maxIntervalMs())
        .put("maxInflight", g.block().maxInflight());
    root.putObject("consensus")
        .put("proposeTimeoutMs", g.consensus().proposeTimeoutMs())
        .put("timeoutDeltaMs", g.consensus().timeoutDeltaMs());
    return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
  }

  public static void write(GenesisConfig g, Path file) throws IOException {
    Files.writeString(file, toJson(g) + System.lineSeparator());
  }

  private static void expectFields(JsonNode n, String what, String... names) throws IOException {
    if (n == null || !n.isObject()) {
      throw new IOException(what + " must be an object");
    }
    List<String> allowed = List.of(names);
    var it = n.fieldNames();
    while (it.hasNext()) {
      String f = it.next();
      if (!allowed.contains(f)) {
        throw new IOException("unknown field '" + f + "' in " + what);
      }
    }
    for (String f : names) {
      if (!n.has(f)) {
        throw new IOException("missing field '" + f + "' in " + what);
      }
    }
  }

  private static JsonNode array(JsonNode n, String f) throws IOException {
    JsonNode a = n.get(f);
    if (!a.isArray()) {
      throw new IOException("'" + f + "' must be an array");
    }
    return a;
  }

  private static int integer(JsonNode n, String f) throws IOException {
    JsonNode v = n.get(f);
    if (!v.canConvertToInt() || !v.isIntegralNumber()) {
      throw new IOException("'" + f + "' must be a 32-bit integer");
    }
    return v.intValue();
  }

  private static long longValue(JsonNode n, String f) throws IOException {
    JsonNode v = n.get(f);
    if (!v.canConvertToLong() || !v.isIntegralNumber()) {
      throw new IOException("'" + f + "' must be an integer");
    }
    return v.longValue();
  }

  private static String text(JsonNode n, String f) throws IOException {
    JsonNode v = n.get(f);
    if (!v.isTextual()) {
      throw new IOException("'" + f + "' must be a string");
    }
    return v.textValue();
  }

  private static byte[] key(JsonNode n, String f) throws IOException {
    try {
      byte[] k = Base64.getDecoder().decode(text(n, f));
      if (k.length != 44) {
        throw new IOException("'" + f + "' must be a 44-byte X.509 Ed25519 key");
      }
      return k;
    } catch (IllegalArgumentException e) {
      throw new IOException("'" + f + "' is not valid base64", e);
    }
  }
}
