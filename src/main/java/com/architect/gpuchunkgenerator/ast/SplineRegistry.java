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
        float[] bakedLut = bake(spline);
        REGISTERED_SPLINES.add(new BakedSplineData(newId, bakedLut, RANGE_MIN, RANGE_MAX));

        // Logging et vérification des valeurs cuites
        float minVal = Float.MAX_VALUE;
        float maxVal = -Float.MAX_VALUE;
        boolean allZeros = true;
        for (float v : bakedLut) {
            if (v < minVal) minVal = v;
            if (v > maxVal) maxVal = v;
            if (v != 0.0f) allZeros = false;
        }

        System.out.println("[SplineRegistry] Spline #" + newId + " : min=" + bakedLut[0] + " max=" + bakedLut[RESOLUTION - 1] +
            " range=[" + minVal + ", " + maxVal + "]" + (allZeros ? " ⚠️ ATTENTION: TOTALEMENT NULLE!" : ""));

        // Dump de l'anatomie pour le debug
        SplineDumper.dumpSpline(spline, 0);

        // Dump dans un fichier
        try {
            java.nio.file.Files.writeString(
                java.nio.file.Paths.get("spline_baked_dump.txt"),
                "Spline #" + newId + " min=" + minVal + " max=" + maxVal + "\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND
            );
        } catch (Exception e) {}

        return new GpuNode.Spline(coordinate, newId, RANGE_MIN, RANGE_MAX);
    }

    /**
     * Cuit la spline sur l'intervalle [-2.0, 2.0] pour respecter les tangentes de Mojang.
     */
    private static float[] bake(Object spline) {
        float[] lut = new float[RESOLUTION];
        float step = (RANGE_MAX - RANGE_MIN) / (RESOLUTION - 1);

        for (int i = 0; i < RESOLUTION; i++) {
            float input = RANGE_MIN + (i * step);
            lut[i] = evaluateVanillaSpline(spline, input);
        }
        return lut;
    }

    /**
     * Évalue la spline via réflexion. 
     * Minecraft 1.21 utilise des structures complexes, nous injectons la valeur directement.
     */
    private static float evaluateVanillaSpline(Object spline, float input) {
        try {
            // Dans CubicSpline.Multipoint, la méthode apply(C context) appelle coordinate.apply(context).
            // Pour cuire, on doit simuler ou contourner le contexte.
            // On tente d'appeler directement la méthode de calcul interne si elle existe, 
            // sinon on simule un contexte minimal.
            
            String className = spline.getClass().getSimpleName();
            
            if (className.contains("Constant")) {
                Method m = spline.getClass().getDeclaredMethod("value");
                m.setAccessible(true);
                return ((Number) m.invoke(spline)).floatValue();
            }

            // Pour Multipoint, on utilise la logique Mojang : 
            // On cherche la méthode calculate(float) ou équivalent.
            // En 1.21.1, CubicSpline est souvent un Record ou une classe avec des champs locations/values.
            // Sécurité : Si on ne peut pas évaluer proprement, on renvoie 0.0f (à logger en debug)
            
            // Approche robuste : On tente de trouver une méthode qui prend un float ou qui calcule l'interpolation.
            // Comme le baking est complexe, on va s'appuyer sur le fait que CubicSpline possède une interface
            // ToFloatFunction. On va mocker le contexte si nécessaire.
            
            return evaluateViaHermite(spline, input);
            
        } catch (Exception e) {
            return 0.0f;
        }
    }

    private static float evaluateViaHermite(Object spline, float x) throws Exception {
        // Extraction des données pour calcul manuel si apply() est trop complexe à mocker
        float[] locations = (float[]) getField(spline, "locations");
        List<?> values = (List<?>) getField(spline, "values");
        float[] derivatives = (float[]) getField(spline, "derivatives");
        
        if (locations == null || values == null || derivatives == null) return 0.0f;

        // 1. Clamping aux bornes (Extrapolation linéaire via tangentes)
        if (x <= locations[0]) {
            return (float) evaluateValue(values.get(0), x) + derivatives[0] * (x - locations[0]);
        }
        int n = locations.length;
        if (x >= locations[n - 1]) {
            return (float) evaluateValue(values.get(n - 1), x) + derivatives[n - 1] * (x - locations[n - 1]);
        }

        // 2. Recherche de l'intervalle [i, i+1]
        int i = 0;
        while (i < n - 1 && x > locations[i + 1]) {
            i++;
        }

        // 3. Interpolation Cubique d'Hermite (Maths exactes de Mojang)
        float x0 = locations[i];
        float x1 = locations[i + 1];
        float h = x1 - x0;
        float t = (x - x0) / h;

        float y0 = evaluateValue(values.get(i), x);
        float y1 = evaluateValue(values.get(i + 1), x);
        float d0 = derivatives[i];
        float d1 = derivatives[i + 1];

        float a = d0 * h - (y1 - y0);
        float b = -d1 * h + (y1 - y0);
        
        // Formule: lerp(t, y0, y1) + t * (1-t) * lerp(t, a, b)
        return (y0 + t * (y1 - y0)) + t * (1.0f - t) * (a + t * (b - a));
    }

    private static float evaluateValue(Object val, float x) throws Exception {
        if (val instanceof Number n) return n.floatValue();
        // Si c'est une spline imbriquée, on récurse la cuisson
        return evaluateViaHermite(val, x);
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
