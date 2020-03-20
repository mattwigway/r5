package com.conveyal.r5.analyst.fare.nyc;

import com.conveyal.r5.analyst.fare.TransferAllowance;
import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;
import com.csvreader.CsvReader;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import gnu.trove.map.TObjectDoubleMap;
import gnu.trove.map.hash.TObjectDoubleHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.*;

public class LIRRTransferAllowance extends TransferAllowance {
    private static final Logger LOG = LoggerFactory.getLogger(LIRRTransferAllowance.class);
    private static final Map<LIRRStop, TObjectDoubleMap<LIRRStop>> peakDirectFares = new HashMap<>();
    private static final Map<LIRRStop, TObjectDoubleMap<LIRRStop>> offpeakDirectFares = new HashMap<>();
    private static final Map<LIRRStop, Map<LIRRStop, TObjectDoubleMap<LIRRStop>>> peakViaFares = new HashMap<>();
    private static final Map<LIRRStop, Map<LIRRStop, TObjectDoubleMap<LIRRStop>>> offpeakViaFares = new HashMap<>();
    private static final Set<LIRRStop> viaStops = new HashSet<>();
    /** if a stop pair is present in this set, the second stop can be reached by only inbound trains from the first stop */
    private static final Multimap<LIRRStop, LIRRStop> inboundDownstreamStops = HashMultimap.create();
    /** if a stop pair is present in this set, the second stop can be reached by only outbound trains from the first stop */
    private static final Multimap<LIRRStop, LIRRStop> outboundDownstreamStops = HashMultimap.create();

    static {
        loadDirectFares();
        loadViaFares();
        loadDownstream();
    }

    /** Fare for the LIRR so far on this journey */
    public final double cumulativeFare;

    /**
     * Transfer allowances to all other LIRR stations, via peak trains
     *
     * It is important to have separate transfer allowances via peak and off-peak trains, since people may transfer between
     * them, and it is important to have separate transfer allowances for peak and off-peak trains.
     *
     * I think this is okay even in the case of multiple transfers and direction changing.
     */
    public final double[] transferAllowancesPeak = new double[LIRRStop.values().length];
    public final double[] transferAllowancesOffpeak = new double[LIRRStop.values().length];

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
            this.cumulativeFare = 0;
            // leave all allowances at zero
            return;
        }
        // main fare calculation loop
        double fareFromPreviousTickets = 0; // some complex LIRR journeys, with more than one direction change, require multiple tickets
        double cumulativeFareThisTicket;
        LIRRStop initialStop = boardStops.get(0); // source stop of current LIRR *ticket*
        LIRRDirection initialDirection = directions.get(0); // source direction of current LIRR *ticket*
        boolean thisTicketPeak = false;
        int nDirectionChanges = 0;
        LIRRStop viaStop; // via stop of current LIRR *ticket*

        for (int i = 0; i < boardStops.size(); i++) {
            LIRRStop boardStop = boardStops.get(i);
            LIRRStop alightStop = alightStops.get(i);
            LIRRDirection direction = directions.get(i);
            thisTicketPeak |= peak.get(i);

            if (direction.equals(initialDirection) && nDirectionChanges == 0) {
                // assuming you can change to another train in the same direction as if you never got off
                cumulativeFareThisTicket = (thisTicketPeak ? peakDirectFares : offpeakDirectFares).get(initialStop).get(alightStop);

                // create allowances. first, find all stops that can be reached without changing directions
                Collection<LIRRStop> downstreamStops =
                        (direction.equals(LIRRDirection.INBOUND) ? inboundDownstreamStops : outboundDownstreamStops).get(alightStop);
                for (LIRRStop downstreamStop : downstreamStops) {
                    // if you were to lose your ticket before boarding the next train
                    // (f_p + f_s) in paper
                    // if you were buying separate tickets, you could buy an offpeak ticket even if you already rode a peak train
                    double fullFareOffpeak = cumulativeFareThisTicket + offpeakDirectFares.get(alightStop).get(downstreamStop);
                    // f_PS in paper
                    // if you are already on a peak train, you have to buy a peak ticket regardless
                    double discountedFareOffpeak = (thisTicketPeak ? peakDirectFares : offpeakDirectFares).get(initialStop).get(downstreamStop);
                    double offpeakTransferAllowance = fullFareOffpeak - discountedFareOffpeak;

                    if (offpeakTransferAllowance < 0) {
                        throw new ArithmeticException("Found negative offpeak transfer allowance for LIRR!");
                    }

                    transferAllowancesOffpeak[downstreamStop.ordinal()] = offpeakTransferAllowance;

                    double fullFarePeak = cumulativeFareThisTicket + peakDirectFares.get(alightStop).get(downstreamStop);
                    double discountedFarePeak = peakDirectFares.get(initialStop).get(downstreamStop);
                    double peakTransferAllowance = fullFarePeak - discountedFarePeak;

                    if (peakTransferAllowance < 0) {
                        throw new ArithmeticException("Found negative peak transfer allowance for LIRR!");
                    }

                    transferAllowancesPeak[downstreamStop.ordinal()] = peakTransferAllowance;
                }

                // now, find all stops that can be reached via a transfer
                for (LIRRStop transferViaStop : viaStops) {
                    if ()
                }

            } else {
                // this can only happen on the second or more ride of a ticket
                if (!directions.get(i).equals(directions.get(i - 1))) {
                    // we have changed direction
                    nDirectionChanges++;

                    if (nDirectionChanges == 1) {
                        // we are on the second direction. continue with current ticket if possible.
                    }
                }
            }
        }
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
                double fare = Double.parseDouble(rdr.get("amount"));
                if (rdr.get("peak").equals("True")) {
                    peakDirectFares.computeIfAbsent(fromStop, k -> new TObjectDoubleHashMap<>()).put(toStop, fare);
                } else {
                    offpeakDirectFares.computeIfAbsent(fromStop, k -> new TObjectDoubleHashMap<>()).put(toStop, fare);
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

                double fare = Double.parseDouble(rdr.get("amount"));
                if (rdr.get("peak").equals("True")) {
                    peakViaFares.computeIfAbsent(fromStop, k -> new HashMap<>()).computeIfAbsent(viaStop, k -> new TObjectDoubleHashMap<>()).put(toStop, fare);
                } else {
                    offpeakViaFares.computeIfAbsent(fromStop, k -> new HashMap<>()).computeIfAbsent(viaStop, k -> new TObjectDoubleHashMap<>()).put(toStop, fare);
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
        INBOUND, OUTBOUND
    }

    /** Represents a single ride on an LIRR train (i.e. no transfers, even same-direction transfers */
    public static class LIRRRide {
        public final boolean inbound;
        public final LIRRStop fromStop;
        public final LIRRStop toStop;
        public final boolean peak;

        public LIRRRide(LIRRStop fromStop, LIRRStop toStop, boolean inbound,  boolean peak) {
            this.inbound = inbound;
            this.fromStop = fromStop;
            this.toStop = toStop;
            this.peak = peak;

            // sanity check
            if (inbound && !inboundDownstreamStops.containsEntry(fromStop, toStop) ||
                !inbound && !outboundDownstreamStops.containsEntry(fromStop, toStop)) {
                throw new IllegalArgumentException("Stops on ride are not downstream of one another!");
            }
        }
    }
}
