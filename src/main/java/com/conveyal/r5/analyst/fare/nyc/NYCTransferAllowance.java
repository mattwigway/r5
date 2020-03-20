package com.conveyal.r5.analyst.fare.nyc;

import com.conveyal.r5.analyst.fare.TransferAllowance;

/**
 * A transfer allowance for NYC. This has a bunch of sub-transfer
 */

public class NYCTransferAllowance extends TransferAllowance {
    public final LIRRTransferAllowance lirr;

    public NYCTransferAllowance (LIRRTransferAllowance lirr) {
        super();
        this.lirr = lirr;
    }

    @Override
    public boolean atLeastAsGoodForAllFutureRedemptions(TransferAllowance other) {
        if (other instanceof NYCTransferAllowance) {
            NYCTransferAllowance o = (NYCTransferAllowance) other;
            if (lirr != null) {
                return lirr.atLeastAsGoodForAllFutureRedemptions(o.lirr);
            } else {
                return false;
            }
        } else {
            throw new IllegalArgumentException("Mixing of transfer allowance types!");
        }
    }
}
