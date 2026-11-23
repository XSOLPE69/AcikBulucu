package tr.acikbulucuai;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.World;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class AcikBulucuAI extends JavaPlugin implements CommandExecutor, TabCompleter {

    private static final String P = "&8[&bAI&8] &7";
    private static final List<String> SUBS = Arrays.asList("onayla", "reddet", "bekleyen", "goster",
            "geriyukle", "modeller", "sifirla", "yenile", "yardim");

    static final class Pending {
        final long at = System.currentTimeMillis();
        final List<Action> acts;
        Pending(List<Action> acts) { this.acts = acts; }
    }

    private Ops ops;
    private Assistant assistant;
    private final Map<String, List<String[]>> history = new ConcurrentHashMap<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Long> lastUse = new ConcurrentHashMap<>();
    private final Set<String> busy = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!setup()) { getServer().getPluginManager().disablePlugin(this); return; }
        getCommand("ai").setExecutor(this);
        getCommand("ai").setTabCompleter(this);
        getLogger().info("AcikBulucuAI hazir. /ai yardim");
        if (apiKey().isEmpty()) getLogger().warning("Gemini anahtari yok: plugins/AcikBulucuAI/config.yml -> gemini.anahtar");
    }

    private boolean setup() {
        try {
            File root = getDataFolder().getAbsoluteFile().getParentFile().getParentFile();
            Sandbox sb = new Sandbox(root);
            ops = new Ops(sb, getDataFolder(), getConfig().getStringList("ai.yasakli-komutlar"));
            assistant = new Assistant(ops);
            return true;
        } catch (IOException e) {
            getLogger().severe("Sunucu klasoru cozulemedi: " + e.getMessage());
            return false;
        }
    }

    private String apiKey() {
        String k = getConfig().getString("gemini.anahtar", "");
        if (k == null || k.isBlank() || k.startsWith("BURAYA")) {
            String env = System.getenv("GEMINI_API_KEY");
            return env == null ? "" : env.trim();
        }
        return k.trim();
    }

    // ---------- komut ----------

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("acikbulucuai.kullan")) { say(s, "&cBu komut için yetkin yok."); return true; }
        if (a.length == 0 || a[0].equalsIgnoreCase("yardim")) { help(s); return true; }
        String k = key(s);
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "onayla": approve(s, k, a); return true;
            case "reddet":
                say(s, pending.remove(k) != null ? "&eBekleyen öneriler silindi." : "Bekleyen öneri yok.");
                return true;
            case "bekleyen": showPending(s, k, -1); return true;
            case "goster":
                int n = -1;
                try { n = a.length > 1 ? Integer.parseInt(a[1]) : -1; } catch (NumberFormatException ignored) { }
                showPending(s, k, n);
                return true;
            case "geriyukle": restore(s); return true;
            case "modeller": models(s); return true;
            case "sifirla": history.remove(k); say(s, "Sohbet geçmişi temizlendi."); return true;
            case "yenile":
                if (!s.hasPermission("acikbulucuai.onayla")) { say(s, "&cYetkin yok."); return true; }
                reloadConfig(); setup(); say(s, "&aAyarlar yenilendi.");
                return true;
            default:
                ask(s, k, String.join(" ", a).trim());
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length != 1) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String x : SUBS) if (x.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(x);
        return out;
    }

    private void help(CommandSender s) {
        say(s, "&b/ai <soru veya istek> &7- yapay zekaya sor/yaptır (öneri üretir, kendiliğinden uygulamaz)");
        say(s, "&b/ai bekleyen &7- onay bekleyen öneriler  &b/ai goster <no> &7- tam içerik");
        say(s, "&b/ai onayla [no ...] &7- önerileri uygula (yedek alınır)  &b/ai reddet &7- sil");
        say(s, "&b/ai geriyukle &7- son uygulanan değişikliği geri al");
        say(s, "&b/ai modeller &7- anahtarınla kullanılabilen modeller  &b/ai sifirla  /ai yenile");
    }

    // ---------- soru ----------

    private void ask(CommandSender s, String k, String q) {
        String key = apiKey();
        if (key.isEmpty()) { say(s, "&cGemini anahtarı yok. plugins/AcikBulucuAI/config.yml içinde gemini.anahtar alanına yapıştır, sonra /ai yenile."); return; }
        long now = System.currentTimeMillis();
        long wait = getConfig().getInt("ai.bekleme-sn", 3) * 1000L;
        Long prev = lastUse.get(k);
        if (prev != null && now - prev < wait) { say(s, "&eBiraz yavaş, birkaç saniye bekle."); return; }
        if (!busy.add(k)) { say(s, "&eÖnceki isteğin hâlâ sürüyor."); return; }
        lastUse.put(k, now);

        final String ctx = buildContext();
        final Assistant.Config cfg = new Assistant.Config();
        cfg.key = key;
        cfg.model = getConfig().getString("gemini.model", "gemini-3.5-flash-lite");
        cfg.timeoutSec = getConfig().getInt("gemini.zaman-asimi-sn", 60);
        cfg.maxRounds = Math.max(1, Math.min(8, getConfig().getInt("ai.max-tur", 4)));
        final List<String[]> hist = new ArrayList<>(history.getOrDefault(k, Collections.emptyList()));
        final Assistant asst = assistant;
        say(s, "&7Düşünüyorum... (" + cfg.model + ")");

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            Assistant.Result r = null;
            String err = null;
            try {
                r = asst.ask(cfg, ctx, q, hist);
            } catch (Gemini.ApiException e) {
                err = friendly(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                err = "İstek kesildi.";
            } catch (Exception e) {
                err = "Hata: " + e.getMessage();
            }
            final Assistant.Result fr = r;
            final String ferr = err;
            Bukkit.getScheduler().runTask(this, () -> {
                busy.remove(k);
                if (ferr != null) { say(s, "&c" + clean(ferr)); return; }
                present(s, k, q, fr);
            });
        });
    }

    private static String friendly(Gemini.ApiException e) {
        switch (e.status) {
            case 429: return "Ücretsiz kullanım sınırı doldu (dakikalık veya günlük). Biraz bekle ya da /ai modeller ile başka model seç. (" + e.getMessage() + ")";
            case 400: case 401: case 403: return "Anahtar geçersiz/izinsiz ya da istek reddedildi: " + e.getMessage();
            case 404: return "Model bulunamadı. /ai modeller ile listele, config.yml'deki gemini.model'i düzelt, /ai yenile.";
            case 500: case 503: return "Gemini şu an yoğun/hatalı (" + e.status + "), biraz sonra tekrar dene.";
            default: return "Gemini hatası " + e.status + ": " + e.getMessage();
        }
    }

    private void present(CommandSender s, String k, String q, Assistant.Result r) {
        if (!r.reply.isEmpty()) for (String line : clean(r.reply).split("\n")) say(s, "&f" + line);
        for (String n : r.notes) say(s, "&8" + clean(n));
        if (!r.proposals.isEmpty()) {
            pending.put(k, new Pending(r.proposals));
            say(s, "&6Onayını bekleyen " + r.proposals.size() + " öneri:");
            int i = 1;
            for (Action a : r.proposals) {
                List<String> d = ops.describe(a, false);
                for (int j = 0; j < d.size(); j++) say(s, (j == 0 ? "&e[" + i + "] " : "&7") + "&f" + clean(d.get(j)));
                i++;
            }
            say(s, "&aUygula: &b/ai onayla &7(tek tek: /ai onayla 1 3)  &cSil: &b/ai reddet  &7Tam içerik: &b/ai goster <no>");
        }
        List<String[]> h = history.computeIfAbsent(k, x -> new ArrayList<>());
        synchronized (h) {
            h.add(new String[]{"user", cut(q, 1500)});
            h.add(new String[]{"model", cut(r.reply.isEmpty() ? "(öneri sunuldu)" : r.reply, 1500)});
            while (h.size() > 6) h.remove(0);
        }
    }

    // ---------- onay / gösterim / geri alma ----------

    private Pending live(CommandSender s, String k) {
        Pending p = pending.get(k);
        if (p == null) { say(s, "Bekleyen öneri yok."); return null; }
        long ttl = getConfig().getInt("ai.onay-suresi-dk", 10) * 60_000L;
        if (System.currentTimeMillis() - p.at > ttl) {
            pending.remove(k);
            say(s, "&eÖnerilerin süresi doldu (dosyalar değişmiş olabilir). Tekrar iste.");
            return null;
        }
        return p;
    }

    private void showPending(CommandSender s, String k, int n) {
        Pending p = live(s, k);
        if (p == null) return;
        for (int i = 0; i < p.acts.size(); i++) {
            if (n > 0 && n != i + 1) continue;
            List<String> d = ops.describe(p.acts.get(i), n > 0);
            for (int j = 0; j < d.size(); j++) say(s, (j == 0 ? "&e[" + (i + 1) + "] " : "&7") + "&f" + clean(d.get(j)));
        }
    }

    private void approve(CommandSender s, String k, String[] a) {
        if (!s.hasPermission("acikbulucuai.onayla")) { say(s, "&cOnaylama yetkin yok."); return; }
        Pending p = live(s, k);
        if (p == null) return;
        List<Action> chosen = new ArrayList<>();
        if (a.length > 1) {
            for (int i = 1; i < a.length; i++) {
                try {
                    int n = Integer.parseInt(a[i]);
                    if (n >= 1 && n <= p.acts.size() && !chosen.contains(p.acts.get(n - 1))) chosen.add(p.acts.get(n - 1));
                } catch (NumberFormatException ignored) { }
            }
            if (chosen.isEmpty()) { say(s, "&cGeçerli öneri numarası yok."); return; }
        } else chosen.addAll(p.acts);
        pending.remove(k);
        List<String> out = ops.apply(chosen, s.getName(), this::dispatch,
                getConfig().getBoolean("ai.skript-otomatik-yenile", true), reloadCmd());
        for (String l : out) say(s, (l.startsWith("✗") ? "&c" : "&a") + clean(l));
    }

    private void restore(CommandSender s) {
        if (!s.hasPermission("acikbulucuai.onayla")) { say(s, "&cYetkin yok."); return; }
        for (String l : ops.restoreLatest(s.getName(), this::dispatch,
                getConfig().getBoolean("ai.skript-otomatik-yenile", true), reloadCmd()))
            say(s, (l.startsWith("✗") ? "&c" : "&a") + clean(l));
    }

    private String reloadCmd() { return getConfig().getString("ai.skript-yenileme-komutu", "sk reload {script}"); }

    private void dispatch(String command) {
        getLogger().info("Komut calistiriliyor: /" + command);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    private void models(CommandSender s) {
        String key = apiKey();
        if (key.isEmpty()) { say(s, "&cÖnce anahtarı gir."); return; }
        int timeout = getConfig().getInt("gemini.zaman-asimi-sn", 60);
        say(s, "&7Modeller soruluyor...");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            List<String> names = null;
            String err = null;
            try { names = Gemini.listModels(key, timeout); }
            catch (Gemini.ApiException e) { err = friendly(e); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); err = "Kesildi."; }
            catch (Exception e) { err = "Hata: " + e.getMessage(); }
            final List<String> fn = names;
            final String ferr = err;
            Bukkit.getScheduler().runTask(this, () -> {
                if (ferr != null) { say(s, "&c" + clean(ferr)); return; }
                List<String> shown = new ArrayList<>();
                for (String n : fn) if (n.startsWith("gemini") && !n.contains("embedding") && !n.contains("image") && !n.contains("tts")) shown.add(n);
                Collections.sort(shown);
                say(s, "&aKullanılabilir modeller (" + shown.size() + "): &f" + String.join(", ", shown.subList(0, Math.min(40, shown.size()))));
                say(s, "&7Ücretsiz sınırları AI Studio > Rate limits sayfasında bak. Seçince config.yml'de gemini.model yaz, /ai yenile.");
            });
        });
    }

    // ---------- yardımcılar ----------

    private String buildContext() {
        StringBuilder b = new StringBuilder();
        b.append("Sürüm: ").append(Bukkit.getVersion()).append(" | API: ").append(Bukkit.getBukkitVersion()).append('\n');
        b.append("Çevrimiçi: ").append(Bukkit.getOnlinePlayers().size()).append('\n');
        StringBuilder w = new StringBuilder();
        for (World x : Bukkit.getWorlds()) w.append(x.getName()).append(' ');
        b.append("Dünyalar: ").append(w.toString().trim()).append('\n');
        b.append("Eklentiler: ");
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            b.append(p.getName()).append(' ').append(p.getDescription().getVersion());
            if (!p.isEnabled()) b.append(" (KAPALI)");
            b.append(", ");
        }
        b.append('\n');
        File sd = new File(ops.sb.root, "plugins/Skript/scripts");
        File[] fs = sd.listFiles();
        if (fs != null) {
            Arrays.sort(fs);
            b.append("Skript dosyaları: ");
            int n = 0;
            for (File f : fs) {
                if (++n > 80) { b.append("..."); break; }
                b.append(f.getName()).append(f.isDirectory() ? "/" : "").append(", ");
            }
            b.append('\n');
        }
        return b.toString();
    }

    private static String key(CommandSender s) { return s instanceof Player ? ((Player) s).getUniqueId().toString() : "KONSOL"; }

    /** Yapay zekâ/dosya kaynaklı metinden renk kodu enjeksiyonunu temizler. */
    private static String clean(String t) { return t.replace("§", "").replace('&', '\uE000'); }

    private static String cut(String t, int n) { return t.length() > n ? t.substring(0, n) : t; }

    private void say(CommandSender s, String msg) {
        s.sendMessage(ChatColor.translateAlternateColorCodes('&', P + msg).replace('\uE000', '&'));
    }
}
