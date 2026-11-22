package com.mcargus.install;

import com.github.junrar.Archive;
import com.github.junrar.exception.RarException;
import com.github.junrar.rarfile.FileHeader;
import com.mcargus.Config;
import com.mcargus.Errors;
import com.mcargus.i18n.Messages;
import com.mcargus.util.Log;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 服务端压缩包自动安装：目录内没有任何启动目标时，识别
 * zip / tar / tar.gz / tar.bz2 / tar.xz / 7z / rar 压缩包，
 * 解压到隔离暂存目录，定位服务端根目录（支持无包裹/单层/多层包裹），再安装到服务器根目录。
 * RAR 解压由 junrar 提供（UnRAR License，见 THIRD-PARTY-NOTICES.md）。
 *
 * 安全与幂等设计：
 *  - 压缩包以"魔数+扩展名"双重识别，绝不改动原压缩包；
 *  - zip 用 commons-compress 流式读取：不校验 CEN 一致性，能容忍中文打包软件产出的
 *    "非法 CEN header"（JDK 17+ 的 java.util.zip 会直接拒绝，典型如条目名含 emoji 的包）；
 *    声明 UTF-8(EFS) 的条目按 UTF-8 解码，其余条目按 install.zipEncoding（默认 GBK）；
 *  - 一律解压到 mcargus 下的 install-&lt;时间戳&gt; 暂存目录，安装到根目录时跳过已存在文件（不覆盖）；
 *  - 防路径穿越（zip slip）：每个条目规范化后必须仍位于暂存目录内；
 *  - 多个压缩包时按"服务端标志文件"打分选择，无法判定则中止并列出候选，绝不乱选；
 *  - CurseForge 客户端整合包（manifest.json/modlist.html）明确报错引导，不误启动；
 *  - 已安装的压缩包记录在 install.completed（含签名），不会重复解压。
 */
public final class Installer {
    private static final String[] STRONG_MARKERS = {"unix_args.txt", "win_args.txt", "server.jar", "run.sh", "run.bat"};
    private static final String[] WEAK_MARKERS = {"libraries", "mods", "eula.txt", "server.properties", "user_jvm_args.txt"};
    private static final int MAX_ARCHIVE_SCAN_ENTRIES = 20000;

    private Installer() {
    }

    /** 无启动目标时调用：尝试安装；无事可做则安静返回，有问题抛带明确指引的 PreflightException。 */
    public static void ensureInstalled(Path serverDir, Config cfg) {
        List<Path> archives = findArchives(serverDir);
        if (archives.isEmpty()) {
            Log.info(Messages.get("install.noArchives"));
            return; // 维持原有的"未找到启动目标"错误
        }
        Log.info(Messages.get("install.foundArchives", archives.size()));
        String zipEncoding = cfg.get(Config.K_ZIP_ENCODING, "GBK");
        Path chosen = pickArchive(archives, zipEncoding);
        String name = chosen.getFileName().toString();
        long size;
        try {
            size = Files.size(chosen);
        } catch (IOException e) {
            throw new Errors.PreflightException(Messages.get("install.unreadable", chosen));
        }
        String sig = name + ":" + size;
        if (sig.equals(cfg.get(Config.K_INSTALL_COMPLETED, ""))) {
            String note = cfg.get(Config.K_INSTALL_NOTE, "");
            throw new Errors.PreflightException(Messages.get("install.alreadyInstalled", name,
                    note.isEmpty() ? "" : "\n" + Messages.get("install.lastNote", note)));
        }

        Log.info(Messages.get("install.selected", name, size / 1024 / 1024));
        Path staging = serverDir.resolve("mcargus").resolve("install-" + Long.toString(System.currentTimeMillis(), 36));
        try {
            int files = extract(chosen, staging, zipEncoding);
            Log.info(Messages.get("install.extracted", files));
            if (isCurseForgeClientPack(staging)) {
                recordAndAbort(cfg, sig, Messages.get("install.note.cfClient"),
                        Messages.get("install.cfClientStaging",
                                Files.isDirectory(staging.resolve("overrides")) ? "overrides/" : "modlist.html",
                                staging));
            }
            Path root = detectServerRoot(staging);
            if (root == null) {
                recordAndAbort(cfg, sig, Messages.get("install.note.noRoot"),
                        Messages.get("install.noRoot", staging));
            }
            if (isCurseForgeClientPack(root)) {
                recordAndAbort(cfg, sig, Messages.get("install.note.cfClient"),
                        Messages.get("install.cfClientRoot", staging));
            }
            int[] counts = installToRoot(root, serverDir);
            if (counts[0] == 0) {
                recordAndAbort(cfg, sig, Messages.get("install.note.nothingInstalled"),
                        Messages.get("install.nothingInstalled", counts[1], counts[2]));
            }
            cfg.set(Config.K_INSTALL_COMPLETED, sig);
            cfg.set(Config.K_INSTALL_NOTE, Messages.get("install.note.ok", name));
            cfg.saveIfDirty();
            Log.info(Messages.get("install.done", counts[0], counts[1], counts[2]));
            if (counts[1] > 0) {
                Log.warn(Messages.get("install.skippedExisting", counts[1]));
            }
            deleteRecursively(staging);
        } catch (Errors.PreflightException e) {
            throw e;
        } catch (Exception e) {
            throw new Errors.PreflightException(Messages.get("install.failed", String.valueOf(e), staging));
        }
    }

    // ---------------------------------------------------------------- 识别与选择

    static List<Path> findArchives(Path serverDir) {
        final List<Path> out = new ArrayList<Path>();
        try {
            Files.walkFileTree(serverDir, EnumSet.noneOf(FileVisitOption.class), 2,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
                            if (n.equalsIgnoreCase("mcargus") || n.startsWith(".")) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (!attrs.isRegularFile() || attrs.size() < 32) {
                                return FileVisitResult.CONTINUE;
                            }
                            String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
                            if (hasArchiveExtension(n) && isArchiveMagic(file)) {
                                out.add(file.toAbsolutePath().normalize());
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            Log.warn(Messages.get("install.scanFailed", e.getMessage()));
        }
        return out;
    }

    /** 按扩展名判断是否可能是支持的压缩包（最终还要通过魔数校验）。 */
    private static boolean hasArchiveExtension(String n) {
        return n.endsWith(".zip")
                || n.endsWith(".tar")
                || n.endsWith(".tar.gz") || n.endsWith(".tgz")
                || n.endsWith(".tar.bz2") || n.endsWith(".tbz2")
                || n.endsWith(".tar.xz") || n.endsWith(".txz")
                || n.endsWith(".7z")
                || n.endsWith(".rar");
    }

    /** 扩展名 + 魔数双重校验（zip/gzip/bzip2/xz/7z/rar 头；tar: 257 偏移处 ustar）。 */
    static boolean isArchiveMagic(Path file) {
        InputStream in = null;
        try {
            in = new BufferedInputStream(Files.newInputStream(file), 512);
            byte[] buf = new byte[262];
            int total = 0;
            int r;
            while (total < buf.length && (r = in.read(buf, total, buf.length - total)) > 0) {
                total += r;
            }
            if (total >= 4 && buf[0] == 'P' && buf[1] == 'K' && (buf[2] == 3 || buf[2] == 5) && (buf[3] == 4 || buf[3] == 6)) {
                return true;
            }
            if (total >= 2 && (buf[0] & 0xff) == 0x1f && (buf[1] & 0xff) == 0x8b) {
                return true; // gzip
            }
            if (total >= 3 && buf[0] == 'B' && buf[1] == 'Z' && buf[2] == 'h') {
                return true; // bzip2
            }
            if (total >= 6 && (buf[0] & 0xff) == 0xfd && buf[1] == '7' && buf[2] == 'z'
                    && buf[3] == 'X' && buf[4] == 'Z' && buf[5] == 0) {
                return true; // xz
            }
            if (total >= 6 && buf[0] == '7' && buf[1] == 'z' && (buf[2] & 0xff) == 0xbc
                    && (buf[3] & 0xff) == 0xaf && buf[4] == 0x27 && buf[5] == 0x1c) {
                return true; // 7z
            }
            if (total >= 7 && buf[0] == 'R' && buf[1] == 'a' && buf[2] == 'r'
                    && buf[3] == '!' && buf[4] == 0x1a && buf[5] == 0x07
                    && (buf[6] == 0 || buf[6] == 1)) {
                return true; // rar4 / rar5
            }
            return total >= 262 && buf[257] == 'u' && buf[258] == 's' && buf[259] == 't' && buf[260] == 'a' && buf[261] == 'r';
        } catch (Exception e) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 多包策略：按服务端标志条目打分；唯一最高分者当选，无法判定则中止并列出全部候选。 */
    static Path pickArchive(List<Path> archives, String zipEncoding) {
        if (archives.size() == 1) {
            return archives.get(0);
        }
        Map<Path, Integer> scores = new LinkedHashMap<Path, Integer>();
        int max = 0;
        for (Path a : archives) {
            int s = scoreArchive(a, zipEncoding);
            scores.put(a, s);
            if (s > max) {
                max = s;
            }
        }
        List<Path> tied = new ArrayList<Path>();
        for (Map.Entry<Path, Integer> e : scores.entrySet()) {
            if (e.getValue() == max && max > 0) {
                tied.add(e.getKey());
            }
        }
        if (tied.size() == 1) {
            for (Map.Entry<Path, Integer> e : scores.entrySet()) {
                Log.info(Messages.get("install.candidate", e.getKey().getFileName(), e.getValue(),
                        e.getKey().equals(tied.get(0)) ? Messages.get("install.candidateChosen") : ""));
            }
            return tied.get(0);
        }
        StringBuilder sb = new StringBuilder(Messages.get("install.multiAbortHead"));
        for (Map.Entry<Path, Integer> e : scores.entrySet()) {
            sb.append("  - ").append(e.getKey().getFileName())
                    .append(Messages.get("install.multiAbortScore", e.getValue())).append('\n');
        }
        sb.append(Messages.get("install.multiAbortHint"));
        throw new Errors.PreflightException(sb.toString());
    }

    /** 强标志（unix_args/server.jar/run.*）计 5 分，弱标志（libraries/mods/eula 等）每类计 1 分。 */
    static int scoreArchive(Path archive, String zipEncoding) {
        List<String> names = listEntryNames(archive, MAX_ARCHIVE_SCAN_ENTRIES, zipEncoding);
        if (names == null) {
            return 0;
        }
        // 每个条目只取一次“根层名 / 末段名”，避免对每个标记重复 toLowerCase 与全表扫描。
        Set<String> parts = new HashSet<String>(names.size() * 2);
        for (String n : names) {
            parts.add(n);
            int first = n.indexOf('/');
            if (first >= 0) {
                parts.add(n.substring(0, first));
                int last = n.lastIndexOf('/');
                if (last + 1 < n.length()) {
                    parts.add(n.substring(last + 1));
                }
            }
        }
        int score = 0;
        for (String m : STRONG_MARKERS) {
            if (parts.contains(m)) {
                score += 5;
            }
        }
        for (String m : WEAK_MARKERS) {
            if (parts.contains(m)) {
                score += 1;
            }
        }
        return score;
    }

    private static List<String> listEntryNames(Path archive, int cap, String zipEncoding) {
        String n = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (n.endsWith(".7z")) {
                return list7zEntryNames(archive, cap);
            }
            if (n.endsWith(".rar")) {
                return listRarEntryNames(archive, cap);
            }
            List<String> out = new ArrayList<String>();
            try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive), 1 << 16)) {
                ArchiveInputStream<? extends ArchiveEntry> tin;
                if (n.endsWith(".zip")) {
                    // 流式读取：不解析 central directory，规避 JDK 对中文打包软件 zip 的严格校验失败
                    tin = newZipStream(raw, zipEncoding);
                } else {
                    tin = new TarArchiveInputStream(tarStream(raw, n));
                }
                try {
                    ArchiveEntry e;
                    while ((e = tin.getNextEntry()) != null && out.size() < cap) {
                        out.add(normalizeEntryName(e.getName()));
                    }
                } finally {
                    tin.close();
                }
            }
            return out;
        } catch (Exception e) {
            Log.warn(Messages.get("install.entryReadFailed", archive.getFileName(), String.valueOf(e)));
            return null;
        }
    }

    private static List<String> list7zEntryNames(Path archive, int cap) throws IOException {
        List<String> out = new ArrayList<String>();
        try (SevenZFile sz = SevenZFile.builder().setPath(archive).get()) {
            SevenZArchiveEntry e;
            while ((e = sz.getNextEntry()) != null && out.size() < cap) {
                if (e.getName() != null) {
                    out.add(normalizeEntryName(e.getName()));
                }
            }
        }
        return out;
    }

    private static List<String> listRarEntryNames(Path archive, int cap) throws IOException, RarException {
        List<String> out = new ArrayList<String>();
        try (Archive ar = new Archive(archive.toFile())) {
            for (FileHeader fh : ar.getFileHeaders()) {
                if (out.size() >= cap) {
                    break;
                }
                if (fh.getFileName() != null) {
                    out.add(normalizeEntryName(fh.getFileName()));
                }
            }
        }
        return out;
    }

    /** 后续只做标记匹配：统一小写并把反斜杠规范成 '/'。 */
    private static String normalizeEntryName(String name) {
        return name.toLowerCase(Locale.ROOT).replace('\\', '/');
    }

    private static ZipArchiveInputStream newZipStream(InputStream raw, String encoding) {
        // useUnicodeExtraFields=true：EFS/Unicode extra field 的条目按 UTF-8，其余按给定编码
        // allowStoredEntriesWithDataDescriptor=true：容忍 data descriptor 形式的 stored 条目
        return new ZipArchiveInputStream(raw, encoding, true, true);
    }

    /** 按扩展名给 tar 流套上对应的解压器（plain tar 原样返回）。 */
    private static InputStream tarStream(InputStream raw, String n) throws IOException {
        if (n.endsWith(".tar.gz") || n.endsWith(".tgz")) {
            return new GzipCompressorInputStream(raw);
        }
        if (n.endsWith(".tar.bz2") || n.endsWith(".tbz2")) {
            return new BZip2CompressorInputStream(raw);
        }
        if (n.endsWith(".tar.xz") || n.endsWith(".txz")) {
            return new XZCompressorInputStream(raw);
        }
        return raw;
    }

    // ---------------------------------------------------------------- 解压

    static int extract(Path archive, Path staging, String zipEncoding) throws IOException {
        Files.createDirectories(staging);
        String n = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".zip")) {
            return extractZip(archive, staging, zipEncoding);
        }
        if (n.endsWith(".7z")) {
            return extract7z(archive, staging);
        }
        if (n.endsWith(".rar")) {
            try {
                return extractRar(archive, staging);
            } catch (RarException e) {
                throw new IOException(e.getMessage(), e);
            }
        }
        return extractTar(archive, staging, n);
    }

    /** 防 zip slip：条目路径规范化后必须仍位于暂存目录内。 */
    static Path safeTarget(Path staging, String entryName) {
        String name = entryName.replace('\\', '/');
        Path p = staging.resolve(name).normalize();
        if (!p.startsWith(staging) || name.contains("..")) {
            return null;
        }
        return p;
    }

    private static void copy(InputStream in, OutputStream out, byte[] buf) throws IOException {
        int r;
        while ((r = in.read(buf)) > 0) {
            out.write(buf, 0, r);
        }
    }

    private static int extractZip(Path archive, Path staging, String zipEncoding) throws IOException {
        int files = 0;
        byte[] buf = new byte[65536];
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive), 1 << 16);
             ZipArchiveInputStream zin = newZipStream(raw, zipEncoding)) {
            ZipArchiveEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                Path t = safeTarget(staging, e.getName());
                if (t == null) {
                    Log.warn(Messages.get("install.zipSlip", e.getName()));
                    continue;
                }
                if (t.getParent() != null) {
                    Files.createDirectories(t.getParent());
                }
                try (OutputStream out = Files.newOutputStream(t)) {
                    copy(zin, out, buf);
                }
                files++;
            }
        }
        return files;
    }

    private static int extract7z(Path archive, Path staging) throws IOException {
        int files = 0;
        byte[] buf = new byte[65536];
        try (SevenZFile sz = SevenZFile.builder().setPath(archive).get()) {
            SevenZArchiveEntry e;
            while ((e = sz.getNextEntry()) != null) {
                if (e.isDirectory() || e.isAntiItem() || e.getName() == null) {
                    continue;
                }
                Path t = safeTarget(staging, e.getName());
                if (t == null) {
                    Log.warn(Messages.get("install.tarSlip", e.getName()));
                    continue;
                }
                if (t.getParent() != null) {
                    Files.createDirectories(t.getParent());
                }
                if (e.hasStream()) {
                    try (InputStream in = sz.getInputStream(e); OutputStream out = Files.newOutputStream(t)) {
                        copy(in, out, buf);
                    }
                } else {
                    Files.newOutputStream(t).close(); // 空文件
                }
                files++;
            }
        }
        return files;
    }

    private static int extractRar(Path archive, Path staging) throws IOException, RarException {
        int files = 0;
        try (Archive ar = new Archive(archive.toFile())) {
            // 顺序读取，兼容 solid 压缩的 RAR
            FileHeader fh;
            while ((fh = ar.nextFileHeader()) != null) {
                if (fh.isDirectory()) {
                    continue;
                }
                String name = fh.getFileName();
                if (name == null) {
                    continue;
                }
                Path t = safeTarget(staging, name);
                if (t == null) {
                    Log.warn(Messages.get("install.tarSlip", name));
                    continue;
                }
                if (t.getParent() != null) {
                    Files.createDirectories(t.getParent());
                }
                try (OutputStream out = Files.newOutputStream(t)) {
                    ar.extractFile(fh, out);
                }
                files++;
            }
        }
        return files;
    }

    private static int extractTar(Path archive, Path staging, String n) throws IOException {
        int files = 0;
        byte[] buf = new byte[65536];
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive), 1 << 16);
             TarArchiveInputStream tin = new TarArchiveInputStream(tarStream(raw, n))) {
            TarArchiveEntry e;
            while ((e = tin.getNextEntry()) != null) {
                if (e.isDirectory() || e.isSymbolicLink() || e.isLink()) {
                    if (e.isSymbolicLink() || e.isLink()) {
                        Log.warn(Messages.get("install.linkEntry", e.getName()));
                    }
                    continue;
                }
                Path t = safeTarget(staging, e.getName());
                if (t == null) {
                    Log.warn(Messages.get("install.tarSlip", e.getName()));
                    continue;
                }
                if (t.getParent() != null) {
                    Files.createDirectories(t.getParent());
                }
                try (OutputStream out = Files.newOutputStream(t)) {
                    copy(tin, out, buf);
                }
                files++;
            }
        }
        return files;
    }

    // ---------------------------------------------------------------- 根定位与安装

    /**
     * 服务端根目录定位：从压缩包根开始，若当前层有服务端标志文件/目录则选中；
     * 否则当"仅包含唯一子目录"时下钻一层（支持一层或多层包裹），最多下钻 6 层。
     */
    static Path detectServerRoot(Path staging) {
        Path cur = staging;
        for (int i = 0; i < 6; i++) {
            if (hasServerMarkers(cur)) {
                return cur;
            }
            File[] all = cur.toFile().listFiles();
            if (all == null) {
                break;
            }
            List<File> dirs = new ArrayList<File>();
            for (File f : all) {
                if (f.isDirectory()) {
                    dirs.add(f);
                }
            }
            if (dirs.size() == 1 && all.length == 1) {
                cur = dirs.get(0).toPath();
                continue;
            }
            break;
        }
        return null;
    }

    static boolean hasServerMarkers(Path dir) {
        for (String m : STRONG_MARKERS) {
            if (Files.exists(dir.resolve(m))) {
                return true;
            }
        }
        for (String m : WEAK_MARKERS) {
            if (Files.exists(dir.resolve(m))) {
                return true;
            }
        }
        return false;
    }

    /** CurseForge 客户端整合包特征：根层 manifest.json + modlist.html 或 overrides/。 */
    static boolean isCurseForgeClientPack(Path root) {
        if (!Files.isRegularFile(root.resolve("manifest.json"))) {
            return false;
        }
        return Files.isRegularFile(root.resolve("modlist.html")) || Files.isDirectory(root.resolve("overrides"));
    }

    /** 安装到服务器根目录：不覆盖任何已存在文件。返回 {安装数, 跳过数, 失败数}。 */
    static int[] installToRoot(final Path root, final Path serverDir) throws IOException {
        final int[] counts = new int[3];
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = root.relativize(file);
                Path dest = serverDir.resolve(rel).normalize();
                if (!dest.startsWith(serverDir)) {
                    counts[2]++;
                    return FileVisitResult.CONTINUE;
                }
                try {
                    if (dest.getParent() != null) {
                        Files.createDirectories(dest.getParent());
                    }
                    Files.move(file, dest);
                    counts[0]++;
                } catch (IOException e) {
                    // 不先做 Files.exists 预检：让 move 直接判断，少一次 stat，也避免 TOCTOU。
                    if (Files.exists(dest)) {
                        counts[1]++;
                        Log.warn(Messages.get("install.skipExisting", rel));
                    } else {
                        counts[2]++;
                        Log.warn(Messages.get("install.moveFailed", rel, e.getMessage()));
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return counts;
    }

    private static void recordAndAbort(Config cfg, String sig, String note, String message) {
        cfg.set(Config.K_INSTALL_COMPLETED, sig);
        cfg.set(Config.K_INSTALL_NOTE, note);
        cfg.saveIfDirty();
        throw new Errors.PreflightException(message);
    }

    static void deleteRecursively(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            Log.warn(Messages.get("install.cleanupFailed", dir, e.getMessage()));
        }
    }
}
