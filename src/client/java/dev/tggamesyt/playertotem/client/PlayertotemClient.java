package dev.tggamesyt.playertotem.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;

import java.io.*;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class PlayertotemClient implements ClientModInitializer {

    public static final String MOD_ID = "playertotem";
    private static final String PACK_ID = "file/player_totem_pack";

    /** Skin URL from the most recent download — used to detect skin changes. */
    private String lastDownloadedSkinUrl = null;

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(c -> {
            Thread thread = new Thread(() -> {
                try {
                    String previousUrl = readCachedSkinUrl();
                    NativeImage skin = downloadPlayerSkin(c);
                    boolean forceReload = false;

                    if (skin != null) {
                        // Skin changed if: no pack on disk, or URL differs from last saved URL
                        boolean changed = !packExistsOnDisk()
                                || lastDownloadedSkinUrl == null
                                || !lastDownloadedSkinUrl.equals(previousUrl);
                        if (changed) {
                            writeTotemPack(c, skin);
                            saveCachedSkinUrl(lastDownloadedSkinUrl);
                            forceReload = true;
                        }
                    } else if (!packExistsOnDisk()) {
                        // Download failed and no cached pack — fall back to Steve
                        System.out.println("[PlayerTotem] Falling back to Steve skin");
                        NativeImage steve = loadSteveSkin(c);
                        if (steve == null) {
                            System.out.println("[PlayerTotem] Could not obtain any skin texture");
                            return;
                        }
                        writeTotemPack(c, steve);
                        forceReload = true;
                    }
                    // else: download failed but cached pack exists — just ensure it's enabled below

                    boolean reload = forceReload;
                    c.execute(() -> enablePackAndReload(c, reload));

                } catch (Exception e) {
                    System.out.println("[PlayerTotem] Error: " + e.getMessage());
                    e.printStackTrace();
                }
            }, "PlayerTotem-Skin-Download");
            thread.setDaemon(true);
            thread.start();
        });
    }

    private boolean packExistsOnDisk() {
        return new File(FabricLoader.getInstance().getGameDir().toFile(),
                "resourcepacks/player_totem_pack/assets/minecraft/textures/item/totem_of_undying.png")
                .exists();
    }

    private File skinUrlCacheFile() {
        File dir = new File(FabricLoader.getInstance().getGameDir().toFile(), "config/playertotem");
        dir.mkdirs();
        return new File(dir, "last_skin_url.txt");
    }

    private String readCachedSkinUrl() {
        try {
            File f = skinUrlCacheFile();
            if (f.exists()) return Files.readString(f.toPath()).trim();
        } catch (Exception ignored) {}
        return null;
    }

    private void saveCachedSkinUrl(String url) {
        if (url == null) return;
        try {
            Files.writeString(skinUrlCacheFile().toPath(), url);
        } catch (Exception ignored) {}
    }

    /**
     * Gets the player's UUID string from the session, handling API changes across MC versions.
     * 1.20.1: Session.getProfile().getId()
     * 1.21.11+: getProfile() was removed; Session exposes UUID via a different method.
     */
    private static String getSessionUuid(MinecraftClient client) {
        Object session = client.getSession();
        // Try getProfile().getId() — works on 1.20.1 (authlib GameProfile.getId() is stable)
        try {
            Object profile = session.getClass().getMethod("getProfile").invoke(session);
            Object uuid = profile.getClass().getMethod("getId").invoke(profile);
            if (uuid != null) return uuid.toString().replace("-", "");
        } catch (Exception ignored) {}
        // Try getUuidOrNull() — added in newer MC versions
        try {
            Object uuid = session.getClass().getMethod("getUuidOrNull").invoke(session);
            if (uuid != null) return uuid.toString().replace("-", "");
        } catch (Exception ignored) {}
        // Signature scan: find a no-arg method returning UUID on Session
        for (Method m : session.getClass().getMethods()) {
            if (m.getReturnType() == java.util.UUID.class && m.getParameterCount() == 0) {
                try {
                    Object uuid = m.invoke(session);
                    if (uuid != null) return uuid.toString().replace("-", "");
                } catch (Exception ignored) {}
            }
        }
        throw new RuntimeException("[PlayerTotem] Cannot get player UUID from session");
    }

    /**
     * Downloads the player's skin from Mojang's session server.
     * Sets {@link #lastDownloadedSkinUrl} so callers can detect skin changes.
     */
    private NativeImage downloadPlayerSkin(MinecraftClient client) {
        try {
            String uuid = getSessionUuid(client);

            URL profileUrl = new URL("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid);
            HttpURLConnection conn = (HttpURLConnection) profileUrl.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                System.out.println("[PlayerTotem] Mojang API returned HTTP " + responseCode);
                return null;
            }

            String profileJson;
            try (InputStream in = conn.getInputStream()) {
                profileJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            JsonObject profile = JsonParser.parseString(profileJson).getAsJsonObject();
            JsonArray properties = profile.getAsJsonArray("properties");
            String texturesBase64 = null;
            for (JsonElement prop : properties) {
                JsonObject p = prop.getAsJsonObject();
                if ("textures".equals(p.get("name").getAsString())) {
                    texturesBase64 = p.get("value").getAsString();
                    break;
                }
            }

            if (texturesBase64 == null) {
                System.out.println("[PlayerTotem] No textures property in profile");
                return null;
            }

            String texturesJson = new String(Base64.getDecoder().decode(texturesBase64), StandardCharsets.UTF_8);
            JsonObject textures = JsonParser.parseString(texturesJson).getAsJsonObject();
            JsonObject skinObj = textures.getAsJsonObject("textures").getAsJsonObject("SKIN");

            if (skinObj == null) {
                System.out.println("[PlayerTotem] No SKIN entry in textures");
                return null;
            }

            String skinUrl = skinObj.get("url").getAsString();
            lastDownloadedSkinUrl = skinUrl;

            HttpURLConnection skinConn = (HttpURLConnection) new URL(skinUrl).openConnection();
            skinConn.setConnectTimeout(5000);
            skinConn.setReadTimeout(5000);

            byte[] skinBytes;
            try (InputStream skinStream = skinConn.getInputStream()) {
                skinBytes = skinStream.readAllBytes();
            }

            NativeImage img = NativeImage.read(new ByteArrayInputStream(skinBytes));
            System.out.println("[PlayerTotem] Downloaded skin (" + img.getWidth() + "x" + img.getHeight() + ")");
            return img;

        } catch (Exception e) {
            System.out.println("[PlayerTotem] Could not download skin: " + e.getMessage());
            return null;
        }
    }

    private NativeImage loadSteveSkin(MinecraftClient client) {
        try {
            var steveId = IdentifierUtil.id("minecraft", "textures/entity/player/wide/steve.png");
            var resource = client.getResourceManager().getResource(steveId);
            if (resource.isPresent()) {
                return NativeImage.read(resource.get().getInputStream());
            }
        } catch (Exception e) {
            System.out.println("[PlayerTotem] Could not load Steve skin: " + e.getMessage());
        }
        return null;
    }

    private void writeTotemPack(MinecraftClient client, NativeImage skin) throws IOException {
        NativeImage totemTex = TotemTextureManager.generateTotem(skin);

        File gameDir = FabricLoader.getInstance().getGameDir().toFile();
        File packRoot = new File(gameDir, "resourcepacks/player_totem_pack");
        File textureDir = new File(packRoot, "assets/minecraft/textures/item");
        textureDir.mkdirs();

        totemTex.writeTo(new File(textureDir, "totem_of_undying.png"));
        totemTex.writeTo(new File(packRoot, "icon.png"));

        try (FileWriter w = new FileWriter(new File(packRoot, "pack.mcmeta"))) {
            w.write("{\n" +
                    "  \"pack\": {\n" +
                    "    \"pack_format\": 15,\n" +
                    "    \"supported_formats\": {\"min_format\": 15, \"max_format\": 71},\n" +
                    "    \"description\": \"Player skin totem texture\"\n" +
                    "  }\n" +
                    "}");
        }

        System.out.println("[PlayerTotem] Generated totem pack at " + packRoot.getAbsolutePath());
    }

    private static Method PROFILE_ID_METHOD = null;

    private static String getProfileId(Object profile) {
        if (PROFILE_ID_METHOD == null) {
            PROFILE_ID_METHOD = resolveProfileIdMethod(profile.getClass());
        }
        try {
            return (String) PROFILE_ID_METHOD.invoke(profile);
        } catch (Exception e) {
            throw new RuntimeException("Cannot get pack profile id", e);
        }
    }

    private static Method resolveProfileIdMethod(Class<?> cls) {
        for (String name : new String[]{"getName", "getId", "getInternalName"}) {
            try {
                Method m = cls.getMethod(name);
                if (m.getReturnType() == String.class) return m;
            } catch (NoSuchMethodException ignored) {}
        }
        for (Method m : cls.getMethods()) {
            if (m.getReturnType() == String.class
                    && m.getParameterCount() == 0
                    && !m.getDeclaringClass().equals(Object.class)) {
                System.out.println("[PlayerTotem] Resolved profile id method via scan: " + m.getName());
                return m;
            }
        }
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getReturnType() == String.class && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    System.out.println("[PlayerTotem] Resolved profile id method via declared scan: " + m.getName());
                    return m;
                }
            }
        }
        throw new RuntimeException("Cannot find pack profile id method on " + cls.getName());
    }

    /**
     * Ensures the pack is enabled and reloads resources if needed.
     * @param forceReload true when pack content changed (skin updated) — always reloads.
     *                    false when skin is unchanged — only reloads if pack wasn't already enabled.
     */
    private void enablePackAndReload(MinecraftClient client, boolean forceReload) {
        var rpm = client.getResourcePackManager();
        rpm.scanPacks();

        boolean found = false;
        for (var profile : rpm.getProfiles()) {
            if (getProfileId(profile).equals(PACK_ID)) {
                found = true;
                break;
            }
        }
        if (!found) {
            System.out.println("[PlayerTotem] Pack not found after scan: " + PACK_ID);
            return;
        }

        // Check if already enabled in the current session
        boolean alreadyEnabled = false;
        for (var p : rpm.getEnabledProfiles()) {
            if (getProfileId(p).equals(PACK_ID)) {
                alreadyEnabled = true;
                break;
            }
        }

        if (!alreadyEnabled) {
            List<String> enabled = new ArrayList<>();
            for (var p : rpm.getEnabledProfiles()) {
                enabled.add(getProfileId(p));
            }
            enabled.add(PACK_ID);
            rpm.setEnabledProfiles(enabled);
            System.out.println("[PlayerTotem] Enabled pack: " + PACK_ID);
        }

        if (!alreadyEnabled || forceReload) {
            client.reloadResources();
        } else {
            System.out.println("[PlayerTotem] Pack already enabled with current skin, no reload needed");
        }
    }
}
