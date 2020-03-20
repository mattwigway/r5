package com.conveyal.r5.analyst.fare.nyc;

import com.conveyal.r5.analyst.fare.TransferAllowance;
import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;
import com.csvreader.CsvReader;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import gnu.trove.map.TObjectDoubleMap;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TObjectDoubleHashMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.*;

public class LIRRTransferAllowance extends TransferAllowance {
    private static final Logger LOG = LoggerFactory.getLogger(LIRRTransferAllowance.class);
    private static final Map<LIRRStop, TObjectIntMap<LIRRStop>> peakDirectFares = new HashMap<>();
    private static final Map<LIRRStop, TObjectIntMap<LIRRStop>> offpeakDirectFares = new HashMap<>();
    private static final Map<LIRRStop, Map<LIRRStop, TObjectIntMap<LIRRStop>>> peakViaFares = new HashMap<>();
    private static final Map<LIRRStop, Map<LIRRStop, TObjectIntMap<LIRRStop>>> offpeakViaFares = new HashMap<>();
    private static final Set<LIRRStop> viaStops = new HashSet<>();
    /** if a stop pair is present in this set, the second stop can be reached by only inbound trains from the first stop */
    private static final Multimap<LIRRStop, LIRRStop> inboundDownstreamStops = HashMultimap.create();
    /** if a stop pair is present in this set, the second stop can be reached by only outbound trains from the first stop */
    private static final Multimap<LIRRStop, LIRRStop> outboundDownstreamStops = HashMultimap.create();

    private static int maxLirrFareTemp = 0;

    /** the maximum fare to travel anywhere in the LIRR system */
    public static final int MAX_LIRR_FARE;

    static {
        loadDirectFares();
        loadViaFares();
        loadDownstream();
        MAX_LIRR_FARE = maxLirrFareTemp;
    }

    /** Fare for the LIRR so far on this journey */
    public final int cumulativeFare;

    /** The boarding stop of the most recently purchased LIRR ticket */
    public final LIRRStop boardStop;

    /** The transfer stop where the user changed direction */
    public final LIRRStop viaStop;

    /** Where the user alighted */
    public final LIRRStop alightStop;

    /** What direction the user started out traveling in */
    public final LIRRDirection initialDirection;

    /** Was a peak train ridden before any opposite-direction transfer */
    public final boolean peakBeforeDirectionChange;

    /** Was a peak train ridden after any opposite-direction transfer, always false if there was no direction change */
    public final boolean peakAfterDirectionChange;


    /**
     * Compute an LIRR fare. Currently assumes that if any journey in the sequence is a peak journey, the peak fare will
     * be charged for the entire journey. It might be possible to get a cheaper fare by combining an off-peak and peak fare
     * purchased separately, but I am assuming that people don't do this. Due to the separate consideration of peak and off-peak
     * transfer allowances described above, this should not cause algorithmic difficulties.
     *
     * @param boardStops
     * @param alightStops
     * @param directions
     * @param peak
     */
    public LIRRTransferAllowance (List<LIRRStop> boardStops, List<LIRRStop> alightStops, List<LIRRDirection> directions, BitSet peak) {
        if (boardStops.size() == 0) {
            throw new IllegalArgumentException("Empty LIRR stop list!");
        }
        // main fare calculation loop
        int fareFromPreviousTickets = 0; // some complex LIRR journeys, with more than one direction change, require multiple tickets
        int cumulativeFareThisTicket = 0;
        LIRRStop initialStop = boardStops.get(0); // source stop of current LIRR *ticket*
        LIRRDirection initialDirection = directions.get(0); // source direction of current LIRR *ticket*
        boolean thisTicketPeak = false; // has a peak train been used on this LIRR *ticket*
        boolean thisDirectionPeak = false; // has a peak train been used in this direction on this ticket (used when there is no via fare, to get cumulative fares)
        boolean lastDirectionPeak = false;
        int nDirectionChanges = 0;
        LIRRStop viaStop = null; // via stop of current LIRR *ticket*

        for (int i = 0; i < boardStops.size(); i++) {
            LIRRStop boardStop = boardStops.get(i);
            LIRRStop alightStop = alightStops.get(i);
            LIRRDirection direction = directions.get(i);

            if (direction.equals(initialDirection) && nDirectionChanges == 0) {
                // assuming you can change to another train in the same direction as if you never got off
                thisDirectionPeak |= peak.get(i);
                thisTicketPeak |= peak.get(i);
                cumulativeFareThisTicket = (thisTicketPeak ? peakDirectFares : offpeakDirectFares).get(initialStop).get(alightStop);
            } else {
                // this can only happen on the second or more ride of a ticket
                if (!directions.get(i).equals(directions.get(i - 1))) {
                    // we have changed direction
                    nDirectionChanges++;

                    if ((nDirectionChanges == 1) && (boardStop.equals(alightStops.get(i - 1)))) {
                        // we are on the second direction. continue with current ticket, unless we have left the system.
                        viaStop = boardStop;
                        lastDirectionPeak = thisDirectionPeak;
                        thisDirectionPeak = peak.get(i);
                    } else {
                        // time to buy a new ticket
                        fareFromPreviousTickets += cumulativeFareThisTicket; // left over from last iteration
                        thisTicketPeak = false;
                        thisDirectionPeak = false;
                        lastDirectionPeak = false;
                        initialStop = boardStop;
                        viaStop = null;
                        nDirectionChanges = 0;
                        initialDirection = direction;

                        cumulativeFareThisTicket = (thisTicketPeak ? peakDirectFares : offpeakDirectFares).get(initialStop).get(alightStop);
                        continue; // move to next ride
                    }
                }

                // couldn't set these until all changing direction was done
                thisDirectionPeak |= peak.get(i);
                thisTicketPeak |= peak.get(i);

                // continue ride in this direction
                // try getting the via fare
                if (viaStop == null) {
                    throw new NullPointerException("Via stop is null");
                }

                try {
                    cumulativeFareThisTicket = (thisTicketPeak ? peakViaFares : offpeakViaFares).get(initialStop).get(viaStop).get(alightStop);
                } catch (Exception e) {
                    // TODO make this catch clause more specific
                    // no via fare for this journey
                    cumulativeFareThisTicket = (lastDirectionPeak ? peakDirectFares : offpeakDirectFares).get(initialStop).get(viaStop) +
                            (thisDirectionPeak ? peakDirectFares : offpeakDirectFares).get(viaStop).get(alightStop);
                }
            }
        }
        this.boardStop = initialStop; // for this ticket
        this.viaStop = viaStop;
        this.initialDirection = initialDirection;
        this.alightStop = alightStops.get(alightStops.size() - 1);
        this.peakBeforeDirectionChange = (viaStop == null ? thisDirectionPeak : lastDirectionPeak);
        this.peakAfterDirectionChange = (viaStop == null ? false : thisDirectionPeak); // always set to false when there has been no direction change
        this.cumulativeFare = fareFromPreviousTickets + cumulativeFareThisTicket;
    }

    /**
     * Does this provide as good as or better than transfers to all future services?
     * Rather than actually figure this out, just treat only LIRR tickets that boarded at the same place, transferred at the same place,
     * alighted at the same place started in the same direction, used the same combo of peak and off-peak services as comparable.
     * Since the LIRR is a small network, and we clear LIRR transfers as soon as you leave the LIRR system, this should be tractable.
     * @param other
     * @return
     */
    public boolean atLeastAsGoodForAllFutureRedemptions (LIRRTransferAllowance other) {
        if (other == null) return true;
        else return (boardStop.equals(other.boardStop)) &&
                (viaStop == other.viaStop) && // okay to use == on enum constants, and neatly handles nulls
                (initialDirection.equals(other.initialDirection)) &&
                (peakBeforeDirectionChange == other.peakBeforeDirectionChange) &&
                (peakAfterDirectionChange == other.peakAfterDirectionChange);
                // no need to compare cumulative fare here, that's done separately -- and in fact we wouldn't want to
                // b/c it might throw out an LIRR journey in favor of a more expensive overall journey that doesn't use the LIRR as much.
    }

    /**
     * Again, producing a weak upper bound for simplicity, and given the small size of the LIRR network it should be
     * tractable. We know the max transfer allowance can't be any more than if you were to just buy the most expensive new ticket.
     * Since we clear LIRR transfer allowances as soon as you egress from an LIRR station, this should not cause tractability issues.
     */

    public int getMaxTransferAllowance () {
        return MAX_LIRR_FARE;
    }


    /**
     * Load LIRR fare information from classpath.
     */
    private static void loadDirectFares () {
        InputStream is = null;
        try {
            is = LIRRTransferAllowance.class.getClassLoader().getResourceAsStream("fares/nyc/lirr/direct_fares.csv");
            CsvReader rdr = new CsvReader(is, ',', Charset.forName("UTF-8"));
            rdr.readHeaders();
            while (rdr.readRecord()) {
                LIRRStop fromStop = LIRRStop.valueOf(rdr.get("from_stop_id").toUpperCase(Locale.US));
                LIRRStop toStop = LIRRStop.valueOf(rdr.get("to_stop_id").toUpperCase(Locale.US));
                int fare = Integer.parseInt(rdr.get("amount"));
                maxLirrFareTemp = Math.max(maxLirrFareTemp, fare);
                if (rdr.get("peak").equals("True")) {
                    peakDirectFares.computeIfAbsent(fromStop, k -> new TObjectIntHashMap<>()).put(toStop, fare);
                } else {
                    offpeakDirectFares.computeIfAbsent(fromStop, k -> new TObjectIntHashMap<>()).put(toStop, fare);
                }
            }
        } catch (IOException e) {
            LOG.error("IO Exception reading LIRR Direct Fares CSV", e);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                LOG.error("IO Exception closing LIRR Direct Fares CSV", e);
            }
        }
    }

    private static void loadViaFares () {
        InputStream is = null;
        try {
            is = LIRRTransferAllowance.class.getClassLoader().getResourceAsStream("fares/nyc/lirr/via_fares.csv");
            CsvReader rdr = new CsvReader(is, ',', Charset.forName("UTF-8"));
            rdr.readHeaders();
            while (rdr.readRecord()) {
                LIRRStop fromStop = LIRRStop.valueOf(rdr.get("from_stop_id").toUpperCase(Locale.US));
                LIRRStop toStop = LIRRStop.valueOf(rdr.get("to_stop_id").toUpperCase(Locale.US));
                LIRRStop viaStop = LIRRStop.valueOf(rdr.get("via_stop_id").toUpperCase(Locale.US));

                viaStops.add(viaStop);

                int fare = Integer.parseInt(rdr.get("amount"));
                maxLirrFareTemp = Math.max(maxLirrFareTemp, fare);

                if (rdr.get("peak").equals("True")) {
                    peakViaFares.computeIfAbsent(fromStop, k -> new HashMap<>()).computeIfAbsent(viaStop, k -> new TObjectIntHashMap<>()).put(toStop, fare);
                } else {
                    offpeakViaFares.computeIfAbsent(fromStop, k -> new HashMap<>()).computeIfAbsent(viaStop, k -> new TObjectIntHashMap<>()).put(toStop, fare);
                }
            }
        } catch (IOException e) {
            LOG.error("IO Exception reading LIRR Direct Fares CSV", e);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                LOG.error("IO Exception closing LIRR Direct Fares CSV", e);
            }
        }
    }

    private static void loadDownstream () {
        InputStream is = null;
        try {
            is = LIRRTransferAllowance.class.getClassLoader().getResourceAsStream("fares/nyc/lirr/descendants.csv");
            CsvReader rdr = new CsvReader(is, ',', Charset.forName("UTF-8"));
            rdr.readHeaders();
            while (rdr.readRecord()) {
                LIRRStop fromStop = LIRRStop.valueOf(rdr.get("stop_id").toUpperCase(Locale.US));
                for (int i = 1; i < rdr.getHeaderCount(); i++) {
                    LIRRStop toStop = LIRRStop.valueOf(rdr.getHeader(i).toUpperCase(Locale.US));
                    String val = rdr.get(i);
                    if (val.equals("I")) {
                        inboundDownstreamStops.put(fromStop, toStop);
                    } else if (val.equals("O")) {
                        outboundDownstreamStops.put(fromStop, toStop);
                    }
                }
            }
        } catch (IOException e) {
            LOG.error("IO Exception reading LIRR Direct Fares CSV", e);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                LOG.error("IO Exception closing LIRR Direct Fares CSV", e);
            }
        }
    }

    public static enum LIRRDirection {
        OUTBOUND, INBOUND;

        public static LIRRDirection forGtfsDirection (int dir) {
            switch (dir) {
                case 0:
                    return OUTBOUND;
                case 1:
                    return INBOUND;
                default:
                    throw new IllegalArgumentException("Direction must be 0/1");
            }
        }
    }
}
