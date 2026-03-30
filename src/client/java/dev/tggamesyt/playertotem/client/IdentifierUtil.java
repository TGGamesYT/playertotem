package dev.tggamesyt.playertotem.client;

import net.minecraft.util.Identifier;

import java.lang.reflect.Method;

public final class IdentifierUtil {

    private static Method IDENTIFIER_OF = null;
    private static boolean HAS_IDENTIFIER_OF;

    static {
        try {
            IDENTIFIER_OF = Identifier.class.getDeclaredMethod(
                    "of", String.class, String.class
            );
            HAS_IDENTIFIER_OF = true;
        } catch (NoSuchMethodException e) {
            HAS_IDENTIFIER_OF = false;
        }
    }

    public static Identifier id(String namespace, String path) {
        try {
            if (HAS_IDENTIFIER_OF) {
                // Newer MC versions
                return (Identifier) IDENTIFIER_OF.invoke(null, namespace, path);
            } else {
                // Older MC versions
                return new Identifier(namespace, path);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create Identifier: " + namespace + ":" + path, e);
        }
    }
}
