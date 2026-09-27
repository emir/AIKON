package io.github.emir.claudes40;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Calendar;
import java.util.Date;

import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;

/**
 * Mobile data used by the app (RMS record store "cs40data", one record):
 * requests and bytes sent / received today (phone-local day) and since the
 * last reset. Net counts every request.
 *
 * The numbers are estimates: message bodies are counted exactly, HTTP
 * headers roughly, and the encrypted connection set-up (TLS handshake,
 * certificates) not at all, so the operator's count is higher.
 */
final class DataUsage {

    private static final String STORE = "cs40data";
    private static final int FORMAT = 1;

    private static boolean loaded;
    private static int day;
    private static int dayRequests;
    private static long daySent;
    private static long dayReceived;
    private static long since;
    private static int requests;
    private static long sent;
    private static long received;

    private DataUsage() {
    }

    /** One request: bytes sent and received (headers included, estimated). */
    static synchronized void add(int out, int in) {
        load();
        roll();
        dayRequests++;
        daySent += out;
        dayReceived += in;
        requests++;
        sent += out;
        received += in;
        save();
    }

    /** {requests, sent, received} today. */
    static synchronized long[] today() {
        load();
        roll();
        return new long[] { dayRequests, daySent, dayReceived };
    }

    /** {requests, sent, received, since (ms)} since the last reset. */
    static synchronized long[] total() {
        load();
        return new long[] { requests, sent, received, since };
    }

    static synchronized void reset() {
        load();
        dayRequests = 0;
        daySent = 0;
        dayReceived = 0;
        requests = 0;
        sent = 0;
        received = 0;
        since = System.currentTimeMillis();
        day = today(since);
        save();
    }

    /** "12 requests · 34,5 KB (sent 8,1 KB, received 26,4 KB)". */
    static String describe(long[] v) {
        return v[0] + L.s(" istek · ", " requests · ") + Text.bytes(v[1] + v[2]) + L.s(" (gönderilen ", " (sent ")
                + Text.bytes(v[1]) + L.s(", alınan ", ", received ") + Text.bytes(v[2]) + ")";
    }

    private static int today(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTime(new Date(ms));
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
    }

    /** A new day starts the day counters again. */
    private static void roll() {
        int d = today(System.currentTimeMillis());
        if (d != day) {
            day = d;
            dayRequests = 0;
            daySent = 0;
            dayReceived = 0;
        }
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        since = System.currentTimeMillis();
        day = today(since);
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, false);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(1)));
            if (in.readInt() == FORMAT) {
                day = in.readInt();
                dayRequests = in.readInt();
                daySent = in.readLong();
                dayReceived = in.readLong();
                since = in.readLong();
                requests = in.readInt();
                sent = in.readLong();
                received = in.readLong();
            }
        } catch (RecordStoreException e) {
            // first use
        } catch (IOException e) {
            // unreadable: start again
        } finally {
            close(rs);
        }
    }

    private static void save() {
        RecordStore rs = null;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bo);
            out.writeInt(FORMAT);
            out.writeInt(day);
            out.writeInt(dayRequests);
            out.writeLong(daySent);
            out.writeLong(dayReceived);
            out.writeLong(since);
            out.writeInt(requests);
            out.writeLong(sent);
            out.writeLong(received);
            out.close();
            byte[] b = bo.toByteArray();
            rs = RecordStore.openRecordStore(STORE, true);
            if (rs.getNumRecords() == 0) {
                rs.addRecord(b, 0, b.length);
            } else {
                rs.setRecord(1, b, 0, b.length);
            }
        } catch (RecordStoreException e) {
            // full or unavailable: the numbers are only kept in RAM
        } catch (IOException e) {
            // same
        } finally {
            close(rs);
        }
    }

    private static void close(RecordStore rs) {
        if (rs != null) {
            try {
                rs.closeRecordStore();
            } catch (RecordStoreException e) {
                // ignore
            }
        }
    }
}
