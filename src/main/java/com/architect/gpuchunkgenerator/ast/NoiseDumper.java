package com.architect.gpuchunkgenerator.ast;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Utilitaire de diagnostic pour extraire les tables de permutation du bruit Minecraft.
 * Indispensable pour assurer la parité mathématique sur le GPU.
 */
public class NoiseDumper {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Set<Object> VISITED = new HashSet<>();

    public static void dumpRandomState(RandomState randomState) {
        List<byte[]> octaves = extractAllPermutations(randomState);
        LOGGER.info("Début de l'extraction des tables de bruit ({} octaves)...", octaves.size());
        
        StringBuilder sb = new StringBuilder();
        sb.append("=== DUMP DES TABLES DE PERMUTATION (SEED-BASED) ===\n");
        sb.append("Nombre total d'octaves: ").append(octaves.size()).append("\n\n");

        for (int i = 0; i < octaves.size(); i++) {
            sb.append("  Octave ").append(i).append(": ").append(formatByteArray(octaves.get(i))).append("\n");
        }

        try {
            Path path = Paths.get("run", "run", "noise_permutations_dump.txt");
            Files.createDirectories(path.getParent());
            Files.writeString(path, sb.toString());
            LOGGER.info("Tables de permutation extraites avec succès.");
        } catch (IOException e) {
            LOGGER.error("Erreur lors de l'écriture du dump de bruit", e);
        }
    }

    public static List<byte[]> extractAllPermutations(RandomState randomState) {
        List<byte[]> result = new ArrayList<>();
        NoiseRouter router = randomState.router();
        VISITED.clear();

        findBlendedOctaves(router.finalDensity(), result);
        // On pourrait aussi chercher dans d'autres champs si besoin, mais le BlendedNoise est partagé
        return result;
    }

    private static void findBlendedOctaves(DensityFunction function, List<byte[]> result) {
        if (function == null || VISITED.contains(function)) return;
        VISITED.add(function);

        if (function instanceof BlendedNoise blended) {
            collectBlendedNoiseData(blended, result);
            return;
        }

        // Récursion via réflexion
        for (Field field : function.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                Object val = field.get(function);
                if (val instanceof DensityFunction child) {
                    findBlendedOctaves(child, result);
                } else if (val instanceof net.minecraft.core.Holder<?> holder) {
                     if (holder.isBound() && holder.value() instanceof DensityFunction hChild) {
                         findBlendedOctaves(hChild, result);
                     }
                }
            } catch (Exception ignored) {}
        }
    }

    private static void collectBlendedNoiseData(BlendedNoise noise, List<byte[]> result) {
        try {
            collectPerlinOctaves(noise, "minLimitNoise", result);
            collectPerlinOctaves(noise, "maxLimitNoise", result);
            collectPerlinOctaves(noise, "mainNoise", result);
        } catch (Exception e) {
            LOGGER.error("Erreur collectBlendedNoiseData", e);
        }
    }

    private static void collectPerlinOctaves(Object owner, String fieldName, List<byte[]> result) throws Exception {
        Field f = owner.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        PerlinNoise perlin = (PerlinNoise) f.get(owner);
        if (perlin == null) return;

        Field levelsField = PerlinNoise.class.getDeclaredField("noiseLevels");
        levelsField.setAccessible(true);
        ImprovedNoise[] levels = (ImprovedNoise[]) levelsField.get(perlin);

        for (ImprovedNoise octave : levels) {
            if (octave == null) continue;
            Field pField = ImprovedNoise.class.getDeclaredField("p");
            pField.setAccessible(true);
            result.add((byte[]) pField.get(octave));
        }
    }

    private static void extractBlendedNoiseData(BlendedNoise noise, StringBuilder sb) {
        try {
            dumpPerlinNoise(noise, "minLimitNoise", sb);
            dumpPerlinNoise(noise, "maxLimitNoise", sb);
            dumpPerlinNoise(noise, "mainNoise", sb);
        } catch (Exception e) {
            sb.append("Erreur d'extraction : ").append(e.getMessage()).append("\n");
        }
    }

    private static void dumpPerlinNoise(Object owner, String fieldName, StringBuilder sb) throws Exception {
        Field f = owner.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        PerlinNoise perlin = (PerlinNoise) f.get(owner);

        if (perlin == null) {
            sb.append("Noise ").append(fieldName).append(" est NULL\n");
            return;
        }

        sb.append("  > PerlinNoise: ").append(fieldName).append("\n");

        // Extraction des octaves (ImprovedNoise[])
        Field levelsField = PerlinNoise.class.getDeclaredField("noiseLevels");
        levelsField.setAccessible(true);
        ImprovedNoise[] levels = (ImprovedNoise[]) levelsField.get(perlin);

        for (int i = 0; i < levels.length; i++) {
            ImprovedNoise octave = levels[i];
            if (octave == null) continue;

            // Extraction de la table p (byte[])
            Field pField = ImprovedNoise.class.getDeclaredField("p");
            pField.setAccessible(true);
            byte[] p = (byte[]) pField.get(octave);

            sb.append("    Octave ").append(i).append(" [p table]: ");
            sb.append(formatByteArray(p)).append("\n");
        }
        sb.append("\n");
    }

    private static String formatByteArray(byte[] arr) {
        if (arr == null) return "null";
        int[] unsigned = new int[arr.length];
        for (int i = 0; i < arr.length; i++) {
            unsigned[i] = arr[i] & 0xFF;
        }
        return Arrays.toString(unsigned);
    }
}
