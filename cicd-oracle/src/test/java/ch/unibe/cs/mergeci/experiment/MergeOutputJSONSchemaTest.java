package ch.unibe.cs.mergeci.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The external-candidates fields are NON_NULL annotated: they must never appear in
 * the serialized JSON of regular RQ2/RQ3 modes (schema stability for plot_results.py
 * and StatisticsReporter), and must appear when set by the external-candidates mode.
 */
class MergeOutputJSONSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void candidateFieldsAbsentForRegularModes() throws Exception {
        MergeOutputJSON output = new MergeOutputJSON();
        output.setMode("cache_parallel");
        MergeOutputJSON.Variant variant = new MergeOutputJSON.Variant();
        variant.setVariantIndex(1);
        output.setVariants(List.of(variant));

        String json = mapper.writeValueAsString(output);
        assertFalse(json.contains("candidate"));
        assertFalse(json.contains("toolVersion"));
        assertFalse(json.contains("toolConfig"));
        assertFalse(json.contains("dedup"));
    }

    @Test
    void candidateFieldsRoundTripWhenSet() throws Exception {
        MergeOutputJSON output = new MergeOutputJSON();
        output.setMode("jdime");
        output.setCandidateComputeSeconds(0.729);
        output.setToolVersion("0.5-develop");
        MergeOutputJSON.Variant variant = new MergeOutputJSON.Variant();
        variant.setVariantIndex(1);
        variant.setCandidateK(0);
        variant.setCandidateStrict(true);
        variant.setCandidateBestEffort(false);
        output.setVariants(List.of(variant));

        String json = mapper.writeValueAsString(output);
        assertTrue(json.contains("\"candidateK\":0"));
        assertTrue(json.contains("\"candidateStrict\":true"));

        MergeOutputJSON back = mapper.readValue(json, MergeOutputJSON.class);
        assertTrue(back.getVariants().get(0).getCandidateStrict());
        assertFalse(back.getVariants().get(0).getCandidateBestEffort());
        assertTrue(back.getCandidateComputeSeconds() == 0.729);
    }

    @Test
    void dedupFieldsAbsentForNonDedupVariants() throws Exception {
        MergeOutputJSON output = new MergeOutputJSON();
        output.setMode("jdime");
        MergeOutputJSON.Variant variant = new MergeOutputJSON.Variant();
        variant.setVariantIndex(1);
        variant.setCandidateK(0);
        output.setVariants(List.of(variant));

        String json = mapper.writeValueAsString(output);
        assertFalse(json.contains("dedup"));
    }

    @Test
    void dedupFieldsPresentWhenSet() throws Exception {
        MergeOutputJSON output = new MergeOutputJSON();
        output.setMode("spork");
        MergeOutputJSON.Variant variant = new MergeOutputJSON.Variant();
        variant.setVariantIndex(1);
        variant.setCandidateK(0);
        variant.setDedupOfMode("jdime");
        variant.setDedupOfVariantIndex(3);
        output.setVariants(List.of(variant));

        String json = mapper.writeValueAsString(output);
        assertTrue(json.contains("\"dedupOfMode\":\"jdime\""));
        assertTrue(json.contains("\"dedupOfVariantIndex\":3"));

        MergeOutputJSON back = mapper.readValue(json, MergeOutputJSON.class);
        assertEquals("jdime", back.getVariants().get(0).getDedupOfMode());
        assertEquals(3, back.getVariants().get(0).getDedupOfVariantIndex());
    }
}
