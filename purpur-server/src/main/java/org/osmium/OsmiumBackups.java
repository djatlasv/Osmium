package org.osmium;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Native auto-backup: zips world folders + root configs into
 * backups/backup-YYYY-MM-DD_HHMM.tar.gz-style zips on a configurable
 * interval, keeping the newest N generations.
 *
 * Consistency strategy: worlds are saved (main thread) immediately before
 * the copy starts, and the copy runs on a single background thread. A
 * block edit racing the copy can produce one stale chunk inside an
 * otherwise-consistent archive — the standard trade-off for live backups.
 *
 * Root config files (osmium.yml, eula.txt, server.properties,
 * osmium-*.json, whitelist.json, ops.json, banned-*.json) are always
 * included.
 */
public final class OsmiumBackups {

    private static final Logger LOGGER = LogManager.getLogger("Osmium-Backups");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm");

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Osmium-Backup");
        t.setDaemon(true);
        return t;
    });

    private OsmiumBackups() {}

    /** Tick driver — called from MinecraftServer.tickChildren (main thread). */
    public static void tick(MinecraftServerLike server) {
        if (!OsmiumConfig.backupsEnabled) return;
        long intervalTicks = Math.max(1, OsmiumConfig.backupsIntervalMinutes) * 60L * 20L;
        long now = server.currentTick();
        if (now - lastBackupTick < intervalTicks) return;
        lastBackupTick = now;

        if (!RUNNING.compareAndSet(false, true)) return; // previous still running

        // Save everything first so the copies are as fresh as possible
        server.saveAllChunks();

        List<File> worldDirs = detectWorlds(new File("."));
        List<File> configFiles = detectConfigs(new File("."));

        WORKER.execute(() -> {
            try {
                createArchive(worldDirs, configFiles);
                pruneOld();
            } catch (Exception e) {
                LOGGER.error("Backup failed: {}", e.getMessage(), e);
            } finally {
                RUNNING.set(false);
            }
        });
    }

    private static long lastBackupTick = Long.MIN_VALUE / 2; // allow immediate first backup shortly after boot

    /** Manual trigger (/backup now). Returns false if one is already running. */
    public static boolean triggerNow(MinecraftServerLike server) {
        if (!RUNNING.compareAndSet(false, true)) return false;
        lastBackupTick = server.currentTick();
        server.saveAllChunks();
        List<File> worldDirs = detectWorlds(new File("."));
        List<File> configFiles = detectConfigs(new File("."));
        WORKER.execute(() -> {
            try {
                createArchive(worldDirs, configFiles);
                pruneOld();
                LOGGER.info("Manual backup complete");
            } catch (Exception e) {
                LOGGER.error("Manual backup failed: {}", e.getMessage(), e);
            } finally {
                RUNNING.set(false);
            }
        });
        return true;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static void createArchive(List<File> worldDirs, List<File> configFiles) throws Exception {
        File backupDir = new File(OsmiumConfig.backupsDirectory);
        if (!backupDir.exists()) backupDir.mkdirs();

        String name = "backup-" + LocalDateTime.now().format(STAMP) + ".zip";
        Path target = new File(backupDir, name).toPath();

        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(target.toFile()))) {
            byte[] buf = new byte[8192];
            for (File dir : worldDirs) addDir(zip, dir, dir.getName(), buf);
            for (File f : configFiles) addFile(zip, f, f.getName(), buf);
        }
        long sizeKb = Files.size(target) / 1024;
        LOGGER.info("Backup written: {} ({} KB)", target.getFileName(), sizeKb);
    }

    private static void addDir(ZipOutputStream zip, File dir, String prefix, byte[] buf) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                addDir(zip, f, prefix + "/" + f.getName(), buf);
            } else {
                addFile(zip, f, prefix + "/" + f.getName(), buf);
            }
        }
    }

    private static void addFile(ZipOutputStream zip, File file, String entryName, byte[] buf) throws Exception {
        ZipEntry entry = new ZipEntry(entryName);
        entry.setTime(file.lastModified());
        zip.putNextEntry(entry);
        try (var in = Files.newInputStream(file.toPath())) {
            int len;
            while ((len = in.read(buf)) > 0) zip.write(buf, 0, len);
        }
        zip.closeEntry();
    }

    private static void pruneOld() {
        File backupDir = new File(OsmiumConfig.backupsDirectory);
        File[] backups = backupDir.listFiles((d, n) -> n.startsWith("backup-") && n.endsWith(".zip"));
        if (backups == null || backups.length <= OsmiumConfig.backupsKeep) return;

        Arrays.sort(backups, Comparator.comparingLong(File::lastModified).reversed());
        int removed = 0;
        for (int i = OsmiumConfig.backupsKeep; i < backups.length; i++) {
            if (backups[i].delete()) removed++;
        }
        if (removed > 0) LOGGER.info("Pruned {} old backups", removed);
    }

    private static List<File> detectWorlds(File root) {
        List<String> names = new ArrayList<>();
        if (OsmiumConfig.backupsWorlds.isBlank()) {
            // auto-detect: any directory with level.dat + region/
            File[] dirs = root.listFiles(File::isDirectory);
            if (dirs != null) {
                for (File d : dirs) {
                    if (new File(d, "level.dat").exists() && new File(d, "region").isDirectory()) {
                        names.add(d.getName());
                    }
                }
            }
        } else {
            for (String n : OsmiumConfig.backupsWorlds.split(",")) {
                String trimmed = n.trim();
                if (!trimmed.isEmpty()) names.add(trimmed);
            }
        }
        List<File> out = new ArrayList<>();
        for (String n : names) {
            File d = new File(root, n);
            if (d.isDirectory()) out.add(d);
            else LOGGER.warn("Configured backup world '{}' not found — skipping", n);
        }
        return out;
    }

    private static final String[] CONFIG_NAMES = {
            "osmium.yml", "eula.txt", "server.properties", "whitelist.json", "ops.json",
            "banned-players.json", "banned-ips.json", "usercache.json",
            "osmium-discord-bot.json", "osmium-homes.json",
            "osmium-ips.json", "osmium-fingerprints.json", "osmium-teams.json"
    };

    private static List<File> detectConfigs(File root) {
        List<File> out = new ArrayList<>();
        for (String n : CONFIG_NAMES) {
            File f = new File(root, n);
            if (f.isFile()) out.add(f);
        }
        return out;
    }

    /** Minimal server surface so this class stays unit-testable. */
    public interface MinecraftServerLike {
        long currentTick();
        void saveAllChunks();
    }
}
