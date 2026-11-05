package org.osmium.forensics;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Embedded SQLite evidence database for packet forensics.
 *
 * sqlite-jdbc is downloaded from Maven Central (pinned version, sha1-verified)
 * into osmium-libs/ on first start and loaded reflectively — the server
 * classloader can't see plugin-style dependency jars.
 *
 * All inserts are enqueued and flushed by ONE background writer thread in
 * batches; nothing here ever runs on the tick thread. A second read-only
 * connection serves the /evidence compiler (WAL allows concurrent reads).
 *
 * Retention: rows older than retention-days are pruned hourly; if the db file
 * grows past db-cap-mb the oldest rows are FIFO-trimmed.
 */
public final class OsmiumEvidenceDb {

    private static final Logger LOGGER = Logger.getLogger("Osmium-EvidenceDb");

    private static final String SQLITE_VERSION = "3.47.1.0";
    private static final String DB_NAME = "osmium-evidence.db";
    private static final int QUEUE_CAP = 200_000;
    private static final int FLUSH_BATCH = 4096;
    private static final long PRUNE_INTERVAL_MS = TimeUnit.HOURS.toMillis(1);

    // ---- row types (also the compiler's query results) ----

    /** Movement window: aggregated over move-window-ms. x0/y0/z0/yaw0/pitch0 = window start, x1.. = end. */
    public record MoveRow(String uuid, String name, long ts, String world,
                          double x0, double y0, double z0, float yaw0, float pitch0,
                          double x1, double y1, double z1, float yaw1, float pitch1,
                          int packets, double dist, int groundChanges, int durMs) {}
    /** Exact attack event. */
    public record AttackRow(String uuid, String name, long ts, String world,
                            double x, double y, double z,
                            String targetType, String targetUuid,
                            double tx, double ty, double tz) {}
    /** Block dig/start/finish/attack-block action. */
    public record DigRow(String uuid, String name, long ts, String action, int x, int y, int z, String block) {}
    /** RAW chat (pre-filter). filtered = the chat filter would have acted. */
    public record ChatRow(String uuid, String name, long ts, String world, String message, boolean filtered, String filterMatch) {}
    /** GrimAC violation. */
    public record GrimRow(String uuid, String name, long ts, String check, double vl, boolean setback) {}
    /** Session upsert (touch=true updates last_seen only). */
    public record SessionRow(String uuid, String name, long ts, boolean touch) {}

    // ---- state ----

    private static volatile boolean running = false;
    private static File dbFile;
    private static LinkedBlockingQueue<Object> queue;
    private static Thread writer;
    private static final AtomicLong DROPPED = new AtomicLong();
    private static volatile String lastDropLog = "";

    // reflective JDBC handles
    private static Object writeConn;
    private static Object readConn;
    private static Object psMove, psAttack, psDig, psChat, psGrim, psSession;
    private static Method mPrepare, mSetLong, mSetInt, mSetDouble, mSetString, mSetBoolean,
            mAddBatch, mClearBatch, mExecuteBatch, mClose, mSetQueryTimeout,
            mExecUpdate, mRsNext, mRsObject;

    private OsmiumEvidenceDb() {}

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    public static boolean isRunning() { return running; }

    public static void start(File serverDir) {
        if (running) return;
        try {
            File jar = ensureDriverJar(new File(serverDir, "osmium-libs"));
            dbFile = new File(serverDir, DB_NAME);
            URLClassLoader loader = new URLClassLoader(new URL[]{ jar.toURI().toURL() },
                    OsmiumEvidenceDb.class.getClassLoader());
            Class<?> driverClass = Class.forName("org.sqlite.JDBC", true, loader);
            Object driver = driverClass.getDeclaredConstructor().newInstance();
            Method connect = driverClass.getMethod("connect", String.class, java.util.Properties.class);
            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
            java.util.Properties props = new java.util.Properties();
            props.setProperty("journal_mode", "WAL");
            props.setProperty("busy_timeout", "5000");
            props.setProperty("synchronous", "NORMAL");
            writeConn = connect.invoke(driver, url, props);
            readConn = connect.invoke(driver, url, props);
            if (writeConn == null || readConn == null) throw new RuntimeException("SQLite driver refused the connection");

            Class<?> connIface = Class.forName("java.sql.Connection");
            Class<?> stmtIface = Class.forName("java.sql.Statement");
            Class<?> psIface = Class.forName("java.sql.PreparedStatement");
            Class<?> rsIface = Class.forName("java.sql.ResultSet");
            mPrepare = connIface.getMethod("prepareStatement", String.class);
            mExecUpdate = stmtIface.getMethod("executeUpdate", String.class);
            Class<?> psParams[] = { int.class };
            mSetLong = psIface.getMethod("setLong", psParams);
            mSetInt = psIface.getMethod("setInt", psParams);
            mSetDouble = psIface.getMethod("setDouble", psParams);
            mSetString = psIface.getMethod("setString", psParams);
            mSetBoolean = psIface.getMethod("setBoolean", psParams);
            mAddBatch = psIface.getMethod("addBatch");
            mClearBatch = psIface.getMethod("clearBatch");
            mExecuteBatch = psIface.getMethod("executeBatch");
            mClose = stmtIface.getMethod("close");
            mSetQueryTimeout = stmtIface.getMethod("setQueryTimeout", int.class);
            mRsNext = rsIface.getMethod("next");
            mRsObject = rsIface.getMethod("getObject", int.class);

            createSchema();
            prepareStatements();

            queue = new LinkedBlockingQueue<>(QUEUE_CAP);
            writer = new Thread(OsmiumEvidenceDb::writeLoop, "Osmium-EvidenceDb");
            writer.setDaemon(true);
            writer.start();
            running = true;
            LOGGER.info("Evidence DB open (" + DB_NAME + ", retention " + org.osmium.OsmiumConfig.forensicsRetentionDays
                    + "d, cap " + org.osmium.OsmiumConfig.forensicsDbCapMb + "MB)");
        } catch (Throwable t) {
            LOGGER.severe("Evidence DB failed to start: " + t);
            running = false;
        }
    }

    public static void stop() {
        if (!running && writer == null) return;
        running = false;
        Thread w = writer;
        if (w != null) {
            w.interrupt();
            try { w.join(3000); } catch (InterruptedException ignored) {}
            writer = null;
        }
        finalFlush();
        closeQuietly(psMove); closeQuietly(psAttack); closeQuietly(psDig);
        closeQuietly(psChat); closeQuietly(psGrim); closeQuietly(psSession);
        closeQuietly(writeConn); closeQuietly(readConn);
        psMove = psAttack = psDig = psChat = psGrim = psSession = null;
        writeConn = readConn = null;
    }

    // ------------------------------------------------------------------
    // enqueue (any thread; drops instead of blocking the tick thread)
    // ------------------------------------------------------------------

    static void enqueue(Object row) {
        LinkedBlockingQueue<Object> q = queue;
        if (q == null || !running) return;
        if (!q.offer(row)) {
            long d = DROPPED.incrementAndGet();
            String now = String.valueOf(System.currentTimeMillis() / 60000);
            if (!now.equals(lastDropLog)) {
                lastDropLog = now;
                LOGGER.warning("Evidence queue full — dropped " + d + " row(s) total");
            }
        }
    }

    // ------------------------------------------------------------------
    // writer loop
    // ------------------------------------------------------------------

    private static void writeLoop() {
        long lastPrune = System.currentTimeMillis();
        long lastFlush = System.currentTimeMillis();
        ArrayList<Object> buf = new ArrayList<>(FLUSH_BATCH);
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                try {
                    Object first = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (first != null) {
                        buf.add(first);
                        queue.drainTo(buf, FLUSH_BATCH - buf.size());
                    }
                } catch (InterruptedException e) {
                    break;
                }
                if (!buf.isEmpty()) {
                    writeBatch(buf);
                    buf.clear();
                }
                long now = System.currentTimeMillis();
                if (now - lastFlush >= TimeUnit.SECONDS.toMillis(Math.max(1, org.osmium.OsmiumConfig.forensicsFlushSeconds))) {
                    // commit point is implicit (autocommit per executeBatch); timestamp kept for future grouping
                    lastFlush = now;
                }
                if (now - lastPrune >= PRUNE_INTERVAL_MS) {
                    lastPrune = now;
                    prune(now);
                    enforceCap();
                }
            } catch (Throwable t) {
                LOGGER.warning("Evidence writer error: " + t);
                buf.clear();
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            }
        }
    }

    private static void finalFlush() {
        LinkedBlockingQueue<Object> q = queue;
        if (q == null || writeConn == null) return;
        ArrayList<Object> buf = new ArrayList<>();
        q.drainTo(buf);
        if (!buf.isEmpty()) {
            try { writeBatch(buf); } catch (Throwable t) { LOGGER.warning("final flush failed: " + t); }
        }
    }

    private static void writeBatch(List<Object> rows) throws Exception {
        Object curPs = null;
        for (Object row : rows) {
            Object ps = statementFor(row);
            if (ps != curPs && curPs != null) { mExecuteBatch.invoke(curPs); mClearBatch.invoke(curPs); }
            curPs = ps;
            bind(ps, row);
            mAddBatch.invoke(ps);
        }
        if (curPs != null) { mExecuteBatch.invoke(curPs); mClearBatch.invoke(curPs); }
    }

    private static Object statementFor(Object row) {
        if (row instanceof MoveRow) return psMove;
        if (row instanceof AttackRow) return psAttack;
        if (row instanceof DigRow) return psDig;
        if (row instanceof ChatRow) return psChat;
        if (row instanceof GrimRow) return psGrim;
        if (row instanceof SessionRow r) return r.touch() ? psSession : psSessionNew;
        return null;
    }

    private static void bind(Object ps, Object row) throws Exception {
        if (row instanceof MoveRow r) {
            set(ps, 1, r.uuid()); set(ps, 2, r.name()); set(ps, 3, r.ts()); set(ps, 4, r.world());
            set(ps, 5, r.x0()); set(ps, 6, r.y0()); set(ps, 7, r.z0()); set(ps, 8, r.yaw0()); set(ps, 9, r.pitch0());
            set(ps, 10, r.x1()); set(ps, 11, r.y1()); set(ps, 12, r.z1()); set(ps, 13, r.yaw1()); set(ps, 14, r.pitch1());
            set(ps, 15, r.packets()); set(ps, 16, r.dist()); set(ps, 17, r.groundChanges()); set(ps, 18, r.durMs());
        } else if (row instanceof AttackRow r) {
            set(ps, 1, r.uuid()); set(ps, 2, r.name()); set(ps, 3, r.ts()); set(ps, 4, r.world());
            set(ps, 5, r.x()); set(ps, 6, r.y()); set(ps, 7, r.z());
            set(ps, 8, r.targetType()); set(ps, 9, r.targetUuid());
            set(ps, 10, r.tx()); set(ps, 11, r.ty()); set(ps, 12, r.tz());
        } else if (row instanceof DigRow r) {
            set(ps, 1, r.uuid()); set(ps, 2, r.name()); set(ps, 3, r.ts());
            set(ps, 4, r.action()); set(ps, 5, r.x()); set(ps, 6, r.y()); set(ps, 7, r.z()); set(ps, 8, r.block());
        } else if (row instanceof ChatRow r) {
            set(ps, 1, r.uuid()); set(ps, 2, r.name()); set(ps, 3, r.ts()); set(ps, 4, r.world());
            set(ps, 5, r.message()); set(ps, 6, r.filtered()); set(ps, 7, r.filterMatch());
        } else if (row instanceof GrimRow r) {
            set(ps, 1, r.uuid()); set(ps, 2, r.name()); set(ps, 3, r.ts());
            set(ps, 4, r.check()); set(ps, 5, r.vl()); set(ps, 6, r.setback());
        } else if (row instanceof SessionRow r) {
            if (r.touch()) {
                set(ps, 1, r.ts()); set(ps, 2, r.name()); set(ps, 3, r.uuid());
            } else {
                set(ps, 1, r.uuid()); set(ps, 2, r.name()); set(ps, 3, r.ts()); set(ps, 4, r.ts());
            }
        }
    }

    private static void set(Object ps, int idx, Object v) throws Exception {
        if (v instanceof Long l) mSetLong.invoke(ps, idx, l);
        else if (v instanceof Integer i) mSetInt.invoke(ps, idx, i);
        else if (v instanceof Double d) mSetDouble.invoke(ps, idx, d);
        else if (v instanceof Float f) mSetDouble.invoke(ps, idx, (double) f);
        else if (v instanceof Boolean b) mSetBoolean.invoke(ps, idx, b);
        else mSetString.invoke(ps, idx, v == null ? "" : v.toString());
    }

    private static final String SQL_MOVE = "INSERT INTO moves(uuid,name,ts,world,x0,y0,z0,yaw0,pitch0,x1,y1,z1,yaw1,pitch1,packets,dist,ground_changes,dur_ms)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String SQL_ATTACK = "INSERT INTO attacks(uuid,name,ts,world,x,y,z,target_type,target_uuid,tx,ty,tz)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String SQL_DIG = "INSERT INTO digs(uuid,name,ts,action,x,y,z,block) VALUES(?,?,?,?,?,?,?,?)";
    private static final String SQL_CHAT = "INSERT INTO chat(uuid,name,ts,world,message,filtered,filter_match) VALUES(?,?,?,?,?,?,?)";
    private static final String SQL_GRIM = "INSERT INTO grim(uuid,name,ts,check_name,vl,setback) VALUES(?,?,?,?,?,?)";
    private static final String SQL_SESSION_TOUCH = "UPDATE sessions SET last_seen=?, name=COALESCE(?, name) WHERE uuid=?";
    private static final String SQL_SESSION_NEW = "INSERT OR IGNORE INTO sessions(uuid,name,first_seen,last_seen) VALUES(?,?,?,?)";

    private static void prepareStatements() throws Exception {
        psMove = mPrepare.invoke(writeConn, SQL_MOVE);
        psAttack = mPrepare.invoke(writeConn, SQL_ATTACK);
        psDig = mPrepare.invoke(writeConn, SQL_DIG);
        psChat = mPrepare.invoke(writeConn, SQL_CHAT);
        psGrim = mPrepare.invoke(writeConn, SQL_GRIM);
        psSession = mPrepare.invoke(writeConn, SQL_SESSION_TOUCH);
        // a second statement for the insert-or-ignore path
        psSessionNew = mPrepare.invoke(writeConn, SQL_SESSION_NEW);
    }

    private static Object psSessionNew;

    private static void createSchema() throws Exception {
        exec("CREATE TABLE IF NOT EXISTS sessions(uuid TEXT PRIMARY KEY, name TEXT, first_seen INTEGER, last_seen INTEGER)");
        exec("CREATE TABLE IF NOT EXISTS moves(uuid TEXT, name TEXT, ts INTEGER, world TEXT,"
                + "x0 REAL, y0 REAL, z0 REAL, yaw0 REAL, pitch0 REAL,"
                + "x1 REAL, y1 REAL, z1 REAL, yaw1 REAL, pitch1 REAL,"
                + "packets INTEGER, dist REAL, ground_changes INTEGER, dur_ms INTEGER)");
        exec("CREATE TABLE IF NOT EXISTS attacks(uuid TEXT, name TEXT, ts INTEGER, world TEXT,"
                + "x REAL, y REAL, z REAL, target_type TEXT, target_uuid TEXT, tx REAL, ty REAL, tz REAL)");
        exec("CREATE TABLE IF NOT EXISTS digs(uuid TEXT, name TEXT, ts INTEGER, action TEXT, x INTEGER, y INTEGER, z INTEGER, block TEXT)");
        exec("CREATE TABLE IF NOT EXISTS chat(uuid TEXT, name TEXT, ts INTEGER, world TEXT, message TEXT, filtered INTEGER, filter_match TEXT)");
        exec("CREATE TABLE IF NOT EXISTS grim(uuid TEXT, name TEXT, ts INTEGER, check_name TEXT, vl REAL, setback INTEGER)");
        exec("CREATE INDEX IF NOT EXISTS idx_moves ON moves(uuid, ts)");
        exec("CREATE INDEX IF NOT EXISTS idx_attacks ON attacks(uuid, ts)");
        exec("CREATE INDEX IF NOT EXISTS idx_digs ON digs(uuid, ts)");
        exec("CREATE INDEX IF NOT EXISTS idx_chat ON chat(uuid, ts)");
        exec("CREATE INDEX IF NOT EXISTS idx_grim ON grim(uuid, ts)");
        exec("CREATE INDEX IF NOT EXISTS idx_chat_ts ON chat(ts)");
        exec("CREATE INDEX IF NOT EXISTS idx_moves_ts ON moves(ts)");
    }

    private static void exec(String sql) throws Exception {
        mExecUpdate.invoke(writeConn, sql);
    }

    // ------------------------------------------------------------------
    // prune + cap (writer thread only)
    // ------------------------------------------------------------------

    private static void prune(long now) {
        long cutoff = now - TimeUnit.DAYS.toMillis(Math.max(1, org.osmium.OsmiumConfig.forensicsRetentionDays));
        try {
            exec("DELETE FROM moves WHERE ts < " + cutoff);
            exec("DELETE FROM attacks WHERE ts < " + cutoff);
            exec("DELETE FROM digs WHERE ts < " + cutoff);
            exec("DELETE FROM chat WHERE ts < " + cutoff);
            exec("DELETE FROM grim WHERE ts < " + cutoff);
            exec("DELETE FROM sessions WHERE last_seen < " + cutoff);
            exec("PRAGMA wal_checkpoint(PASSIVE)");
        } catch (Exception e) {
            LOGGER.warning("Prune failed: " + e);
        }
    }

    private static void enforceCap() {
        long capBytes = (long) org.osmium.OsmiumConfig.forensicsDbCapMb * 1024L * 1024L;
        try {
            // include the WAL file: combined footprint is what fills the disk
            long used = dbFile.length();
            File wal = new File(dbFile.getParentFile(), DB_NAME + "-wal");
            if (wal.exists()) used += wal.length();
            if (used <= capBytes) return;
            long target = capBytes * 9 / 10;
            String[] tables = { "moves", "chat", "digs", "attacks", "grim" };
            while (used > target) {
                boolean progressed = false;
                for (String table : tables) {
                    Integer n = (Integer) mExecUpdate.invoke(writeConn,
                            "DELETE FROM " + table + " WHERE rowid IN (SELECT rowid FROM " + table
                                    + " ORDER BY ts ASC LIMIT 50000)");
                    if (n != null && n > 0) progressed = true;
                    used = dbFile.length() + (wal.exists() ? wal.length() : 0);
                    if (used <= target) break;
                }
                if (!progressed) break;
            }
            exec("PRAGMA wal_checkpoint(TRUNCATE)");
            LOGGER.info("Evidence DB trimmed to " + (dbFile.length() / 1024 / 1024) + "MB (cap "
                    + org.osmium.OsmiumConfig.forensicsDbCapMb + "MB)");
        } catch (Exception e) {
            LOGGER.warning("Cap enforcement failed: " + e);
        }
    }

    // ------------------------------------------------------------------
    // queries (compiler thread; uses the read connection)
    // ------------------------------------------------------------------

    public record SessionSpan(long firstSeen, long lastSeen) {}

    public static SessionSpan session(UUID uuid) {
        List<Object[]> rows = query(readConn,
                "SELECT first_seen, last_seen FROM sessions WHERE uuid='" + uuid + "' LIMIT 1", 2);
        if (rows.isEmpty()) return null;
        Object[] r = rows.get(0);
        return new SessionSpan(asLong(r[0]), asLong(r[1]));
    }

    public static List<MoveRow> moves(UUID uuid, long fromMs, long toMs, int max) {
        List<Object[]> rows = query(readConn, "SELECT ts,world,x0,y0,z0,yaw0,pitch0,x1,y1,z1,yaw1,pitch1,packets,dist,ground_changes,dur_ms"
                + " FROM moves WHERE uuid='" + uuid + "' AND ts>=" + fromMs + " AND ts<=" + toMs + " ORDER BY ts LIMIT " + max, 16);
        List<MoveRow> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new MoveRow(uuid.toString(), "", asLong(r[0]), asStr(r[1]),
                    asDouble(r[2]), asDouble(r[3]), asDouble(r[4]), (float) asDouble(r[5]), (float) asDouble(r[6]),
                    asDouble(r[7]), asDouble(r[8]), asDouble(r[9]), (float) asDouble(r[10]), (float) asDouble(r[11]),
                    asInt(r[12]), asDouble(r[13]), asInt(r[14]), asInt(r[15])));
        }
        return out;
    }

    public static List<AttackRow> attacks(UUID uuid, long fromMs, long toMs, int max) {
        List<Object[]> rows = query(readConn, "SELECT ts,world,x,y,z,target_type,target_uuid,tx,ty,tz,name"
                + " FROM attacks WHERE uuid='" + uuid + "' AND ts>=" + fromMs + " AND ts<=" + toMs + " ORDER BY ts LIMIT " + max, 11);
        List<AttackRow> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new AttackRow(uuid.toString(), asStr(r[10]), asLong(r[0]), asStr(r[1]),
                    asDouble(r[2]), asDouble(r[3]), asDouble(r[4]),
                    asStr(r[5]), asStr(r[6]), asDouble(r[7]), asDouble(r[8]), asDouble(r[9])));
        }
        return out;
    }

    public static List<ChatRow> chat(UUID uuid, long fromMs, long toMs, int max) {
        List<Object[]> rows = query(readConn, "SELECT ts,world,message,filtered,filter_match,name"
                + " FROM chat WHERE uuid='" + uuid + "' AND ts>=" + fromMs + " AND ts<=" + toMs + " ORDER BY ts LIMIT " + max, 6);
        List<ChatRow> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new ChatRow(uuid.toString(), asStr(r[5]), asLong(r[0]), asStr(r[1]),
                    asStr(r[2]), asInt(r[3]) != 0, asStr(r[4])));
        }
        return out;
    }

    public static List<GrimRow> grim(UUID uuid, long fromMs, long toMs, int max) {
        List<Object[]> rows = query(readConn, "SELECT ts,check_name,vl,setback,name"
                + " FROM grim WHERE uuid='" + uuid + "' AND ts>=" + fromMs + " AND ts<=" + toMs + " ORDER BY ts LIMIT " + max, 5);
        List<GrimRow> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new GrimRow(uuid.toString(), asStr(r[4]), asLong(r[0]), asStr(r[1]),
                    asDouble(r[2]), asInt(r[3]) != 0));
        }
        return out;
    }

    public static List<DigRow> digs(UUID uuid, long fromMs, long toMs, int max) {
        List<Object[]> rows = query(readConn, "SELECT ts,action,x,y,z,block,name"
                + " FROM digs WHERE uuid='" + uuid + "' AND ts>=" + fromMs + " AND ts<=" + toMs + " ORDER BY ts LIMIT " + max, 7);
        List<DigRow> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new DigRow(uuid.toString(), asStr(r[6]), asLong(r[0]), asStr(r[1]),
                    asInt(r[2]), asInt(r[3]), asInt(r[4]), asStr(r[5])));
        }
        return out;
    }

    private static long asLong(Object o) {
        if (o instanceof Long l) return l;
        if (o instanceof Integer i) return i;
        if (o instanceof Double d) return (long) (double) d;
        return 0L;
    }

    private static int asInt(Object o) { return (int) asLong(o); }

    private static double asDouble(Object o) {
        if (o instanceof Double d) return d;
        if (o instanceof Long l) return l;
        if (o instanceof Integer i) return i;
        return 0.0;
    }

    private static String asStr(Object o) { return o == null ? "" : o.toString(); }

    /** Reflective select (read connection). sqlite-jdbc getObject yields Integer/Long/Double/String. */
    private static List<Object[]> query(Object conn, String sql, int cols) {
        List<Object[]> out = new ArrayList<>();
        try {
            Object st = mPrepare.invoke(conn, sql);
            try {
                mSetQueryTimeout.invoke(st, 10);
                Object rs = st.getClass().getMethod("executeQuery").invoke(st);
                while ((Boolean) mRsNext.invoke(rs)) {
                    Object[] row = new Object[cols];
                    for (int i = 0; i < cols; i++) row[i] = mRsObject.invoke(rs, i + 1);
                    out.add(row);
                }
            } finally {
                mClose.invoke(st);
            }
        } catch (Exception e) {
            LOGGER.warning("Evidence query failed: " + e);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // driver jar download (pinned Maven Central artifact, sha1-verified)
    // ------------------------------------------------------------------

    private static File ensureDriverJar(File libDir) throws Exception {
        if (!libDir.exists()) libDir.mkdirs();
        File jar = new File(libDir, "sqlite-jdbc-" + SQLITE_VERSION + ".jar");
        if (jar.exists() && jar.length() > 0) return jar;
        String base = "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/" + SQLITE_VERSION + "/";
        byte[] sha1Expected = fetch(new URI(base + "sqlite-jdbc-" + SQLITE_VERSION + ".jar.sha1").toURL());
        File tmp = new File(libDir, jar.getName() + ".tmp");
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream raw = new URI(base + "sqlite-jdbc-" + SQLITE_VERSION + ".jar").toURL().openStream();
             DigestInputStream in = new DigestInputStream(raw, md)) {
            java.nio.file.Files.copy(in, tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        String actual = hex(md.digest());
        String expected = new String(sha1Expected, StandardCharsets.UTF_8).trim();
        if (!actual.equalsIgnoreCase(expected)) {
            Files.deleteIfExists(tmp.toPath());
            throw new RuntimeException("sqlite-jdbc download failed sha1 verification");
        }
        Files.move(tmp.toPath(), jar.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        LOGGER.info("Downloaded sqlite-jdbc " + SQLITE_VERSION + " (" + jar.length() / 1024 / 1024 + "MB)");
        return jar;
    }

    private static byte[] fetch(URL url) throws Exception {
        try (InputStream in = url.openStream()) {
            return in.readAllBytes();
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    private static void closeQuietly(Object closable) {
        if (closable == null) return;
        try {
            closable.getClass().getMethod("close").invoke(closable);
        } catch (Exception ignored) {}
    }
}
