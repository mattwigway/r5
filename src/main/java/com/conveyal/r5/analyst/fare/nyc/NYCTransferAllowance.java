package com.conveyal.r5.analyst.fare.nyc;

import com.conveyal.r5.analyst.fare.TransferAllowance;

/**
 * A transfer allowance for NYC. This has a bunch of sub-transfer
 */

public class NYCTransferAllowance extends TransferAllowance {
    // all these are public so they can be JSON-serialized for the debug interface

    public final LIRRTransferAllowance lirr;
    public final NYCInRoutingFareCalculator.NYCPatternType metrocardTransferSource;
    public final int metrocardTransferExpiry;

    public boolean leftSubwayPaidArea;

    /** Where the Metro-North was boarded, -1 if not on Metro-North */
    public final int metroNorthBoardStop;

    /** Direction of the current Metro-North trip, -1 if not on Metro-North */
    public final int metroNorthDirection;

    /** Whether the current Metro-North trip is peak or not */
    public final boolean metroNorthPeak;

    /** Since metro-north doesn't allow inter-line transfers, record which line we are on */
    public final NYCInRoutingFareCalculator.MetroNorthLine metroNorthLine;

    /**
     * Whether the current Metro-North trip is on the New Haven line
     * This is important because there are no free transfers between the New Haven and Harlem/Hudson
     * lines, see http://www.iridetheharlemline.com/2010/09/22/question-of-the-day-can-i-use-my-ticket-on-other-lines/
     */

    public NYCTransferAllowance(LIRRTransferAllowance lirr, NYCInRoutingFareCalculator.NYCPatternType metrocardTransferSource,
                                int metrocardTransferExpiry, boolean leftSubwayPaidArea,
                                int metroNorthBoardStop, int metroNorthDirection, boolean metroNorthPeak,
                                NYCInRoutingFareCalculator.MetroNorthLine metroNorthLine) {
        // only the value needs to be set correctly. The expiration time and number of transfers left are only used in
        // the second domination rule, which we override in atLeastAsGoodForAllFutureRedemptions
        super(computeMaxTransferAllowance(lirr, metrocardTransferSource, metroNorthBoardStop), Integer.MAX_VALUE, Integer.MAX_VALUE);
        this.lirr = lirr;
        this.metrocardTransferSource = metrocardTransferSource;
        this.metrocardTransferExpiry = metrocardTransferExpiry;
        this.leftSubwayPaidArea = leftSubwayPaidArea;
        this.metroNorthBoardStop = metroNorthBoardStop;
        this.metroNorthDirection = metroNorthDirection;
        this.metroNorthPeak = metroNorthPeak;
        this.metroNorthLine = metroNorthLine;
    }

    /**
     * Compute max transfer allowance. Static single function to trick JVM into letting us run it in the constructor
     * before the super() call.
     *
     * Note that we are adding the max transfer allowance from each different type of ticket, because max transfer
     * allowance is for the entire suffix, and you could conceivably hold multiple transfer slips simultaneously.
     */
    private static int computeMaxTransferAllowance (LIRRTransferAllowance lirr, NYCInRoutingFareCalculator.NYCPatternType metrocardTransferSource, int metroNorthBoardStop) {
        int maxTransferAllowance = 0;

        // adding these is correct because you could conceivably get multiple independent transfers from one prefix, e.g.
        // another LIRR transfer and then a metrocard transfer held over from before. Remember in that paper that max
        // transfer allowance is max for any suffix, not for any single transfer.
        // This is true even though we don't allow transfers between LIRR or MNR vehicles with another
        // vehicle in between, b/c you could do bus -> grand central -> Harlem-125 -> bus
        // For LIRR and MNR, we punt on actually computing the max transfer allowance for a particular ticket,
        // and just return the maximum full fare trip - which is a loose upper bound (they can't discount
        // the next trip more than it would cost). This is fine for algorithm correctness, although it means retaining
        // more trips than necessary. You can think of it as a ghost transfer to a train that will never be optimal.
        if (lirr != null) maxTransferAllowance += lirr.getMaxTransferAllowance();
        if (metroNorthBoardStop > -1) maxTransferAllowance += NYCStaticFareData.METRO_NORTH_MAX_FARE;
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

            // if other does not have a Metro-North allowance, this is better.
            // otherwise, this is only the same or better if board stops, peak/offpeak,
            // directions, and line are all the same. This will overretain trips, but Metro-North
            // is small enough this should be fine.
            if (o.metroNorthBoardStop != -1 && (
                    metroNorthBoardStop != o.metroNorthBoardStop ||
                            metroNorthPeak != o.metroNorthPeak ||
                            metroNorthDirection != o.metroNorthDirection ||
                            metroNorthLine != o.metroNorthLine
                    )) return false;

            // if we got here, we're good
            return true;
        } else {
            throw new IllegalArgumentException("Mixing of transfer allowance types!");
        }
    }

}
