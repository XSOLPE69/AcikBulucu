package tr.acikbulucuai;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Doğrulama, yedekleme, uygulama, geri alma, işlem günlüğü. Bukkit'e bağımlı değil. */
final class Ops {
    interface Runner { void run(String command); }

    static final String SKRIPT_DIR = "plugins/skript/scripts/";

    final Sandbox sb;
    final File dataDir;
    final Set<String> banned;

    Ops(Sandbox sb, File dataDir, Collection<String> bannedCommands) {
        this.sb = sb;
        this.dataDir = dataDir;
        this.banned = new HashSet<>();
        for (String s : bannedCommands) this.banned.add(s.toLowerCase(Locale.ROOT).replaceFirst("^/", ""));
    }

    // ---------- okuma araçları (yan etkisiz) ----------

    String runRead(Action a) {
        try {
            if (Action.LISTELE.equals(a.tur)) return list(a.yol);
            return read(a.yol, a.sonSatir);
        } catch (SecurityException | IOException e) {
            return "[HATA " + a.yol + ": " + e.getMessage() + "]\n";
        }
    }

    private String list(String path) throws IOException {
        File dir = sb.resolveDir(path);
        StringBuilder b = new StringBuilder("=== klasör: ").append(path == null || path.isBlank() ? "." : path).append(" ===\n");
        if (dir == null) {
            b.append("plugins/\nconfig/\n");
            for (String f : Sandbox.ROOT_FILES) if (new File(sb.root, f).isFile()) b.append(f).append('\n');
            b.append("logs/latest.log (sadece okuma)\n");
            return b.toString();
        }
        File[] fs = dir.listFiles();
        if (fs == null) return b.append("(okunamadı)\n").toString();
        Arrays.sort(fs, Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        int n = 0;
        for (File f : fs) {
            if (f.getName().equalsIgnoreCase("AcikBulucuAI") && dir.equals(new File(sb.root, "plugins"))) continue;
            if (++n > 200) { b.append("... (liste kısaltıldı)\n"); break; }
            b.append(f.getName()).append(f.isDirectory() ? "/" : "  (" + f.length() + " bayt)").append('\n');
        }
        return b.toString();
    }

    private String read(String path, int tail) throws IOException {
        File f = sb.resolve(path, false);
        if (!f.isFile()) throw new IOException("Dosya yok: " + path);
        boolean log = f.getName().toLowerCase(Locale.ROOT).endsWith(".log");
        String text;
        String note = "";
        if (tail > 0) {
            int want = Math.min(tail, 300);
            try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
                long len = r.length(), start = Math.max(0, len - 256 * 1024);
                byte[] buf = new byte[(int) (len - start)];
                r.seek(start); r.readFully(buf);
                String[] lines = new String(buf, StandardCharsets.UTF_8).split("\r?\n");
                int from = Math.max(0, lines.length - want);
                text = String.join("\n", Arrays.copyOfRange(lines, from, lines.length));
            }
            note = " (son " + want + " satır)";
        } else {
            long len = f.length();
            byte[] buf;
            try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
                buf = new byte[(int) Math.min(len, Sandbox.MAX_READ)];
                r.readFully(buf);
            }
            text = new String(buf, StandardCharsets.UTF_8);
            if (len > Sandbox.MAX_READ) note = " (ilk " + Sandbox.MAX_READ + " bayt, dosya " + len + " bayt)";
        }
        return "=== dosya: " + path + note + " ===\n" + Sandbox.redact(text, log) + "\n=== son ===\n";
    }

    // ---------- doğrulama ----------

    /** null = geçerli, aksi halde red nedeni. */
    String validate(Action a) {
        try {
            if (Action.YAZ.equals(a.tur)) {
                if (a.icerik == null) return "içerik yok";
                if (a.icerik.length() > Sandbox.MAX_WRITE) return "içerik çok büyük";
                if (a.icerik.contains(Sandbox.MARKER)) return "içerikte " + Sandbox.MARKER + " var (gizli değer yer tutucusu)";
                File f = sb.resolve(a.yol, true);
                if (f.isDirectory()) return "bu bir klasör";
                if (f.isFile() && Sandbox.hasSecret(readAll(f)))
                    return "dosyada gizli değerler var, tamamen üzerine yazılamaz; dosya_duzenle kullan";
                return null;
            }
            if (Action.DUZENLE.equals(a.tur)) {
                if (a.bul == null || a.bul.isEmpty()) return "'bul' boş";
                if (a.degistir == null) return "'degistir' yok";
                if (a.bul.contains(Sandbox.MARKER) || a.degistir.contains(Sandbox.MARKER))
                    return Sandbox.MARKER + " yer tutucusu düzenlenemez";
                File f = sb.resolve(a.yol, true);
                if (!f.isFile()) return "dosya yok: " + a.yol;
                String res = replaceOnce(readAll(f), a.bul, a.degistir);
                if (res == null) return "'bul' metni dosyada tam olarak 1 kez geçmiyor";
                if (res.length() > Sandbox.MAX_WRITE) return "sonuç çok büyük";
                return null;
            }
            if (Action.KOMUT.equals(a.tur)) return checkCommand(a.komut);
            return "bilinmeyen işlem";
        } catch (SecurityException | IOException e) {
            return e.getMessage();
        }
    }

    String checkCommand(String cmd) {
        if (cmd == null) return "komut yok";
        String c = cmd.trim();
        if (c.startsWith("/")) c = c.substring(1).trim();
        if (c.isEmpty() || c.length() > 200 || c.indexOf('\n') >= 0 || c.indexOf('\r') >= 0) return "geçersiz komut";
        String first = c.split("\\s+")[0].toLowerCase(Locale.ROOT);
        String bare = first.contains(":") ? first.substring(first.indexOf(':') + 1) : first;
        if (banned.contains(first) || banned.contains(bare)) return "yasaklı komut: " + first;
        return null;
    }

    static String normCommand(String cmd) {
        String c = cmd.trim();
        return c.startsWith("/") ? c.substring(1).trim() : c;
    }

    private static String readAll(File f) throws IOException { return Files.readString(f.toPath(), StandardCharsets.UTF_8); }

    /** 'bul' tam 1 kez geçiyorsa değiştirilmiş metin, değilse null. CRLF dosyalarını da tolere eder. */
    static String replaceOnce(String content, String bul, String deg) {
        String r = once(content, bul, deg);
        if (r == null && content.contains("\r\n") && !bul.contains("\r"))
            r = once(content, bul.replace("\n", "\r\n"), deg.replace("\n", "\r\n"));
        return r;
    }

    private static String once(String content, String bul, String deg) {
        int i = content.indexOf(bul);
        if (i < 0 || content.indexOf(bul, i + 1) >= 0) return null;
        return content.substring(0, i) + deg + content.substring(i + bul.length());
    }

    // ---------- gösterim ----------

    List<String> describe(Action a, boolean full) {
        List<String> out = new ArrayList<>();
        if (Action.YAZ.equals(a.tur)) {
            boolean exists = false;
            try { exists = sb.resolve(a.yol, true).isFile(); } catch (Exception ignored) { }
            String[] lines = a.icerik.split("\r?\n", -1);
            out.add("DOSYA YAZ: " + a.yol + "  (" + (exists ? "üstüne yazar" : "yeni dosya") + ", " + lines.length + " satır)");
            int max = full ? 150 : 12;
            for (int i = 0; i < Math.min(lines.length, max); i++) out.add("  | " + lines[i]);
            if (lines.length > max) out.add("  | ... (+" + (lines.length - max) + " satır" + (full ? "" : ", tamamı için /ai goster") + ")");
        } else if (Action.DUZENLE.equals(a.tur)) {
            out.add("DOSYA DÜZENLE: " + a.yol);
            int max = full ? 80 : 8;
            String[] b = a.bul.split("\r?\n", -1), d = a.degistir.split("\r?\n", -1);
            for (int i = 0; i < Math.min(b.length, max); i++) out.add("  - " + b[i]);
            if (b.length > max) out.add("  - ...");
            for (int i = 0; i < Math.min(d.length, max); i++) out.add("  + " + d[i]);
            if (d.length > max) out.add("  + ...");
        } else {
            out.add("KOMUT (konsol): /" + normCommand(a.komut));
        }
        if (a.neden != null && !a.neden.isBlank()) out.add("  neden: " + a.neden.trim());
        return out;
    }

    // ---------- uygulama / geri alma ----------

    List<String> apply(List<Action> acts, String who, Runner runner, boolean skriptReload, String reloadCmd) {
        List<String> res = new ArrayList<>();
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File bdir = new File(dataDir, "yedek/" + ts);
        StringBuilder manifest = new StringBuilder();
        Set<String> touched = new LinkedHashSet<>();
        int n = 0;
        for (Action a : acts) {
            n++;
            String err = validate(a);
            if (err != null) { res.add("✗ [" + n + "] atlandı: " + err); continue; }
            try {
                if (Action.KOMUT.equals(a.tur)) {
                    String c = normCommand(a.komut);
                    runner.run(c);
                    log(who, "KOMUT /" + c);
                    res.add("✓ [" + n + "] komut çalıştı: /" + c);
                    continue;
                }
                File f = sb.resolve(a.yol, true);
                String rel = sb.relOf(f);
                boolean existed = f.isFile();
                if (existed) {
                    File bk = new File(bdir, rel);
                    bk.getParentFile().mkdirs();
                    Files.copy(f.toPath(), bk.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                manifest.append(existed ? "E\t" : "N\t").append(rel).append('\n');
                String data = Action.YAZ.equals(a.tur) ? a.icerik : replaceOnce(readAll(f), a.bul, a.degistir);
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                Files.write(f.toPath(), data.getBytes(StandardCharsets.UTF_8));
                touched.add(rel);
                log(who, (Action.YAZ.equals(a.tur) ? "YAZ " : "DÜZENLE ") + rel + " (" + data.length() + " karakter)");
                res.add("✓ [" + n + "] " + (Action.YAZ.equals(a.tur) ? "yazıldı: " : "düzenlendi: ") + rel);
            } catch (IOException | SecurityException e) {
                res.add("✗ [" + n + "] hata: " + e.getMessage());
            }
        }
        if (manifest.length() > 0) {
            try {
                bdir.mkdirs();
                Files.writeString(new File(bdir, "_manifest.txt").toPath(), manifest.toString(), StandardCharsets.UTF_8);
                res.add("Yedek: plugins/AcikBulucuAI/yedek/" + ts + "  (geri almak için /ai geriyukle)");
            } catch (IOException e) { res.add("Uyarı: manifest yazılamadı: " + e.getMessage()); }
        }
        if (skriptReload) res.addAll(reloadScripts(touched, runner, reloadCmd));
        return res;
    }

    List<String> reloadScripts(Collection<String> rels, Runner runner, String reloadCmd) {
        List<String> res = new ArrayList<>();
        for (String rel : rels) {
            String low = rel.toLowerCase(Locale.ROOT);
            if (!low.startsWith(SKRIPT_DIR) || !low.endsWith(".sk")) continue;
            String name = rel.substring(SKRIPT_DIR.length(), rel.length() - 3);
            if (name.startsWith("-") || name.contains("/-")) continue;
            String c = reloadCmd.replace("{script}", name);
            runner.run(c);
            res.add("↻ Skript yenilendi: /" + c);
        }
        return res;
    }

    List<String> restoreLatest(String who, Runner runner, boolean skriptReload, String reloadCmd) {
        List<String> res = new ArrayList<>();
        File[] dirs = new File(dataDir, "yedek").listFiles(f -> f.isDirectory() && !f.getName().endsWith("_geri"));
        if (dirs == null || dirs.length == 0) { res.add("Geri alınacak yedek yok."); return res; }
        Arrays.sort(dirs, Comparator.comparing(File::getName).reversed());
        File bdir = dirs[0];
        File mf = new File(bdir, "_manifest.txt");
        if (!mf.isFile()) { res.add("Manifest bulunamadı: " + bdir.getName()); return res; }
        Set<String> touched = new LinkedHashSet<>();
        try {
            for (String line : Files.readAllLines(mf.toPath(), StandardCharsets.UTF_8)) {
                String[] p = line.split("\t", 2);
                if (p.length < 2) continue;
                File f = sb.resolve(p[1], true);
                if (p[0].equals("E")) {
                    Files.copy(new File(bdir, p[1]).toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    res.add("↩ geri yüklendi: " + p[1]);
                } else {
                    Files.deleteIfExists(f.toPath());
                    res.add("↩ silindi (yeni oluşturulmuştu): " + p[1]);
                    if (p[1].toLowerCase(Locale.ROOT).endsWith(".sk")) res.add("  not: silinen script bellekte yüklü kalabilir; /sk reload scripts yaz.");
                    continue;
                }
                touched.add(p[1]);
            }
            log(who, "GERİ ALMA " + bdir.getName());
            File done = new File(bdir.getParentFile(), bdir.getName() + "_geri");
            if (!bdir.renameTo(done)) res.add("Uyarı: yedek klasörü yeniden adlandırılamadı.");
        } catch (IOException | SecurityException e) {
            res.add("✗ Geri alma hatası: " + e.getMessage());
        }
        if (skriptReload) res.addAll(reloadScripts(touched, runner, reloadCmd));
        res.add("Not: çalıştırılmış komutlar geri alınmaz.");
        return res;
    }

    private void log(String who, String what) {
        try {
            dataDir.mkdirs();
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " | " + who + " | " + what + "\n";
            Files.writeString(new File(dataDir, "islem.log").toPath(), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }
}
