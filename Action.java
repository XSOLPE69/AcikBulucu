package tr.acikbulucuai;

import java.util.Map;

/** Yapay zekanın istediği tek bir işlem. */
final class Action {
    static final String OKU = "dosya_oku", LISTELE = "klasor_listele",
            YAZ = "dosya_yaz", DUZENLE = "dosya_duzenle", KOMUT = "komut";

    String tur, yol, icerik, bul, degistir, komut, neden;
    int sonSatir;

    boolean readOnly() { return OKU.equals(tur) || LISTELE.equals(tur); }

    static Action from(Map<String, Object> m) {
        if (m == null) return null;
        String t = Json.str(m.get("tur"));
        if (t == null) return null;
        t = t.trim().toLowerCase();
        if (!(t.equals(OKU) || t.equals(LISTELE) || t.equals(YAZ) || t.equals(DUZENLE) || t.equals(KOMUT))) return null;
        Action a = new Action();
        a.tur = t;
        a.yol = Json.str(m.get("yol"));
        a.icerik = Json.str(m.get("icerik"));
        a.bul = Json.str(m.get("bul"));
        a.degistir = Json.str(m.get("degistir"));
        a.komut = Json.str(m.get("komut"));
        a.neden = Json.str(m.get("neden"));
        a.sonSatir = Json.integer(m.get("son_satir"), 0);
        return a;
    }
}
