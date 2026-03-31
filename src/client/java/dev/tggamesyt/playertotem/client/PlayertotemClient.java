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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class PlayertotemClient implements ClientModInitializer {

    public static final String MOD_ID = "playertotem";
    private static final String PACK_ID = "file/player_totem_pack";

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            // Download skin on a background thread immediately at game start,
            // before the main menu appears, so the reload never interrupts gameplay.
            Thread thread = new Thread(() -> {
                try {
                    NativeImage skin = downloadPlayerSkin(client);
                    if (skin == null) {
                        System.out.println("[PlayerTotem] Falling back to Steve skin");
                        skin = loadSteveSkin(client);
                    }
                    if (skin != null) {
                        writeTotemPack(client, skin);
                        client.execute(() -> enablePackAndReload(client));
                    } else {
                        System.out.println("[PlayerTotem] Could not obtain any skin texture");
                    }
                } catch (Exception e) {
                    System.out.println("[PlayerTotem] Error: " + e.getMessage());
                    e.printStackTrace();
                }
            }, "PlayerTotem-Skin-Download");
            thread.setDaemon(true);
            thread.start();
        });
    }

    /**
     * Downloads the player's skin directly from Mojang's session server.
     * Works on all versions since it uses HTTP, not version-specific MC APIs.
     */
    private NativeImage downloadPlayerSkin(MinecraftClient client) {
        try {
            String uuid = client.getSession().getProfile().getId().toString().replace("-", "");

            // Step 1: Get profile from Mojang's session server
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

            // Step 2: Extract the skin URL from the Base64-encoded textures property
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

            // Step 3: Download the skin image
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

        // pack.mcmeta — pack_format 15 for 1.20.1, supported_formats range for newer versions
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

    /**
     * Returns the pack profile's string ID.
     * MC remaps class internals between versions, so Yarn names like "getName"/"getId"
     * don't match at runtime (intermediary names like method_XXXX are used instead).
     * We scan by signature: ResourcePackProfile has exactly one no-arg String method.
     */
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
        // Try known Yarn names first (getName = 1.20.1, getId = 1.21+)
        for (String name : new String[]{"getName", "getId", "getInternalName"}) {
            try {
                Method m = cls.getMethod(name);
                if (m.getReturnType() == String.class) return m;
            } catch (NoSuchMethodException ignored) {}
        }
        // Signature scan: find the single public no-arg String method that isn't from Object
        // ResourcePackProfile has exactly one such method across all MC versions
        for (Method m : cls.getMethods()) {
            if (m.getReturnType() == String.class
                    && m.getParameterCount() == 0
                    && !m.getDeclaringClass().equals(Object.class)) {
                System.out.println("[PlayerTotem] Resolved profile id method via scan: " + m.getName());
                return m;
            }
        }
        // Last resort: check non-public declared methods up the hierarchy
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

    private void enablePackAndReload(MinecraftClient client) {
        var rpm = client.getResourcePackManager();
        rpm.scanPacks();

        // Check if the pack was discovered
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

        // Add our pack to the enabled list if not already there
        List<String> enabled = new ArrayList<>();
        for (var p : rpm.getEnabledProfiles()) {
            enabled.add(getProfileId(p));
        }

        if (!enabled.contains(PACK_ID)) {
            enabled.add(PACK_ID);
            rpm.setEnabledProfiles(enabled);
            System.out.println("[PlayerTotem] Enabled pack: " + PACK_ID);
        } else {
            System.out.println("[PlayerTotem] Pack already enabled");
        }

        client.reloadResources();
    }
}
