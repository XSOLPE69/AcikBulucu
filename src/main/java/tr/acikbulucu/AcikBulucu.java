package tr.acikbulucu;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AcikBulucu v2
 * /aciklaribul          -> tarar ve raporlar
 * /aciklaribul onizle   -> otomatik onarilacaklari listeler
 * /aciklaribul onar     -> guvenli olanlari yedek alarak onarir
 * /aciklaribul geriyukle-> son onarimi geri alir
 */
public class AcikBulucu extends JavaPlugin {

    private enum Level {
        KRITIK("&c✖ KRİTİK"),
        UYARI("&e⚠ UYARI"),
        ONERI("&b● ÖNERİ");

        final String label;

        Level(String label) {
            this.label = label;
        }
    }

    private interface Fixer {
        void apply(Tx tx) throws Exception;
    }

    private static final class Finding {
        final Level level;
        final String title;
        final String advice;
        final String fixDesc;
        final Fixer fixer;

        Finding(Level level, String title, String advice, String fixDesc, Fixer fixer) {
            this.level = level;
            this.title = title;
            this.advice = advice;
            this.fixDesc = fixDesc;
            this.fixer = fixer;
        }
    }

    /** Yapilan her degisikligi kaydeder, boylece geri alinabilir. */
    private final class Tx {
        final Path dir;
        final List<String> log = new ArrayList<>();
        int counter = 0;

        Tx() throws IOException {
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            dir = new File(getDataFolder(), "yedek/" + stamp).toPath();
            Files.createDirectories(dir);
        }

        void backup(File f) throws IOException {
            if (!f.exists()) {
                return;
            }
            String name = (counter++) + ".bak";
            Files.copy(f.toPath(), dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            log.add("RESTORE|" + f.getAbsolutePath() + "|" + name);
        }

        void created(File f) {
            log.add("DELETE|" + f.getAbsolutePath());
        }

        void spawn(World w) {
            Location s = w.getSpawnLocation();
            log.add("SPAWN|" + w.getName() + "|" + s.getBlockX() + "|" + s.getBlockY() + "|" + s.getBlockZ());
        }

        void moved(File from, File to) throws IOException {
            Files.move(from.toPath(), to.toPath());
            log.add("MOVE|" + to.getAbsolutePath() + "|" + from.getAbsolutePath());
        }

        void save() throws IOException {
            Files.write(dir.resolve("manifest.txt"), log, StandardCharsets.UTF_8);
        }
    }

    private static final Pattern COPY = Pattern.compile("^(.*?)\\s*\\((\\d+)\\)\\.sk$", Pattern.CASE_INSENSITIVE);
    private static final String RULE_WS = "sondaki boşluklar";

    private List<Finding> findings = new ArrayList<>();
    private boolean skriptDirty = false;
    private int lastCritical = -1;

    // ------------------------------------------------------------------
    // Yasam dongusu
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getConfig().getBoolean("otomatik-tarama.aktif", true)) {
            long ticks = 20L * 60L * Math.max(1, getConfig().getInt("otomatik-tarama.dakika", 30));
            Bukkit.getScheduler().runTaskTimer(this, this::scheduledRun, ticks, ticks);
        }
        getLogger().info("AcikBulucu hazir. Kullanim: /aciklaribul [onizle|onar|geriyukle]");
    }

    private void scheduledRun() {
        scan();
        if (getConfig().getBoolean("otomatik-tarama.otomatik-onar", false) && hasFixable()) {
            getLogger().info("Otomatik onarim calisiyor...");
            runRepair(Bukkit.getConsoleSender(), fixableList());
        }
        int critical = count(Level.KRITIK);
        if (critical > 0 && critical != lastCritical) {
            String m = "&6[AçıkBulucu] &c" + critical + " kritik açık var. &7Detay: &e/aciklaribul";
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("acikbulucu.use")) {
                    msg(p, m);
                }
            }
            msg(Bukkit.getConsoleSender(), m);
        }
        lastCritical = critical;
    }

    // ------------------------------------------------------------------
    // Komutlar
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("aciklaribul")) {
            return false;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";

        if (sub.isEmpty()) {
            scan();
            report(sender);
            return true;
        }
        if (sub.equals("onizle")) {
            scan();
            preview(sender);
            return true;
        }
        if (sub.equals("onar")) {
            if (!sender.hasPermission("acikbulucu.onar")) {
                msg(sender, "&cBu komut için yetkin yok.");
                return true;
            }
            scan();
            List<Finding> ord = ordered();
            List<Finding> pick = new ArrayList<>();
            if (args.length < 2) {
                preview(sender);
                return true;
            }
            if (args[1].equalsIgnoreCase("hepsi")) {
                pick = fixableList();
            } else {
                for (int i = 1; i < args.length; i++) {
                    int no;
                    try {
                        no = Integer.parseInt(args[i]);
                    } catch (NumberFormatException e) {
                        msg(sender, "&c'" + args[i] + "' geçerli bir numara değil.");
                        return true;
                    }
                    if (no < 1 || no > ord.size() || ord.get(no - 1).fixer == null) {
                        msg(sender, "&c" + no + " numaralı madde otomatik onarılabilir değil.");
                        return true;
                    }
                    pick.add(ord.get(no - 1));
                }
            }
            runRepair(sender, pick);
            return true;
        }
        if (sub.equals("geriyukle")) {
            if (!sender.hasPermission("acikbulucu.onar")) {
                msg(sender, "&cBu komut için yetkin yok.");
                return true;
            }
            restoreLatest(sender);
            return true;
        }
        msg(sender, "&7Kullanım: &e/" + label + " &7[onizle|onar|geriyukle]");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : Arrays.asList("onizle", "onar", "geriyukle")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
            return out;
        }
        return new ArrayList<>();
    }

    private void scan() {
        findings = new ArrayList<>();
        guard("Sunucu yazılımı", this::checkSoftware);
        guard("server.properties", this::checkProperties);
        guard("Giriş güvenliği", this::checkAuth);
        guard("Eklentiler", this::checkPlugins);
        guard("Skript dosyaları", this::checkSkriptScripts);
        guard("Skript hataları", this::checkSkriptErrors);
        guard("Claim script'i", this::checkClaimScript);
        guard("Dünyalar", this::checkWorlds);
        guard("Yetkililer", this::checkOps);
        guard("Performans", this::checkPerformance);
    }

    // ------------------------------------------------------------------
    // Kontroller
    // ------------------------------------------------------------------

    private void checkSoftware() {
        String sw = (Bukkit.getName() + " " + Bukkit.getVersion()).toLowerCase(Locale.ROOT);
        boolean fork = sw.contains("paper") || sw.contains("purpur") || sw.contains("pufferfish")
                || sw.contains("folia") || sw.contains("leaf");
        if (!fork) {
            add(Level.UYARI, "Sunucu yazılımı Paper tabanlı değil (" + Bukkit.getName() + ")",
                    "Paper veya Purpur'a geç. Daha hızlıdır, daha az lag yapar. "
                            + "Host panelinden sunucu jar'ını değiştirmen yeterli.");
        }
    }

    private void checkProperties() {
        Properties p = props();
        if (p.isEmpty()) {
            return;
        }
        if ("true".equalsIgnoreCase(p.getProperty("enable-rcon", "false"))) {
            String pw = p.getProperty("rcon.password", "").trim();
            if (pw.isEmpty()) {
                addFix(Level.KRITIK, "RCON açık ve şifresi boş",
                        "Herkes konsola erişebilir. RCON'u kullanıyorsan güçlü bir rcon.password belirle.",
                        "server.properties: enable-rcon=false yapılacak (yeniden başlatınca geçerli olur)",
                        tx -> setProp(tx, "enable-rcon", "false"));
            } else if (pw.length() < 12) {
                add(Level.UYARI, "RCON şifresi zayıf (12 karakterden kısa)",
                        "rcon.password değerini uzun ve rastgele bir şifreyle değiştir. Kullanmıyorsan RCON'u kapat.");
            }
        }
        int view = intProp(p, "view-distance", 10);
        if (view > 10) {
            addFix(Level.UYARI, "view-distance çok yüksek (" + view + ")",
                    "6-8 arası önerilir. Chunk yükü karesiyle artar, lag'ın en büyük sebebidir.",
                    "server.properties: view-distance=8 yapılacak (yeniden başlatınca geçerli olur)",
                    tx -> setProp(tx, "view-distance", "8"));
        }
        int sim = intProp(p, "simulation-distance", 10);
        if (sim > 8) {
            add(Level.ONERI, "simulation-distance yüksek (" + sim + ")",
                    "4-6 yaparsan mob ve makine yükü ciddi azalır.");
        }
        if ("true".equalsIgnoreCase(p.getProperty("enable-command-block", "false"))) {
            add(Level.ONERI, "Komut blokları açık",
                    "Kullanmıyorsan server.properties içinde enable-command-block=false yap.");
        }
    }

    private void checkAuth() {
        boolean online = Bukkit.getOnlineMode();
        if (!online && !isProxy()) {
            boolean auth = hasAny("AuthMe", "AuthMeReloaded", "nLogin", "FastLogin", "LoginSecurity",
                    "OpeNLogin", "LibreLogin", "UserLogin");
            if (!auth) {
                add(Level.KRITIK, "online-mode kapalı ve giriş (şifre) eklentisi yok",
                        "Herkes başkasının adıyla girip op/rütbe hesabını ele geçirebilir. AuthMe veya nLogin kur, "
                                + "ya da host destekliyorsa online-mode=true yap.");
            }
        }
        if (online && has("Geyser-Spigot") && !has("floodgate")) {
            add(Level.UYARI, "Geyser var ama Floodgate yok",
                    "online-mode açıkken Bedrock oyuncular Floodgate olmadan giriş yapamaz. Floodgate'i kur.");
        }
    }

    private void checkPlugins() {
        Plugin[] all = Bukkit.getPluginManager().getPlugins();

        List<String> disabled = new ArrayList<>();
        List<String> legacy = new ArrayList<>();
        for (Plugin pl : all) {
            if (!pl.isEnabled()) {
                disabled.add(pl.getName());
            }
            if (pl.getDescription().getAPIVersion() == null) {
                legacy.add(pl.getName());
            }
        }
        if (!disabled.isEmpty()) {
            String why = logReasons(disabled);
            add(Level.KRITIK, "Yüklü ama kapalı eklentiler: " + String.join(", ", disabled),
                    "Açılırken hata vermiş (genelde eksik bağımlılık ya da sürüm uyuşmazlığı)."
                            + (why.isEmpty() ? " logs/latest.log içinde eklentinin adını ara."
                            : " Log'dan nedenler: " + why));
        }
        if (!legacy.isEmpty()) {
            add(Level.ONERI, "Eski (api-version belirtmeyen) eklentiler: " + String.join(", ", limit(legacy, 8)),
                    "Eski sürüm için yazılmış olabilir. Güncel sürümlerini indir ya da alternatif bul.");
        }

        Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
        if (vault == null || !vault.isEnabled()) {
            add(Level.UYARI, "Vault yok",
                    "Vault'u kur. Ekonomi, izin ve market eklentilerini birbirine bağlar. Skript'teki "
                            + "'balance' işlemleri (claim script'i gibi) Vault olmadan çalışmaz.");
        } else if (!hasProvider(vault, "net.milkbowl.vault.economy.Economy")) {
            add(Level.KRITIK, "Vault var ama ekonomi sağlayıcı yok",
                    "Para sistemi bağlı değil. Essentials'ı (ekonomi açık) veya başka bir ekonomi eklentisini kur, "
                            + "sonra sunucuyu yeniden başlat.");
        }

        if (!hasAny("LuckPerms", "PermissionsEx", "GroupManager", "UltraPermissions", "zPermissions")) {
            add(Level.KRITIK, "İzin/rütbe eklentisi yok",
                    "LuckPerms kur. Rütbeleri ve yetkileri güvenli yönetmenin standart yoludur.");
        }

        if (!has("PlaceholderAPI")) {
            List<String> needs = new ArrayList<>();
            for (String n : new String[]{"DeluxeMenus", "DecentHolograms", "FancyNpcs", "FancyHolograms", "TAB"}) {
                if (has(n)) {
                    needs.add(n);
                }
            }
            if (!needs.isEmpty()) {
                add(Level.UYARI, "PlaceholderAPI yok ama kullanan eklentiler var: " + String.join(", ", needs),
                        "PlaceholderAPI'yi kur. Aksi halde menülerde ve NPC'lerde %player_name% gibi yazılar çözülmez.");
            }
        }

        if (nameContains("grim", "vulcan", "nocheatplus", "matrix", "spartan", "themis", "verus", "intave",
                "aac", "anticheat") == null) {
            add(Level.UYARI, "Çalışan bir anti-cheat yok",
                    "GrimAC (ücretsiz) veya benzeri bir anti-cheat kur. Yüklü ama kapalı bir anti-cheat "
                            + "varsa (örneğin GrimACSelector) önce onu çalıştır.");
        }
        if (nameContains("backup") == null) {
            add(Level.ONERI, "Yedekleme eklentisi görünmüyor",
                    "Host panelinden otomatik yedek aldığından emin ol ya da bir yedek eklentisi kur.");
        }
        if (!hasAny("GriefPrevention", "WorldGuard", "Lands", "Towny", "Residence")) {
            add(Level.UYARI, "Arazi koruma eklentisi yok",
                    "GriefPrevention veya WorldGuard kur. Oyuncuların yapıları korumasız kalır.");
        }

        conflict(Level.KRITIK, "Birden fazla skyblock eklentisi aynı anda açık",
                "Sadece birini bırak, diğerlerini plugins klasöründen sil. Bunu otomatik yapmıyorum, "
                        + "çünkü ada verisi silinebilir. Aksi halde /is komutu ve adalar karışır.",
                "BentoBox", "IridiumSkyblock", "SuperiorSkyblock2", "ASkyBlock", "uSkyBlock", "FabledSkyBlock");
        conflict(Level.KRITIK, "Birden fazla izin eklentisi aynı anda açık",
                "Yalnızca LuckPerms kullan, diğerlerini kaldır.",
                "LuckPerms", "PermissionsEx", "GroupManager", "UltraPermissions", "zPermissions");
        conflict(Level.UYARI, "Birden fazla scoreboard/tab eklentisi aynı anda açık",
                "Tek sistem seç. Aynı anda çalışırlarsa yan panel titrer veya silinir.",
                "TAB", "FeatherBoard", "AnimatedScoreboard", "ScoreboardRevision", "SimpleScore");
        conflict(Level.UYARI, "Essentials ve CMI birlikte açık",
                "İkisi de aynı komutları (home, tpa, spawn...) tanımlar. Birini kaldır.",
                "Essentials", "CMI");
    }

    private void checkSkriptScripts() {
        if (!scriptsDir().isDirectory()) {
            return;
        }
        List<File> enabled = enabledScripts();

        List<String> copies = new ArrayList<>();
        List<String> scoreboards = new ArrayList<>();
        boolean rankScript = false;
        List<String> broken = new ArrayList<>();
        Set<String> brokenRules = new LinkedHashSet<>();

        for (File f : enabled) {
            String n = f.getName();
            String low = n.toLowerCase(Locale.ROOT);
            if (COPY.matcher(n).matches()) {
                copies.add(n);
            }
            if (low.contains("scoreboard")) {
                scoreboards.add(n);
            }
            if (low.contains("rutbe") || low.contains("rank")) {
                rankScript = true;
            }
            try {
                String orig = readLatin(f);
                List<String> rules = new ArrayList<>();
                String fixed = repairScript(orig, rules);
                boolean serious = false;
                for (String r : rules) {
                    if (!r.equals(RULE_WS)) {
                        serious = true;
                    }
                }
                if (!fixed.equals(orig) && serious) {
                    broken.add(n);
                    brokenRules.addAll(rules);
                }
            } catch (IOException ignored) {
                // okunamayan dosyayi atla
            }
        }

        if (!broken.isEmpty()) {
            addFix(Level.UYARI,
                    "Skript dosyalarında düzeltilebilir hatalar: " + String.join(", ", limit(broken, 8)),
                    "Bilinen kalıplar bulundu: " + String.join(", ", brokenRules) + ". Skript bunları anlamaz.",
                    "Script'ler yedeklenip düzeltilecek (%player_name% -> %player%, girinti karışıklığı, BOM) "
                            + "ve Skript yeniden yüklenecek",
                    this::fixSyntax);
        }
        if (scoreboards.size() >= 2) {
            addFix(Level.UYARI, "Birden fazla aktif scoreboard script'i: " + String.join(", ", scoreboards),
                    "Aynı anda çalışırlarsa birbirini ezer.",
                    "En yeni scoreboard script'i kalacak, diğerlerinin adına '-' konup kapatılacak",
                    this::fixScoreboards);
        }
        if (!copies.isEmpty()) {
            addFix(Level.ONERI, "Dosya adında (sayı) olan script'ler: " + String.join(", ", limit(copies, 8)),
                    "Tarayıcıdan tekrar indirilmiş kopya olabilir. Eski sürümler de aktifse çift çalışır.",
                    "Kopyalar sadeleştirilecek: en yeni dosya kalır ve sade adı alır, eskileri '-' ile kapanır",
                    this::fixCopies);
        }
        if (rankScript && has("LuckPerms")) {
            add(Level.ONERI, "Rütbe hem script'te hem LuckPerms'te yönetiliyor olabilir",
                    "Rütbeleri tek yerde tut (tercihen LuckPerms). İki sistem birbirini sıfırlayıp rütbe kaybettirebilir.");
        }
    }

    /** logs/latest.log icindeki Skript hatalarini dosya ve satir bazinda gruplar. */
    private void checkSkriptErrors() {
        String text = logTail();
        if (text == null || !has("Skript")) {
            return;
        }
        Map<String, TreeSet<Integer>> errs = new TreeMap<>();
        Matcher m1 = Pattern.compile("\\(([^,()]+\\.sk), line (\\d+)").matcher(text);
        while (m1.find()) {
            errs.computeIfAbsent(m1.group(1).trim(), k -> new TreeSet<>()).add(Integer.parseInt(m1.group(2)));
        }
        Matcher m2 = Pattern.compile("Line (\\d+): \\(([^()]+\\.sk)\\)").matcher(text);
        while (m2.find()) {
            errs.computeIfAbsent(m2.group(2).trim(), k -> new TreeSet<>()).add(Integer.parseInt(m2.group(1)));
        }
        if (errs.isEmpty()) {
            return;
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, TreeSet<Integer>> e : errs.entrySet()) {
            List<String> lines = new ArrayList<>();
            for (Integer i : e.getValue()) {
                lines.add(String.valueOf(i));
            }
            parts.add(e.getKey() + " (satır " + String.join(", ", limit(lines, 6)) + ")");
        }
        List<String> shown = new ArrayList<>();
        for (Map.Entry<String, TreeSet<Integer>> e : errs.entrySet()) {
            List<String> fl = readLines(new File(scriptsDir(), e.getKey()));
            int c = 0;
            for (Integer i : e.getValue()) {
                if (c >= 4 || i < 1 || i > fl.size()) {
                    continue;
                }
                String t = fl.get(i - 1).trim();
                shown.add(e.getKey() + ":" + i + " -> " + (t.length() > 90 ? t.substring(0, 90) + "..." : t));
                c++;
            }
        }
        add(Level.UYARI, "Son açılıştan beri Skript hata veren dosyalar: " + String.join("; ", limit(parts, 5)),
                (shown.isEmpty() ? "" : "Hatalı satırlar: " + String.join(" | ", limit(shown, 8)) + ". ")
                        + "Bilinen kalıplar 'onar' ile düzelir, diğerleri için o satırı elle düzelt. "
                        + "Sonra /sk reload scripts yaz.");
    }

    /** Claim satin alma script'i yoksa uretir. */
    private void checkClaimScript() {
        if (!scriptsDir().isDirectory() || !has("Skript") || !has("GriefPrevention")) {
            return;
        }
        Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
        if (vault == null || !vault.isEnabled() || !hasProvider(vault, "net.milkbowl.vault.economy.Economy")) {
            return;
        }
        for (File f : enabledScripts()) {
            try {
                if (readLatin(f).contains("command /claimal")) {
                    return;
                }
            } catch (IOException ignored) {
                // atla
            }
        }
        addFix(Level.ONERI, "Claim satın alma script'i yok",
                "GriefPrevention config.yml içinde InitialBlocks=0 ve saatlik blok kazancını 0 yapmayı unutma, "
                        + "yoksa herkes parasız claim yapar. Sonra /gpreload yaz.",
                "claim.sk oluşturulacak (/claimal ve /claimgenislet, fiyatlar config.yml'den okunur)",
                this::createClaimScript);
    }

    private void checkWorlds() {
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() != World.Environment.NORMAL || skipWorld(w)) {
                continue;
            }
            Location s = w.getSpawnLocation();
            Material below = w.getBlockAt(s.getBlockX(), s.getBlockY() - 1, s.getBlockZ()).getType();
            String name = w.getName();
            if (below == Material.LAVA) {
                addFix(Level.KRITIK, "'" + name + "' dünyasının spawn noktası lavın üstünde",
                        "Güvenli bir bloğun üstünde durup /mv setspawn yaz.",
                        "'" + name + "' için yakında güvenli bir blok aranıp spawn oraya taşınacak",
                        tx -> fixSpawn(tx, w));
            } else if (below.isAir()) {
                addFix(Level.UYARI, "'" + name + "' dünyasının spawn noktası havada/boşlukta",
                        "Multiverse 'konum güvensiz' hatasının sebebi budur. Sağlam bir bloğun üstünde "
                                + "/mv setspawn yaz. Multiverse kendi spawn'ını ayrı tutar, otomatik düzeltme yetmezse bunu yap.",
                        "'" + name + "' için yakında güvenli bir blok aranıp spawn oraya taşınacak",
                        tx -> fixSpawn(tx, w));
            }
        }
    }

    private void checkOps() {
        Set<OfflinePlayer> ops = Bukkit.getOperators();
        if (ops.size() > 3) {
            List<String> names = new ArrayList<>();
            for (OfflinePlayer op : ops) {
                names.add(op.getName() == null ? "?" : op.getName());
            }
            add(Level.UYARI, "Çok fazla op var (" + ops.size() + "): " + String.join(", ", limit(names, 10)),
                    "Op yerine LuckPerms ile sadece gereken yetkileri ver. Bunu otomatik yapmıyorum, "
                            + "yanlış kişinin yetkisini almak istemem. Gerekmeyenlere /deop yaz.");
        }
    }

    private void checkPerformance() {
        Runtime rt = Runtime.getRuntime();
        long maxMb = rt.maxMemory() / (1024 * 1024);
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        if (maxMb > 0 && maxMb < 1024) {
            add(Level.UYARI, "RAM düşük (" + maxMb + " MB)",
                    "Skyblock + claim + menüler için en az 2 GB önerilir.");
        } else if (maxMb > 0 && usedMb * 100 / maxMb > 85) {
            add(Level.UYARI, "RAM neredeyse dolu (%" + (usedMb * 100 / maxMb) + ")",
                    "Gereksiz eklentileri kaldır, view-distance'ı düşür, gerekirse hosttan RAM artır.");
        }
        try {
            Object server = Bukkit.getServer();
            double[] tps = (double[]) server.getClass().getMethod("getTPS").invoke(server);
            if (tps != null && tps.length > 0 && tps[0] < 18.0) {
                add(Level.UYARI, "TPS düşük (" + String.format(Locale.ROOT, "%.1f", tps[0]) + "/20)",
                        "Sunucu yavaşlıyor. spark eklentisiyle hangi eklentinin yük bindirdiğine bak.");
            }
        } catch (Throwable ignored) {
            // Paper degilse getTPS yok
        }
    }

    // ------------------------------------------------------------------
    // Onarimlar
    // ------------------------------------------------------------------

    private void runRepair(CommandSender sender, List<Finding> fixable) {
        if (fixable.isEmpty()) {
            msg(sender, "&aOtomatik onarılabilir bir şey bulunamadı.");
            return;
        }
        Tx tx;
        try {
            tx = new Tx();
        } catch (IOException e) {
            msg(sender, "&cYedek klasörü oluşturulamadı, hiçbir şey değiştirilmedi: " + e.getMessage());
            return;
        }
        skriptDirty = false;
        msg(sender, "&8&m-----&r &6&lOtomatik Onarım &8&m-----");
        int ok = 0;
        for (Finding f : fixable) {
            try {
                f.fixer.apply(tx);
                msg(sender, "&a✔ &7" + f.fixDesc);
                ok++;
            } catch (Throwable t) {
                msg(sender, "&c✖ &7" + f.fixDesc + " &8(" + t.getMessage() + ")");
            }
        }
        try {
            tx.save();
        } catch (IOException e) {
            msg(sender, "&cYedek kaydı yazılamadı: " + e.getMessage());
        }
        if (skriptDirty) {
            reloadSkript();
            msg(sender, "&7Skript yeniden yükleniyor. Hata varsa konsolda görünür.");
        }
        msg(sender, "&a" + ok + "/" + fixable.size() + " onarım uygulandı. &7Geri almak için: &e/aciklaribul geriyukle");
        scan();
        msg(sender, "&7Kalan: &c" + count(Level.KRITIK) + " kritik &8| &e" + count(Level.UYARI) + " uyarı &8| &b"
                + count(Level.ONERI) + " öneri &7(kalanların çoğu elle yapılması gereken işlerdir)");
    }

    private void preview(CommandSender sender) {
        msg(sender, "&8&m-----&r &6&lOnarım Önizleme &8&m-----");
        boolean any = false;
        List<Finding> ord = ordered();
        for (int i = 0; i < ord.size(); i++) {
            if (ord.get(i).fixer != null) {
                msg(sender, "&e" + (i + 1) + ". &7" + ord.get(i).fixDesc);
                any = true;
            }
        }
        if (!any) {
            msg(sender, "&aOtomatik onarılacak bir şey yok.");
        } else {
            msg(sender, "&7Seçerek: &e/aciklaribul onar 4 8 &8| &7Hepsi: &e/aciklaribul onar hepsi &7(önce yedek alınır).");
        }
    }

    private void restoreLatest(CommandSender sender) {
        File root = new File(getDataFolder(), "yedek");
        File[] dirs = root.listFiles(File::isDirectory);
        File latest = null;
        if (dirs != null) {
            for (File d : dirs) {
                if (d.getName().contains(".") || !new File(d, "manifest.txt").isFile()) {
                    continue;
                }
                if (latest == null || d.getName().compareTo(latest.getName()) > 0) {
                    latest = d;
                }
            }
        }
        if (latest == null) {
            msg(sender, "&cGeri alınacak yedek bulunamadı.");
            return;
        }
        try {
            Path dir = latest.toPath();
            List<String> lines = Files.readAllLines(dir.resolve("manifest.txt"), StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0; i--) {
                String[] p = lines.get(i).split("\\|", 3);
                if (p.length < 2) {
                    continue;
                }
                switch (p[0]) {
                    case "RESTORE":
                        Files.copy(dir.resolve(p[2]), Paths.get(p[1]), StandardCopyOption.REPLACE_EXISTING);
                        break;
                    case "DELETE":
                        Files.deleteIfExists(Paths.get(p[1]));
                        break;
                    case "MOVE":
                        if (Files.exists(Paths.get(p[1]))) {
                            Files.move(Paths.get(p[1]), Paths.get(p[2]), StandardCopyOption.REPLACE_EXISTING);
                        }
                        break;
                    case "SPAWN":
                        World sw = Bukkit.getWorld(p[1]);
                        String[] c = p[2].split("\\|");
                        if (sw != null && c.length == 3) {
                            sw.setSpawnLocation(Integer.parseInt(c[0]), Integer.parseInt(c[1]),
                                    Integer.parseInt(c[2]));
                        }
                        break;
                    default:
                        break;
                }
            }
            Files.move(dir, dir.resolveSibling(latest.getName() + ".geri-alindi"));
            reloadSkript();
            msg(sender, "&a✔ Son onarım geri alındı (" + latest.getName() + "). Skript yeniden yükleniyor.");
            msg(sender, "&7server.properties değişikliklerinin etkisi için sunucuyu yeniden başlat.");
        } catch (Exception e) {
            msg(sender, "&cGeri alma sırasında hata: " + e.getMessage());
        }
    }

    private void fixCopies(Tx tx) throws IOException {
        Map<String, List<File>> groups = new TreeMap<>();
        Set<String> hasCopy = new LinkedHashSet<>();
        for (File f : enabledScripts()) {
            Matcher m = COPY.matcher(f.getName());
            String base;
            if (m.matches()) {
                base = m.group(1).trim();
                hasCopy.add(base);
            } else {
                base = f.getName().substring(0, f.getName().length() - 3);
            }
            groups.computeIfAbsent(base, k -> new ArrayList<>()).add(f);
        }
        for (String base : hasCopy) {
            List<File> g = groups.get(base);
            File newest = g.get(0);
            for (File f : g) {
                if (f.lastModified() > newest.lastModified()) {
                    newest = f;
                }
            }
            for (File f : g) {
                if (f != newest) {
                    disable(tx, f);
                }
            }
            File target = new File(scriptsDir(), base + ".sk");
            if (!newest.equals(target) && !target.exists()) {
                tx.moved(newest, target);
            }
        }
        skriptDirty = true;
    }

    private void fixScoreboards(Tx tx) throws IOException {
        List<File> sb = new ArrayList<>();
        for (File f : enabledScripts()) {
            if (f.getName().toLowerCase(Locale.ROOT).contains("scoreboard")) {
                sb.add(f);
            }
        }
        if (sb.size() < 2) {
            return;
        }
        File newest = sb.get(0);
        for (File f : sb) {
            if (f.lastModified() > newest.lastModified()) {
                newest = f;
            }
        }
        for (File f : sb) {
            if (f != newest) {
                disable(tx, f);
            }
        }
        skriptDirty = true;
    }

    private void disable(Tx tx, File f) throws IOException {
        File target = new File(f.getParentFile(), "-" + f.getName());
        int i = 2;
        while (target.exists()) {
            target = new File(f.getParentFile(), "-" + i + "-" + f.getName());
            i++;
        }
        tx.moved(f, target);
    }

    private void fixSyntax(Tx tx) throws IOException {
        for (File f : enabledScripts()) {
            String orig = readLatin(f);
            String fixed = repairScript(orig, new ArrayList<>());
            if (!fixed.equals(orig)) {
                tx.backup(f);
                Files.write(f.toPath(), fixed.getBytes(StandardCharsets.ISO_8859_1));
            }
        }
        skriptDirty = true;
    }

    /**
     * Bilinen Skript hatalarini duzeltir. Dosya ISO-8859-1 olarak okunup yazildigi icin
     * Turkce karakterler bayt bayt korunur; yalnizca ASCII kaliplara dokunulur.
     */
    private String repairScript(String text, List<String> rules) {
        String t = text;
        if (t.startsWith("\u00EF\u00BB\u00BF")) {
            t = t.substring(3);
            rules.add("BOM işareti");
        }
        boolean crlf = t.contains("\r\n");
        String[] lines = t.split("\r?\n", -1);

        boolean tabs = false;
        boolean spaces = false;
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }
            if (line.startsWith("\t")) {
                tabs = true;
            } else if (line.startsWith(" ")) {
                spaces = true;
            }
        }
        boolean mixed = tabs && spaces;
        boolean wsChanged = false;
        boolean nameFixed = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (mixed) {
                int k = 0;
                while (k < line.length() && line.charAt(k) == '\t') {
                    k++;
                }
                if (k > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int j = 0; j < k; j++) {
                        sb.append("    ");
                    }
                    line = sb.append(line.substring(k)).toString();
                }
            }
            String stripped = line.replaceAll("[ \\t]+$", "");
            if (!stripped.equals(line)) {
                wsChanged = true;
                line = stripped;
            }
            if (line.contains("%player_name%") || line.contains("%player_uuid%")) {
                line = line.replace("%player_name%", "%player%").replace("%player_uuid%", "%uuid of player%");
                nameFixed = true;
            }
            lines[i] = line;
        }
        if (mixed) {
            rules.add("karışık tab/boşluk girintisi");
        }
        if (nameFixed) {
            rules.add("%player_name% -> %player%");
        }
        if (wsChanged) {
            rules.add(RULE_WS);
        }
        return String.join(crlf ? "\r\n" : "\n", lines);
    }

    private void createClaimScript(Tx tx) throws IOException {
        File f = new File(scriptsDir(), "claim.sk");
        if (f.exists()) {
            f = new File(scriptsDir(), "claim-otomatik.sk");
        }
        if (f.exists()) {
            throw new IOException("claim dosyası zaten var");
        }
        int ilkFiyat = getConfig().getInt("claim-sablonu.ilk-fiyat", 5000);
        int ilkBlok = getConfig().getInt("claim-sablonu.ilk-blok", 1000);
        int genFiyat = getConfig().getInt("claim-sablonu.genisletme-fiyat", 1000);
        int genBlok = getConfig().getInt("claim-sablonu.genisletme-blok", 250);

        String[] lines = {
                "# claim.sk - AcikBulucu tarafindan otomatik olusturuldu",
                "# Gereken: Skript, Vault + ekonomi (Essentials), GriefPrevention",
                "",
                "options:",
                "    ilk_fiyat: " + ilkFiyat,
                "    ilk_blok: " + ilkBlok,
                "    genisletme_fiyat: " + genFiyat,
                "    genisletme_blok: " + genBlok,
                "    prefix: &8[&6Claim&8]&7",
                "",
                "command /claimal:",
                "    description: Ilk claim hakkini satin al",
                "    trigger:",
                "        if {claim::acik::%uuid of player%} is true:",
                "            send \"{@prefix} &cZaten claim hakkin var. Buyutmek icin &e/claimgenislet\"",
                "            stop",
                "        if player's balance < {@ilk_fiyat}:",
                "            send \"{@prefix} &cYetersiz bakiye! Gerekli: &e{@ilk_fiyat}\"",
                "            stop",
                "        remove {@ilk_fiyat} from player's balance",
                "        set {claim::acik::%uuid of player%} to true",
                "        execute console command \"acb %player% {@ilk_blok}\"",
                "        send \"{@prefix} &aClaim hakkin acildi! &e{@ilk_blok} &ablok kazandin.\"",
                "",
                "command /claimgenislet:",
                "    description: Claim blogunu arttir",
                "    trigger:",
                "        if {claim::acik::%uuid of player%} is not true:",
                "            send \"{@prefix} &cOnce &e/claimal &cile claim hakki almalisin.\"",
                "            stop",
                "        if player's balance < {@genisletme_fiyat}:",
                "            send \"{@prefix} &cYetersiz bakiye! Gerekli: &e{@genisletme_fiyat}\"",
                "            stop",
                "        remove {@genisletme_fiyat} from player's balance",
                "        execute console command \"acb %player% {@genisletme_blok}\"",
                "        send \"{@prefix} &a+{@genisletme_blok} claim blogu eklendi!\"",
                ""
        };
        Files.write(f.toPath(), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        tx.created(f);
        skriptDirty = true;
    }

    private void fixSpawn(Tx tx, World w) {
        Location s = w.getSpawnLocation();
        int cx = s.getBlockX();
        int cz = s.getBlockZ();
        for (int r = 0; r <= 48; r += 2) {
            for (int dx = -r; dx <= r; dx += 2) {
                for (int dz = -r; dz <= r; dz += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    Block b = w.getHighestBlockAt(cx + dx, cz + dz);
                    if (isSafeGround(b)) {
                        tx.spawn(w);
                        w.setSpawnLocation(b.getX(), b.getY() + 1, b.getZ());
                        return;
                    }
                }
            }
        }
        throw new IllegalStateException("yakında güvenli blok yok, dünyada /mv setspawn yap");
    }

    private boolean isSafeGround(Block b) {
        Material m = b.getType();
        return m.isSolid() && m != Material.LAVA
                && b.getRelative(0, 1, 0).isPassable() && b.getRelative(0, 2, 0).isPassable();
    }

    private void setProp(Tx tx, String key, String value) throws IOException {
        File f = new File("server.properties");
        if (!f.isFile()) {
            throw new IOException("server.properties bulunamadı");
        }
        tx.backup(f);
        List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.ISO_8859_1);
        boolean done = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(key + "=")) {
                lines.set(i, key + "=" + value);
                done = true;
            }
        }
        if (!done) {
            lines.add(key + "=" + value);
        }
        Files.write(f.toPath(), lines, StandardCharsets.ISO_8859_1);
    }

    private void reloadSkript() {
        if (!has("Skript")) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(this,
                () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "sk reload scripts"), 20L);
    }

    // ------------------------------------------------------------------
    // Rapor
    // ------------------------------------------------------------------

    private void report(CommandSender sender) {
        msg(sender, "&8&m-----&r &6&lAçıkBulucu Raporu &8&m-----");
        if (findings.isEmpty()) {
            msg(sender, "&a✔ Hiçbir açık bulunamadı. Sunucu temiz görünüyor!");
            return;
        }
        msg(sender, "&c" + count(Level.KRITIK) + " kritik &8| &e" + count(Level.UYARI) + " uyarı &8| &b"
                + count(Level.ONERI) + " öneri");
        int n = 1;
        for (Level level : Level.values()) {
            for (Finding f : findings) {
                if (f.level != level) {
                    continue;
                }
                msg(sender, "&7" + n + ". " + level.label + " &f" + f.title
                        + (f.fixer != null ? " &a[otomatik onarılabilir]" : ""));
                msg(sender, "   &8↳ &7" + f.advice);
                n++;
            }
        }
        if (hasFixable()) {
            msg(sender, "&7Önizleme: &e/aciklaribul onizle &8| &7Onarım: &e/aciklaribul onar &8| &7Geri al: &e/aciklaribul geriyukle");
        }
        msg(sender, "&8&m------------------------------");
    }

    // ------------------------------------------------------------------
    // Yardimcilar
    // ------------------------------------------------------------------

    private void guard(String name, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            add(Level.ONERI, name + " kontrolü çalışamadı", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void add(Level level, String title, String advice) {
        findings.add(new Finding(level, title, advice, null, null));
    }

    private void addFix(Level level, String title, String advice, String fixDesc, Fixer fixer) {
        findings.add(new Finding(level, title, advice, fixDesc, fixer));
    }

    private List<Finding> ordered() {
        List<Finding> out = new ArrayList<>();
        for (Level l : Level.values()) {
            for (Finding f : findings) {
                if (f.level == l) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    private List<Finding> fixableList() {
        List<Finding> out = new ArrayList<>();
        for (Finding f : findings) {
            if (f.fixer != null) {
                out.add(f);
            }
        }
        return out;
    }

    private boolean skipWorld(World w) {
        List<String> words = getConfig().getStringList("dunya-kontrol.haric-kelimeler");
        if (words.isEmpty()) {
            words = Arrays.asList("skyblock", "island", "void");
        }
        String n = w.getName().toLowerCase(Locale.ROOT);
        for (String s : words) {
            if (n.contains(s.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String logTail() {
        File log = new File("logs/latest.log");
        if (!log.isFile()) {
            return null;
        }
        try (RandomAccessFile raf = new RandomAccessFile(log, "r")) {
            long len = raf.length();
            long start = Math.max(0L, len - 1_000_000L);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private String logReasons(List<String> names) {
        String text = logTail();
        if (text == null) {
            return "";
        }
        Pattern bad = Pattern.compile(
                "(?i)(error|warn|severe|could not|missing|requires|depend|unsupported|failed|disabling|incompatible)");
        List<String> out = new ArrayList<>();
        String[] lines = text.split("\r?\n");
        for (String name : names) {
            int c = 0;
            for (String line : lines) {
                if (c >= 2) {
                    break;
                }
                if (line.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))
                        && bad.matcher(line).find()) {
                    String t = line.replaceFirst("^\\[[0-9:]+ [A-Z]+\\]: ", "").trim();
                    out.add(name + ": " + (t.length() > 160 ? t.substring(0, 160) + "..." : t));
                    c++;
                }
            }
        }
        return String.join(" | ", limit(out, 6));
    }

    private List<String> readLines(File f) {
        try {
            String t = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            return Arrays.asList(t.split("\r?\n", -1));
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private boolean hasFixable() {
        for (Finding f : findings) {
            if (f.fixer != null) {
                return true;
            }
        }
        return false;
    }

    private int count(Level level) {
        int c = 0;
        for (Finding f : findings) {
            if (f.level == level) {
                c++;
            }
        }
        return c;
    }

    private void msg(CommandSender sender, String text) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', text));
    }

    private File scriptsDir() {
        return new File(getDataFolder().getParentFile(), "Skript/scripts");
    }

    private List<File> enabledScripts() {
        List<File> out = new ArrayList<>();
        File[] files = scriptsDir().listFiles();
        if (files == null) {
            return out;
        }
        for (File f : files) {
            String n = f.getName();
            if (f.isFile() && n.toLowerCase(Locale.ROOT).endsWith(".sk") && !n.startsWith("-")) {
                out.add(f);
            }
        }
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    private String readLatin(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.ISO_8859_1);
    }

    private boolean has(String name) {
        Plugin pl = Bukkit.getPluginManager().getPlugin(name);
        return pl != null && pl.isEnabled();
    }

    private boolean hasAny(String... names) {
        for (String n : names) {
            if (has(n)) {
                return true;
            }
        }
        return false;
    }

    private String nameContains(String... parts) {
        for (Plugin pl : Bukkit.getPluginManager().getPlugins()) {
            if (!pl.isEnabled()) {
                continue;
            }
            String low = pl.getName().toLowerCase(Locale.ROOT);
            for (String part : parts) {
                if (low.contains(part)) {
                    return pl.getName();
                }
            }
        }
        return null;
    }

    private void conflict(Level level, String title, String advice, String... names) {
        List<String> present = new ArrayList<>();
        for (String n : names) {
            if (has(n)) {
                present.add(n);
            }
        }
        if (present.size() >= 2) {
            add(level, title + ": " + String.join(", ", present), advice);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean hasProvider(Plugin owner, String className) {
        try {
            Class<?> c = Class.forName(className, true, owner.getClass().getClassLoader());
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration((Class) c);
            return rsp != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isProxy() {
        try {
            if (YamlConfiguration.loadConfiguration(new File("spigot.yml")).getBoolean("settings.bungeecord", false)) {
                return true;
            }
            File paper = new File("config/paper-global.yml");
            if (paper.isFile()
                    && YamlConfiguration.loadConfiguration(paper).getBoolean("proxies.velocity.enabled", false)) {
                return true;
            }
        } catch (Throwable ignored) {
            // yoksay
        }
        return false;
    }

    private Properties props() {
        Properties p = new Properties();
        File f = new File("server.properties");
        if (f.isFile()) {
            try (InputStream in = new FileInputStream(f)) {
                p.load(in);
            } catch (Exception ignored) {
                // okunamazsa bos don
            }
        }
        return p;
    }

    private int intProp(Properties p, String key, int def) {
        try {
            return Integer.parseInt(p.getProperty(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private List<String> limit(List<String> list, int max) {
        if (list.size() <= max) {
            return list;
        }
        List<String> out = new ArrayList<>(list.subList(0, max));
        out.add("+" + (list.size() - max) + " tane daha");
        return out;
    }
}
