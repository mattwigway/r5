package com.conveyal.r5.analyst.fare.nyc;

import junit.framework.TestCase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

public class LIRRTransferAllowanceTest extends TestCase {
    @Test
    public void testLirrFares () {
        // Journey from Syosset to Hempstead via Jamaica and East New York
        // yes, you'd ordinarily just change at Jamaica, but for some reason the router thinks this is cheaper (a bug)
        // Fare should be 14 for Syosset to E NY at peak + 9.25 offpeak for E NY to Hempstead= 23.25 (no via fares here)
        assertEquals(2325, computeFare(
                new LIRRStop[] { LIRRStop.LIRR205, LIRRStop.LIRR102, LIRRStop.LIRR50, LIRRStop.LIRR84 },
                new boolean[] { true, true, false }
        ).cumulativeFare);
    }

    private LIRRTransferAllowance computeFare (LIRRStop[] stops, boolean[] peak) {
        List<LIRRStop> boardStops = Arrays.asList(Arrays.copyOfRange(stops, 0, stops.length - 1));
        List<LIRRStop> alightStops = Arrays.asList(Arrays.copyOfRange(stops, 1, stops.length));
        BitSet peakBitset = new BitSet();
        for (int i = 0; i < peak.length; i++) peakBitset.set(i, peak[i]);

        List<LIRRTransferAllowance.LIRRDirection> directions = new ArrayList<>();

        for (int i = 0; i < boardStops.size(); i++) {
            LIRRStop fromStop = boardStops.get(i);
            LIRRStop toStop = alightStops.get(i);
            if (LIRRTransferAllowance.inboundDownstreamStops.containsEntry(fromStop, toStop))
                directions.add(LIRRTransferAllowance.LIRRDirection.INBOUND);
            else if (LIRRTransferAllowance.outboundDownstreamStops.containsEntry(fromStop, toStop))
                directions.add(LIRRTransferAllowance.LIRRDirection.OUTBOUND);
            else throw new IllegalArgumentException("No single-direction path from board stop to alight stop!");
        }

        return new LIRRTransferAllowance(boardStops, alightStops, directions, peakBitset);
    }
}
