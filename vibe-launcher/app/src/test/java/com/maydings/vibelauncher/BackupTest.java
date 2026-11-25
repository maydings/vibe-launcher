package com.maydings.vibelauncher;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

/** Backup encryption/decryption (format 2), format 1 compatibility, and strict schema validation. */
public class BackupTest {

    private static String samplePlainBackup() throws Exception {
        JSONObject vs = new JSONObject();
        vs.put("tmdbKey", new JSONArray().put("s").put("secret-tmdb-key"));
        vs.put("plexToken", new JSONArray().put("s").put("secret-plex-token"));
        vs.put("columns", new JSONArray().put("i").put(5));
        vs.put("topBarMinimal", new JSONArray().put("b").put(true));
        vs.put("language", new JSONArray().put("s").put("en"));
        vs.put("uiFont", new JSONArray().put("s").put("sys:sans-serif"));

        JSONObject tiles = new JSONObject();
        tiles.put("order", new JSONArray().put("s").put("com.netflix.ninja,com.plexapp.android"));
        tiles.put("hidden", new JSONArray().put("S").put(new JSONArray().put("com.example.hidden")));

        JSONObject prefs = new JSONObject();
        prefs.put("vibe_settings", vs);
        prefs.put("tiles", tiles);

        JSONObject root = new JSONObject();
        root.put("vibeLauncherBackup", 1);
        root.put("prefs", prefs);
        root.put("pictures", new JSONObject());
        return root.toString();
    }

    @Test
    public void encryptDecryptRoundTripPreservesApiKeys() throws Exception {
        String plain = samplePlainBackup();
        String encrypted = Backup.encrypt(plain, "my-strong-passphrase");

        assertTrue(Backup.isEncrypted(encrypted));
        assertFalse(encrypted.contains("secret-tmdb-key"));
        assertFalse(encrypted.contains("secret-plex-token"));

        Backup.ReadResult res = Backup.readWithStatus(encrypted, "my-strong-passphrase");
        assertEquals(Backup.READ_OK, res.status);
        assertNotNull(res.contents);
        assertEquals("en", res.contents.language);
        assertEquals("secret-tmdb-key",
                res.contents.prefs.get("vibe_settings").getJSONArray("tmdbKey").getString(1));
        assertEquals("secret-plex-token",
                res.contents.prefs.get("vibe_settings").getJSONArray("plexToken").getString(1));
    }

    @Test
    public void wrongPassphraseReturnsWrongPassphraseStatus() throws Exception {
        String encrypted = Backup.encrypt(samplePlainBackup(), "correct-horse");
        assertEquals(Backup.READ_WRONG_PASSPHRASE, Backup.readWithStatus(encrypted, "wrong-horse").status);
        assertEquals(Backup.READ_WRONG_PASSPHRASE, Backup.readWithStatus(encrypted, "").status);
        assertNull(Backup.read(encrypted, "wrong-horse"));
    }

    @Test
    public void tamperedCiphertextRejected() throws Exception {
        String encrypted = Backup.encrypt(samplePlainBackup(), "pass123");
        JSONObject env = new JSONObject(encrypted);
        String data = env.getString("data");
        char flipped = data.charAt(0) == 'A' ? 'B' : 'A';
        env.put("data", flipped + data.substring(1));

        Backup.ReadResult res = Backup.readWithStatus(env.toString(), "pass123");
        assertNotEquals(Backup.READ_OK, res.status);
        assertNull(res.contents);
    }

    @Test
    public void legacyPlaintextFormatStillAccepted() throws Exception {
        String plain = samplePlainBackup();
        assertFalse(Backup.isEncrypted(plain));
        Backup.ReadResult res = Backup.readWithStatus(plain, "");
        assertEquals(Backup.READ_OK, res.status);
        assertNotNull(res.contents);
    }

    @Test
    public void rejectsInvalidTypesAndFontPathTraversal() throws Exception {
        // Multi-char / empty type tags
        JSONObject badTag = new JSONObject(samplePlainBackup());
        badTag.getJSONObject("prefs").getJSONObject("vibe_settings")
                .put("tmdbKey", new JSONArray().put("sS").put("abc"));
        assertNull(Backup.read(badTag.toString(), ""));

        // Wrong type for known int key (would cause ClassCastException crash loop)
        JSONObject wrongType = new JSONObject(samplePlainBackup());
        wrongType.getJSONObject("prefs").getJSONObject("vibe_settings")
                .put("columns", new JSONArray().put("s").put("5"));
        assertNull(Backup.read(wrongType.toString(), ""));

        // JSON null value
        JSONObject nullVal = new JSONObject(samplePlainBackup());
        nullVal.getJSONObject("prefs").getJSONObject("vibe_settings")
                .put("tmdbKey", new JSONArray().put("s").put(JSONObject.NULL));
        assertNull(Backup.read(nullVal.toString(), ""));

        // Path traversal in uiFont
        JSONObject traversal = new JSONObject(samplePlainBackup());
        traversal.getJSONObject("prefs").getJSONObject("vibe_settings")
                .put("uiFont", new JSONArray().put("s").put("../../secret"));
        assertNull(Backup.read(traversal.toString(), ""));
    }
}
