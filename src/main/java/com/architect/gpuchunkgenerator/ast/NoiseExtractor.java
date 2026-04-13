package com.architect.gpuchunkgenerator.ast;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import com.architect.gpuchunkgenerator.ast.ir.GpuNode;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Moteur d'extraction par réflexion pour les samplers de bruit Minecraft 1.21.1.
 * Parité 1:1 via NormalNoise (ex-DoublePerlin) et PerlinNoise (ex-OctavePerlin).
 */
public class NoiseExtractor {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<NormalNoise, GpuNode.Noise> CACHE = new HashMap<>();
    private static final List<GpuNode.Noise> REGISTERED_NOISES = new ArrayList<>();
    private static int currentBufferOffset = 0;

    public static GpuNode.Noise extract(String name, NormalNoise sampler, double xzScale, double yScale) {
        if (CACHE.containsKey(sampler)) {
            return CACHE.get(sampler);
        }

        try {
            // 1. Extraction des composants du NormalNoise (Mojang Mappings 1.21.1)
            Object ampObj = getField(sampler, "amplitude", Object.class);
            float globalAmplitude = (ampObj instanceof Number n) ? n.floatValue() : 1.0f;
            PerlinNoise firstSampler = (PerlinNoise) getField(sampler, "firstSampler", PerlinNoise.class);
            PerlinNoise secondSampler = (PerlinNoise) getField(sampler, "secondSampler", PerlinNoise.class);

            // 2. Extraction des données des PerlinNoise samplers
            GpuNode.SamplerData s1 = extractSamplerData(firstSampler);
            GpuNode.SamplerData s2 = extractSamplerData(secondSampler);

            GpuNode.Noise gpuNoise = new GpuNode.Noise(name, xzScale, yScale, globalAmplitude, s1, s2, currentBufferOffset);
            
            // 3. Calcul de l'offset pour le SSBO (Header (20) + Amplitudes + Permutations)
            int size = 20 + (s1.amplitudes().length + s2.amplitudes().length) * (4 + 512 * 4);
            currentBufferOffset += size;

            CACHE.put(sampler, gpuNoise);
            REGISTERED_NOISES.add(gpuNoise);
            return gpuNoise;

        } catch (Exception e) {
            LOGGER.error("Impossible d'extraire les données de bruit pour {}", name, e);
            return null;
        }
    }

    private static GpuNode.SamplerData extractSamplerData(PerlinNoise sampler) throws Exception {
        Object amplitudesObj = getField(sampler, "amplitudes", Object.class);
        float[] amplitudesFloat;

        if (amplitudesObj instanceof double[] dArr) {
            amplitudesFloat = new float[dArr.length];
            for (int i = 0; i < dArr.length; i++) amplitudesFloat[i] = (float) dArr[i];
        } else if (amplitudesObj instanceof List<?> list) { 
            amplitudesFloat = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object val = list.get(i);
                if (val instanceof Double d) amplitudesFloat[i] = d.floatValue();
                else if (val instanceof Float f) amplitudesFloat[i] = f;
                else if (val instanceof Number n) amplitudesFloat[i] = n.floatValue();
                else amplitudesFloat[i] = 0.0f;
            }
        } else {
            amplitudesFloat = new float[0];
        }

        int firstOctave = (int) getField(sampler, "firstOctave", int.class);
        
        // Extraction des ImprovedNoise dans 'noiseLevels'
        ImprovedNoise[] noises = (ImprovedNoise[]) getField(sampler, "noiseLevels", ImprovedNoise[].class);
        byte[][] permutations = new byte[noises.length][512];

        for (int i = 0; i < noises.length; i++) {
            if (noises[i] != null) {
                byte[] pArray = (byte[]) getField(noises[i], "p", byte[].class);
                for (int j = 0; j < 512; j++) {
                    permutations[i][j] = pArray[j % pArray.length];
                }
            }
        }

        return new GpuNode.SamplerData(firstOctave, amplitudesFloat, permutations);
    }

    private static Object getField(Object obj, String name, Class<?> type) throws Exception {
        Class<?> current = obj.getClass();
        while (current != null && current != Object.class) {
            try {
                Field f = current.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        
        // Seconde chance : recherche par type
        current = obj.getClass();
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                if (type.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f.get(obj);
                }
            }
            current = current.getSuperclass();
        }
        throw new NoSuchFieldException("Champ " + name + " de type " + type.getSimpleName() + " non trouvé.");
    }

    public static List<GpuNode.Noise> getRegisteredNoises() {
        return REGISTERED_NOISES;
    }

    public static void clear() {
        CACHE.clear();
        REGISTERED_NOISES.clear();
        currentBufferOffset = 0;
    }
}
