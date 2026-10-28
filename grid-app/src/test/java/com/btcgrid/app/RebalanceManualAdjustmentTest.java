package com.btcgrid.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RebalanceManualAdjustmentTest {
    @Test void classifiesOnlyExplicitManualOrdersOrMetadata() {
        assertTrue(RebalanceManualAdjustment.isManual("RBAL_MANUAL_add", null));
        assertTrue(RebalanceManualAdjustment.isManual("RBAL_WATER_legacy", "{\"manualPositionAdjustment\":true}"));
        assertFalse(RebalanceManualAdjustment.isManual("RBAL_WATER_auto", null));
        assertFalse(RebalanceManualAdjustment.isManual("RBAL_grid", "{\"manualPositionAdjustment\":false}"));
        assertFalse(RebalanceManualAdjustment.isManual("RBAL_grid", "{\"manualPositionAdjustment\":\"true\"}"));
        assertFalse(RebalanceManualAdjustment.isManual(null, "legacy not json"));
        assertFalse(RebalanceManualAdjustment.isManual(null, "null"));
    }

    @Test void manualMetadataKeepsExistingAccountingAndRoundingData() throws Exception {
        var mapper = new ObjectMapper();
        String marked = RebalanceManualAdjustment.markMetadata("{\"buyGridRoundingCarryQty\":0.00020633406,\"targetRatio\":1}");
        var data = mapper.readTree(marked);
        assertTrue(data.path("manualPositionAdjustment").booleanValue());
        assertEquals("0.00020633406", data.path("buyGridRoundingCarryQty").decimalValue().toPlainString());
        assertEquals(1, data.path("targetRatio").intValue());
        assertEquals(data, mapper.readTree(RebalanceManualAdjustment.markMetadata(marked)));
        assertTrue(mapper.readTree(RebalanceManualAdjustment.markMetadata(null)).path("manualPositionAdjustment").booleanValue());
        String exact = RebalanceManualAdjustment.markMetadata("{\"carry\":0.000206334060000000000000001}");
        assertTrue(exact.contains("0.000206334060000000000000001"));
    }
}
