package ai.shashu.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Reading {@code inspection} off a decision, {@code detectors.memory} included.
 *
 * <p>Sibling of the Python SDK's {@code Decision.inspection} and the Node SDK's {@code
 * decision.inspection}: the same shape — {@code input}, {@code output}, {@code reason} and a {@code
 * detectors} map — whether it was read off the body or off the {@code x-shashu-inspection} header.
 */
class InspectionTest {

  static String body(String inspection) {
    return "{\"shashu\":{\"decision\":\"allow\",\"id\":\"r1\""
        + (inspection == null ? "" : ",\"inspection\":" + inspection)
        + "}}";
  }

  @SuppressWarnings("unchecked")
  static String memory(ShashuDecision d) {
    Map<String, String> detectors = (Map<String, String>) d.inspection().get("detectors");
    return detectors == null ? null : detectors.get("memory");
  }

  @Test
  void body_memory_detector_is_readable_for_each_state() {
    for (String state : new String[] {"checked", "partial", "skipped"}) {
      ShashuDecision d =
          Shashu.decisionFrom(
              body(
                  "{\"input\":\"checked\",\"output\":\"checked\",\"detectors\":{\"memory\":\""
                      + state
                      + "\"}}"),
              null);
      assertEquals(state, memory(d));
      assertEquals("checked", d.inspection().get("input"));
    }
  }

  @Test
  void header_memory_detector_is_collected_under_detectors() {
    Map<String, String> h = new LinkedHashMap<>();
    h.put("x-shashu-decision", "allow");
    h.put(
        "x-shashu-inspection",
        "input=checked;output=skipped;reason=adapter_unreachable;memory=partial");
    ShashuDecision d = Shashu.decisionFrom(null, h);
    assertEquals("partial", memory(d));
    assertEquals("skipped", d.inspection().get("output"));
    assertEquals("adapter_unreachable", d.inspection().get("reason"));
  }

  @Test
  void absent_inspection_is_null_and_no_detectors_is_null_memory() {
    assertNull(Shashu.decisionFrom(body(null), null).inspection());
    assertNull(
        memory(Shashu.decisionFrom(body("{\"input\":\"checked\",\"output\":\"checked\"}"), null)));
  }

  @Test
  void unknown_states_pass_through_and_the_map_is_read_only() {
    ShashuDecision d =
        Shashu.decisionFrom(
            body(
                "{\"input\":\"checked\",\"output\":\"checked\",\"detectors\":{\"memory\":\"brand_new\",\"files\":7}}"),
            null);
    assertEquals("brand_new", memory(d));
    assertThrows(UnsupportedOperationException.class, () -> d.inspection().put("input", "x"));
  }
}
