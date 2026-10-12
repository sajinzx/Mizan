package com.sajin.mizan.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajin.mizan.chain.GenesisAnchor;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.sim.DevGenesis;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/** M6-T2: genesis JSON and the height-0 anchor. */
class GenesisJsonTest {

  @Test
  void roundTripPreservesTheAnchor() throws IOException {
    GenesisConfig g = DevGenesis.config(4);
    String json = GenesisJson.toJson(g);
    GenesisConfig back = GenesisJson.parse(json);
    assertThat(GenesisJson.toJson(back)).isEqualTo(json);
    assertThat(GenesisAnchor.hash(back)).isEqualTo(GenesisAnchor.hash(g));
    assertThat(back.validators()).hasSize(4);
    assertThat(back.instruments()).isEqualTo(g.instruments());
  }

  @Test
  void anchorDependsOnChainIdAndGenesisState() throws IOException {
    GenesisConfig g = DevGenesis.config();
    String json = GenesisJson.toJson(g);
    GenesisConfig other =
        GenesisJson.parse(json.replace("\"chainId\" : 4242", "\"chainId\" : 4243"));
    assertThat(GenesisAnchor.hash(other)).isNotEqualTo(GenesisAnchor.hash(g));
    GenesisConfig fees =
        GenesisJson.parse(json.replaceFirst("\"takerBps\" : 10", "\"takerBps\" : 11"));
    assertThat(fees.instruments().get(0).takerBps()).isEqualTo(11);
  }

  @Test
  void strictParsing() throws IOException {
    String json = GenesisJson.toJson(DevGenesis.config());
    assertThatThrownBy(() -> GenesisJson.parse(json.replace("\"maxInflight\"", "\"maxInFlight\"")))
        .hasMessageContaining("unknown field");
    assertThatThrownBy(() -> GenesisJson.parse(json.replace("\"stateRootVersion\" : 1,", "")))
        .hasMessageContaining("missing field");
    assertThatThrownBy(
            () ->
                GenesisJson.parse(
                    json.replaceFirst(
                        "\"adminPubKey\" : \"[^\"]+\"", "\"adminPubKey\" : \"AAAA\"")))
        .hasMessageContaining("44-byte");
    assertThatThrownBy(() -> GenesisJson.parse(json.replace("\"tick\" : 1", "\"tick\" : 0")))
        .hasMessageContaining("invalid genesis");
    assertThatThrownBy(
            () -> GenesisJson.parse(json.replace("\"chainId\" : 4242", "\"chainId\" : \"x\"")))
        .hasMessageContaining("integer");
  }
}
