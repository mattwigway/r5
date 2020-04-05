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
        super(lirr != null ? lirr.getMaxTransferAllowance() : 0, Integer.MAX_VALUE, Integer.MAX_VALUE);
        this.lirr = lirr;
        this.metrocardTransferSource = metrocardTransferSource;
        this.metrocardTransferExpiry = metrocardTransferExpiry;
        this.leftSubwayPaidArea = leftSubwayPaidArea;
    }

    @Override
    public boolean atLeastAsGoodForAllFutureRedemptions(TransferAllowance other) {
        if (other instanceof NYCTransferAllowance) {
            NYCTransferAllowance o = (NYCTransferAllowance) other;
            if (lirr != null && !lirr.atLeastAsGoodForAllFutureRedemptions(o.lirr)) return false;
            if (lirr == null && o.lirr != null) return false; // can't be as good

            if (metrocardTransferSource != o.metrocardTransferSource) return false; // different services
            if (metrocardTransferExpiry < o.metrocardTransferExpiry) return false; // expires sooner
            if (!leftSubwayPaidArea && o.leftSubwayPaidArea) return false; // free transfer with this and not with other

            // if we got here, we're good
            return true;
        } else {
            throw new IllegalArgumentException("Mixing of transfer allowance types!");
        }
    }
}
