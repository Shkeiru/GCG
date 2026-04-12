package com.architect.gpuchunkgenerator.ast;

import com.architect.gpuchunkgenerator.ast.ir.GpuNode;
import net.minecraft.util.CubicSpline;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registre central pour la cuisson et la gestion des Splines Minecraft.
 * Permet de transformer des arbres de splines complexes en LUTs 1D prêtes pour le GPU.
 */
public class SplineRegistry {

    private static final Map<Object, Integer> SPLINE_TO_ID = new HashMap<>();
    private static final List<BakedSplineData> REGISTERED_SPLINES = new ArrayList<>();
    
    public static final int RESOLUTION = 1024;
    public static final float RANGE_MIN = -2.0f;
    public static final float RANGE_MAX = 2.0f;

    public record BakedSplineData(int id, float[] lut, float min, float max) {}

    /**
     * Enregistre une spline et prépare ses données de cuisson.
     */
    public static GpuNode.Spline register(Object spline, GpuNode coordinate) {
        if (SPLINE_TO_ID.containsKey(spline)) {
            int id = SPLINE_TO_ID.get(spline);
            return new GpuNode.Spline(coordinate, id, RANGE_MIN, RANGE_MAX);
        }

        int newId = REGISTERED_SPLINES.size();
        SPLINE_TO_ID.put(spline, newId);

        // Cuisson immédiate pour s'assurer que les données sont prêtes
        float[] bakedLut = bake(spline, newId);
        REGISTERED_SPLINES.add(new BakedSplineData(newId, bakedLut, RANGE_MIN, RANGE_MAX));

        // Dump de l'anatomie pour le debug
        SplineDumper.dumpSpline(spline, 0);

        return new GpuNode.Spline(coordinate, newId, RANGE_MIN, RANGE_MAX);
    }

    /**
     * Cuit la spline sur l'intervalle [-2.0, 2.0] pour respecter les tangentes de Mojang.
     */
    private static float[] bake(Object spline, int id) {
        float[] lut = new float[RESOLUTION];
        float step = (RANGE_MAX - RANGE_MIN) / (RESOLUTION - 1);

        for (int i = 0; i < RESOLUTION; i++) {
            float input = RANGE_MIN + (i * step);
            lut[i] = evaluateVanillaSpline(spline, input);
        }

        System.out.println("[SplineRegistry] Cuisson OK - ID: " + id + " | Points [0]=" + lut[0] + ", [512]=" + lut[512] + ", [1023]=" + lut[1023]);
        return lut;
    }

    /**
     * Évalue la spline via réflexion. 
     * Minecraft 1.21 utilise des structures complexes, nous injectons la valeur directement.
     */
    private static float evaluateVanillaSpline(Object spline, float input) {
        if (spline == null) return 0.0f;
        String className = spline.getClass().getName();

        try {
            // 1. Gestion des Constantes (CubicSpline.Constant)
            if (className.contains("$Constant")) {
                try {
                    // record Constant<C, I>(float value)
                    return ((Number) getField(spline, "value")).floatValue();
                } catch (Exception e) {
                    // Cas d'obfuscation ou autre nom de champ (ex: 'c' ou 'value')
                    return ((Number) getField(spline, "c")).floatValue();
                }
            }

            // 2. Gestion des Points Multiples (CubicSpline.Multipoint)
            if (className.contains("$Multipoint")) {
                return evaluateViaHermite(spline, input);
            }

            // 3. Fallback pour constantes primitives directes (si rencontrées)
            if (spline instanceof Number n) return n.floatValue();

            System.err.println("[SplineRegistry] Type de spline inconnu : " + className);
            return 0.0f;

        } catch (Exception e) {
            System.err.println("[SplineRegistry] ERREUR FATALE de cuisson pour " + className + " à l'input " + input);
            e.printStackTrace();
            throw new RuntimeException("Échec de la cuisson de la spline : " + className, e);
        }
    }

    private static float evaluateViaHermite(Object spline, float x) throws Exception {
        // Extraction robuste (float[] ou List<Number> selon l'implémentation du Record)
        float[] locations = toFloatArray(getField(spline, "locations"));
        List<?> values = (List<?>) getField(spline, "values");
        float[] derivatives = toFloatArray(getField(spline, "derivatives"));

        if (locations == null || values == null || derivatives == null || locations.length == 0) {
            throw new IllegalStateException("Spline Multipoint incomplète ou invalide (Locations/Values/Derivatives manquants).");
        }

        int n = locations.length;
        // 1. Clamping aux bornes (Extrapolation linéaire via tangentes)
        if (x <= locations[0]) {
            return evaluateVanillaSpline(values.get(0), x) + derivatives[0] * (x - locations[0]);
        }
        if (x >= locations[n - 1]) {
            return evaluateVanillaSpline(values.get(n - 1), x) + derivatives[n - 1] * (x - locations[n - 1]);
        }

        // 2. Recherche de l'intervalle [i, i+1]
        int i = 0;
        while (i < n - 1 && x > locations[i + 1]) {
            i++;
        }

        // 3. Interpolation Cubique d'Hermite (Validation Mathématique Mojang)
        float x0 = locations[i];
        float x1 = locations[i + 1];
        float h = x1 - x0;
        float t = (x - x0) / h;

        // On récurse si l'élément de 'values' est lui-même une spline
        float y0 = evaluateVanillaSpline(values.get(i), x);
        float y1 = evaluateVanillaSpline(values.get(i + 1), x);

        float d0 = derivatives[i];
        float d1 = derivatives[i + 1];

        float a = d0 * h - (y1 - y0);
        float b = -d1 * h + (y1 - y0);

        // Formule: lerp(t, y0, y1) + t * (1-t) * lerp(t, a, b)
        return (y0 + t * (y1 - y0)) + t * (1.0f - t) * (a + t * (b - a));
    }

    private static float[] toFloatArray(Object obj) {
        if (obj == null) return null;
        if (obj instanceof float[] f) return f;
        if (obj instanceof List<?> l) {
            float[] res = new float[l.size()];
            for (int i = 0; i < l.size(); i++) res[i] = ((Number) l.get(i)).floatValue();
            return res;
        }
        if (obj instanceof double[] d) {
            float[] res = new float[d.length];
            for (int i = 0; i < d.length; i++) res[i] = (float) d[i];
            return res;
        }
        return null;
    }

    private static Object getField(Object obj, String name) throws Exception {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Exception e) {
            // Record component
            Method m = obj.getClass().getDeclaredMethod(name);
            m.setAccessible(true);
            return m.invoke(obj);
        }
    }

    public static List<BakedSplineData> getRegisteredSplines() {
        return REGISTERED_SPLINES;
    }

    public static void clear() {
        SPLINE_TO_ID.clear();
        REGISTERED_SPLINES.clear();
    }
}
