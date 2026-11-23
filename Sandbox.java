package tr.acikbulucuai;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/** Yapay zekanın dokunabileceği yolları ve görebileceği metni sınırlar. */
final class Sandbox {
    static final String MARKER = "<GIZLI>";
    static final int MAX_READ = 60_000;
    static final int MAX_WRITE = 200_000;
    static final String OWN = "plugins/acikbulucuai";

    static final List<String> ROOT_FILES = List.of("server.properties", "bukkit.yml", "spigot.yml",
            "commands.yml", "permissions.yml", "help.yml");
    private static final Set<String> EXT = Set.of("sk", "yml", "yaml", "json", "txt", "properties",
            "conf", "toml", "cfg", "ini", "md", "csv");
    private static final Pattern SECRET = Pattern.compile(
            "(?im)^([ \\t-]*[\\w.\\-]*(?:password|passwd|token|secret|api[-_]?key|webhook|private[-_]?key|license[-_]?key|access[-_]?key)[\\w.\\-]*[ \\t]*[:=][ \\t]*)(\\S.*)$");
    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");

    final File root;

    Sandbox(File root) throws IOException { this.root = root.getCanonicalFile(); }

    /** Göreli yolu temizler; mutlak yol ve .. yasak. */
    static String clean(String rel) {
        if (rel == null || rel.isBlank()) throw new SecurityException("Yol boş.");
        String p = rel.trim().replace('\\', '/');
        if (p.startsWith("/") || p.matches("^[A-Za-z]:.*") || p.indexOf('\0') >= 0)
            throw new SecurityException("Mutlak yol yasak: " + rel);
        List<String> out = new ArrayList<>();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) throw new SecurityException("'..' yasak: " + rel);
            out.add(seg);
        }
        if (out.isEmpty()) throw new SecurityException("Yol boş.");
        return String.join("/", out);
    }

    private static boolean allowed(String low, boolean write) {
        return low.startsWith("plugins/") || low.startsWith("config/") || ROOT_FILES.contains(low)
                || (!write && low.equals("logs/latest.log"));
    }

    private static void notOwn(String low) {
        if (low.equals(OWN) || low.startsWith(OWN + "/"))
            throw new SecurityException("Kendi klasörüm (anahtar burada) yasak.");
    }

    /** Dosya yolunu doğrular ve gerçek File döndürür. */
    File resolve(String rel, boolean write) throws IOException {
        String p = clean(rel);
        String low = p.toLowerCase(Locale.ROOT);
        if (!allowed(low, write)) throw new SecurityException("Bu konuma erişim yok: " + p);
        notOwn(low);
        boolean log = low.equals("logs/latest.log");
        String name = low.substring(low.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        if (!log && !EXT.contains(ext)) throw new SecurityException("Bu dosya türü yasak: ." + ext);
        File f = new File(root, p).getCanonicalFile();
        String real = relOf(f);
        if (real == null) throw new SecurityException("Sunucu klasörü dışına çıkılamaz.");
        String rl = real.toLowerCase(Locale.ROOT);
        if (!allowed(rl, write)) throw new SecurityException("Bu konuma erişim yok: " + real);
        notOwn(rl);
        return f;
    }

    /** Klasör yolu. null = sanal kök listesi. */
    File resolveDir(String rel) throws IOException {
        String raw = rel == null ? "" : rel.trim().replace('\\', '/');
        if (raw.isEmpty() || raw.equals(".") || raw.equals("/")) return null;
        String p = clean(raw);
        String low = p.toLowerCase(Locale.ROOT);
        if (!(low.equals("plugins") || low.equals("config") || low.startsWith("plugins/") || low.startsWith("config/")))
            throw new SecurityException("Bu klasöre erişim yok: " + p);
        notOwn(low);
        File f = new File(root, p).getCanonicalFile();
        String real = relOf(f);
        if (real == null) throw new SecurityException("Sunucu klasörü dışına çıkılamaz.");
        String rl = real.toLowerCase(Locale.ROOT);
        if (!(rl.equals("plugins") || rl.equals("config") || rl.startsWith("plugins/") || rl.startsWith("config/")))
            throw new SecurityException("Bu klasöre erişim yok: " + real);
        notOwn(rl);
        if (!f.isDirectory()) throw new IOException("Klasör değil: " + p);
        return f;
    }

    /** Kökten göreli yol ('/' ayraçlı) ya da kök dışındaysa null. */
    String relOf(File f) {
        Path rp = root.toPath(), fp = f.toPath();
        if (!fp.startsWith(rp) || fp.equals(rp)) return null;
        return rp.relativize(fp).toString().replace('\\', '/');
    }

    static String redact(String text, boolean log) {
        String t = SECRET.matcher(text).replaceAll("$1" + MARKER);
        if (log) {
            t = IPV4.matcher(t).replaceAll(m -> {
                String ip = m.group();
                return (ip.equals("127.0.0.1") || ip.equals("0.0.0.0")) ? ip : "<IP>";
            });
        }
        return t;
    }

    static boolean hasSecret(String text) { return !SECRET.matcher(text).replaceAll("$1" + MARKER).equals(text); }
}
