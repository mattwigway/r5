package com.conveyal.r5.analyst.fare.nyc;

import com.conveyal.r5.analyst.fare.TransferAllowance;

/**
 * A transfer allowance for NYC. This has a bunch of sub-transfer
 */

public class NYCTransferAllowance extends TransferAllowance {
    public final LIRRTransferAllowance lirr;
    private final NYCInRoutingFareCalculator.NYCPatternType metrocardTransferSource;
    private final int metrocardTransferExpiry;
    private boolean leftSubwayPaidArea;

    public NYCTransferAllowance(LIRRTransferAllowance lirr, NYCInRoutingFareCalculator.NYCPatternType metrocardTransferSource,
                                int metrocardTransferExpiry, boolean leftSubwayPaidArea) {
        // only the value needs to be set correctly. The expiration time and number of transfers left are only used in
        // the second domination rule, which we override in atLeastAsGoodForAllFutureRedemptions
        super(computeMaxTransferAllowance(lirr, metrocardTransferSource), Integer.MAX_VALUE, Integer.MAX_VALUE);
        this.lirr = lirr;
        this.metrocardTransferSource = metrocardTransferSource;
        this.metrocardTransferExpiry = metrocardTransferExpiry;
        this.leftSubwayPaidArea = leftSubwayPaidArea;
    }

    /**
     * Compute max transfer allowance. Static single function to trick JVM into letting us run it in the constructor
     * before the super() call.
     *
     * Note that we are adding the max transfer allowance from each different type of ticket, because max transfer
     * allowance is for the entire suffix, and you could conceivably hold multiple transfer slips simultaneously.
     */
    private static int computeMaxTransferAllowance (LIRRTransferAllowance lirr, NYCInRoutingFareCalculator.NYCPatternType metrocardTransferSource) {
        int maxTransferAllowance = 0;

        // adding these is correct because you could conceivably get multiple independent transfers from one prefix, e.g.
        // another LIRR transfer and then a metrocard transfer held over from before. Remember in that paper that max
        // transfer allowance is max for any suffix, not for any single transfer.
        if (lirr != null) maxTransferAllowance += lirr.getMaxTransferAllowance();
        if (metrocardTransferSource != null) {
            maxTransferAllowance +=
                    NYCStaticFareData.METROCARD_TRANSFER_ALLOWANCE_FOR_PATTERN_TYPE.get(metrocardTransferSource);
        }

        return maxTransferAllowance;
    }


    @Override
    public boolean atLeastAsGoodForAllFutureRedemptions(TransferAllowance other) {
        if (other instanceof NYCTransferAllowance) {
            NYCTransferAllowance o = (NYCTransferAllowance) other;
            if (lirr != null && !lirr.atLeastAsGoodForAllFutureRedemptions(o.lirr)) return false;
            if (lirr == null && o.lirr != null) return false; // can't be as good

            // if other is null this is better
            if (metrocardTransferSource != o.metrocardTransferSource && o.metrocardTransferSource != null) return false;
            // NB local bus is not strictly better than subway, because subway has no expiration time for within-system
            // transfers. Although for optimization this might not matter due to cutoff time.
            if (metrocardTransferExpiry < o.metrocardTransferExpiry) return false; // expires sooner
            if (!leftSubwayPaidArea && o.leftSubwayPaidArea) return false; // free transfer with this and not with other

            // if we got here, we're good
            return true;
        } else {
            throw new IllegalArgumentException("Mixing of transfer allowance types!");
        }
    }

}
