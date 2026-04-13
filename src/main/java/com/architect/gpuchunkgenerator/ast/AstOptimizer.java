package com.architect.gpuchunkgenerator.ast;

import com.architect.gpuchunkgenerator.ast.ir.GpuNode;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Optimiseur d'AST : Transforme les DensityFunctions vanilla en GpuNodes.
 * Supporte l'intégralité des types de nœuds de Minecraft 1.21.1 (Mojmap).
 * 
 * Nœuds gérés :
 *  - Constantes : Constant
 *  - Binaires : Ap2 (ADD, MUL, MIN, MAX), MulOrAdd, Clamp
 *  - Unaires : Mapped (ABS, SQUARE, CUBE, SQUEEZE, HALF_NEGATIVE, QUARTER_NEGATIVE)
 *  - Bruit : Noise, ShiftedNoise, Shift, ShiftA, ShiftB
 *  - Splines : Spline (CubicSpline)
 *  - Spécialisés : YClampedGradient, RangeChoice, WeirdScaledSampler
 *  - Wrappers : HolderHolder, Marker, BlendDensity, BlendAlpha, BlendOffset
 *  - Caches : Interpolated, FlatCache, CacheAllInCell, Cache2D, CacheOnce, NoiseInterpolator
 */
public class AstOptimizer {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static int depth = 0;
    private static int unknownCount = 0;
    private static final Map<Object, GpuNode> FORCED_MAPPINGS = new IdentityHashMap<>();

    /**
     * Enregistre un mapping forcé (ex: une instance de DensityFunction -> GpuNode.Reference).
     * Utilise l'identité mémoire de l'objet source.
     */
    public static void registerForcedMapping(Object vanillaObj, GpuNode node) {
        if (vanillaObj != null && !FORCED_MAPPINGS.containsKey(vanillaObj)) {
            FORCED_MAPPINGS.put(vanillaObj, node);
            LOGGER.info("Mapping forcé enregistré pour {}", vanillaObj.getClass().getSimpleName());
        }
    }

    public static void clearForcedMappings() {
        FORCED_MAPPINGS.clear();
    }

    public static GpuNode optimize(DensityFunction function) {
        depth = 0;
        unknownCount = 0;
        GpuNode result = visit(function);
        if (unknownCount > 0) {
            LOGGER.warn("⚠ {} nœud(s) non reconnus émis comme GpuNode.Unknown", unknownCount);
        }
        return result;
    }

    private static GpuNode visit(Object obj) {
        if (obj == null) return new GpuNode.Constant(0.0);
        if (depth++ > 512) {
            depth--;
            return new GpuNode.Unknown("StackOverflowGuard");
        }
        try {
            return visitInternal(obj);
        } finally {
            depth--;
        }
    }

    private static GpuNode visitInternal(Object obj) {
        Object current = obj;
        String className = current.getClass().getSimpleName();

        // --- 1. Déballage des Wrappers (Traversal pur) ---
        while (className.equals("Marker") || className.equals("BlendDensity") || 
               className.equals("Slide") || current instanceof Holder || 
               className.equals("HolderHolder")) {
            
            if (current instanceof Holder<?> holder) {
                if (!holder.isBound()) return new GpuNode.Unknown("UnboundHolder");
                current = holder.value();
            } else if (className.equals("Marker")) {
                current = invokeSafe(current, "wrapped");
            } else if (className.equals("BlendDensity") || className.equals("Slide")) {
                current = invokeSafe(current, "input");
            } else if (className.equals("HolderHolder")) {
                Object h = invokeSafe(current, "function");
                if (h instanceof Holder<?> holder && holder.isBound()) {
                    current = holder.value();
                } else {
                    current = h;
                }
            }
            if (current == null) return new GpuNode.Constant(0.0);
            className = current.getClass().getSimpleName();
        }

        // --- 2. Priorité aux mappings forcés (Identité sur l'objet déballé) ---
        if (FORCED_MAPPINGS.containsKey(current)) {
            LOGGER.info("Mapping forcé utilisé pour {}", className);
            return FORCED_MAPPINGS.get(current);
        }

        // --- 3. Gestion des caches et bruits (Passage à travers) ---
        if (className.equals("FlatCache") || className.equals("CacheAllInCell") ||
            className.equals("Cache2D") || className.equals("CacheOnce") ||
            className.equals("NoiseInterpolator")) {
            Object inner = invokeSafe(current, "wrapped");
            if (inner == null) inner = invokeSafe(current, "input");
            if (inner == null) inner = findFieldByType(current, DensityFunction.class);
            if (inner != null) return visit(inner);
            return new GpuNode.Unknown(className + "_NoInner");
        }

        // BlendAlpha et BlendOffset — constantes
        if (className.equals("BlendAlpha")) return new GpuNode.Constant(1.0);
        if (className.equals("BlendOffset")) return new GpuNode.Constant(0.0);

        try {
            // ============================================================
            // PHASE 2 : Nœuds terminaux (feuilles)
            // ============================================================

            // Constant (DensityFunction ou CubicSpline)
            if (className.equals("Constant") || className.contains("$Constant")) {
                Object valObj = invokeSafe(current, "value");
                if (valObj == null) valObj = invokeSafe(current, "c");
                return new GpuNode.Constant(((Number) valObj).doubleValue());
            }

            // Multipoint (CubicSpline interne)
            if (className.equals("Multipoint") || className.contains("$Multipoint")) {
                return extractSplineInternal(current);
            }

            // YClampedGradient
            if (className.equals("YClampedGradient")) {
                return new GpuNode.YClampedGradient(
                    (int) invokeSafe(current, "fromY"),
                    (int) invokeSafe(current, "toY"),
                    (double) invokeSafe(current, "fromValue"),
                    (double) invokeSafe(current, "toValue")
                );
            }

            // ============================================================
            // PHASE 3 : Nœuds de bruit (avec extraction NoiseExtractor)
            // ============================================================

            // Noise → GpuNode.Noise
            if (className.equals("Noise")) {
                return extractNoise(current);
            }

            // ShiftedNoise → GpuNode.ShiftedNoise
            if (className.equals("ShiftedNoise")) {
                return extractShiftedNoise(current);
            }

            // Shift / ShiftA / ShiftB → GpuNode.ShiftNoise
            if (className.equals("Shift")) {
                return extractShiftNoise(current, GpuNode.ShiftType.SHIFT_XYZ);
            }
            if (className.equals("ShiftA")) {
                return extractShiftNoise(current, GpuNode.ShiftType.SHIFT_A);
            }
            if (className.equals("ShiftB")) {
                return extractShiftNoise(current, GpuNode.ShiftType.SHIFT_B);
            }

            // ============================================================
            // PHASE 4 : Opérations mathématiques
            // ============================================================

            // Ap2 (Binary: ADD, MUL, MIN, MAX)
            if (className.equals("Ap2")) {
                String type = invokeSafe(current, "type").toString();
                GpuNode left = visit(invokeSafe(current, "argument1"));
                GpuNode right = visit(invokeSafe(current, "argument2"));
                return foldBinary(type, left, right);
            }

            // MulOrAdd (input OP argument)
            if (className.equals("MulOrAdd")) {
                String type = invokeSafe(current, "specificType").toString();
                GpuNode input = visit(invokeSafe(current, "input"));
                double argValue = (double) invokeSafe(current, "argument");
                return foldBinary(type, input, new GpuNode.Constant(argValue));
            }

            // Mapped (Unary: ABS, SQUARE, CUBE, SQUEEZE, HALF_NEGATIVE, QUARTER_NEGATIVE)
            if (className.equals("Mapped")) {
                String type = invokeSafe(current, "type").toString();
                GpuNode input = visit(invokeSafe(current, "input"));
                return foldUnary(type, input);
            }

            // Clamp
            if (className.equals("Clamp")) {
                GpuNode input = visit(invokeSafe(current, "input"));
                double min = (double) invokeSafe(current, "minValue");
                double max = (double) invokeSafe(current, "maxValue");
                return new GpuNode.BinaryOp("CLAMP", input,
                    new GpuNode.BinaryOp("RANGE", new GpuNode.Constant(min), new GpuNode.Constant(max)));
            }

            // ============================================================
            // PHASE 5 : Nœuds composites
            // ============================================================

            // Spline → GpuNode.Spline (via SplineRegistry)
            if (className.equals("Spline")) {
                return extractSpline(current);
            }

            // RangeChoice
            if (className.equals("RangeChoice")) {
                GpuNode input = visit(invokeSafe(current, "input"));
                double minInc = (double) invokeSafe(current, "minInclusive");
                double maxExc = (double) invokeSafe(current, "maxExclusive");
                GpuNode whenInRange = visit(invokeSafe(current, "whenInRange"));
                GpuNode whenOutOfRange = visit(invokeSafe(current, "whenOutOfRange"));
                return new GpuNode.RangeChoice(input, minInc, maxExc, whenInRange, whenOutOfRange);
            }

            // WeirdScaledSampler
            if (className.equals("WeirdScaledSampler")) {
                return extractWeirdScaledSampler(current);
            }

            // BlendedNoise
        if (className.equals("BlendedNoise") || className.endsWith(".BlendedNoise")) {
                return new GpuNode.BlendedNoise();
            }

        } catch (Exception e) {
            LOGGER.error("Erreur lors du traitement de {}: {}", className, e.getMessage());
            return new GpuNode.Unknown(className + "_Error");
        }

        // Nœud non reconnu
        unknownCount++;
        LOGGER.error("TYPE NON GÉRÉ: {} → 0.0f", className);
        LOGGER.error("Nœud inconnu rencontré : {} | Dump : {}", className, current.toString());
        return new GpuNode.Unknown(className);
    }

    // ================================================================
    // Extracteurs spécialisés
    // ================================================================

    private static GpuNode extractNoise(Object obj) {
        Object noiseHolder = invokeSafe(obj, "noise");
        NormalNoise sampler = extractSamplerFromHolder(noiseHolder);
        String name = getNoiseName(noiseHolder);
        double xzScale = (double) invokeSafe(obj, "xzScale");
        double yScale = (double) invokeSafe(obj, "yScale");

        if (sampler != null) {
            return NoiseExtractor.extract(name, sampler, xzScale, yScale);
        }
        return new GpuNode.Noise(name, xzScale, yScale, 1.0f, null, null, -1);
    }

    private static GpuNode extractShiftedNoise(Object obj) {
        // Les 3 shifts sont eux-mêmes des DensityFunctions (souvent ShiftA/ShiftB)
        GpuNode shiftX = visit(invokeSafe(obj, "shiftX"));
        GpuNode shiftY = visit(invokeSafe(obj, "shiftY"));
        GpuNode shiftZ = visit(invokeSafe(obj, "shiftZ"));

        double xzScale = (double) invokeSafe(obj, "xzScale");
        double yScale = (double) invokeSafe(obj, "yScale");

        // Extraire le bruit interne
        Object noiseHolder = invokeSafe(obj, "noise");
        NormalNoise sampler = extractSamplerFromHolder(noiseHolder);
        int bufferOffset = -1;

        if (sampler != null) {
            String name = getNoiseName(noiseHolder);
            GpuNode.Noise noiseNode = NoiseExtractor.extract(name, sampler, 1.0, 1.0);
            bufferOffset = noiseNode.bufferOffset();
        }

        return new GpuNode.ShiftedNoise(shiftX, shiftY, shiftZ, xzScale, yScale, bufferOffset);
    }

    private static GpuNode extractShiftNoise(Object obj, GpuNode.ShiftType type) {
        Object noiseHolder = invokeSafe(obj, "offsetNoise");
        NormalNoise sampler = extractSamplerFromHolder(noiseHolder);
        int bufferOffset = -1;

        if (sampler != null) {
            String name = getNoiseName(noiseHolder) + "_" + type.name().toLowerCase();
            GpuNode.Noise noiseNode = NoiseExtractor.extract(name, sampler, 1.0, 1.0);
            bufferOffset = noiseNode.bufferOffset();
        }

        return new GpuNode.ShiftNoise(type, bufferOffset);
    }

    private static GpuNode extractWeirdScaledSampler(Object obj) {
        GpuNode input = visit(invokeSafe(obj, "input"));

        Object noiseHolder = invokeSafe(obj, "noise");
        NormalNoise sampler = extractSamplerFromHolder(noiseHolder);
        int bufferOffset = -1;

        if (sampler != null) {
            String name = getNoiseName(noiseHolder) + "_weird";
            GpuNode.Noise noiseNode = NoiseExtractor.extract(name, sampler, 1.0, 1.0);
            bufferOffset = noiseNode.bufferOffset();
        }

        // Déterminer le type de mapper
        Object mapper = invokeSafe(obj, "rarityValueMapper");
        GpuNode.RarityMapper rarityMapper = GpuNode.RarityMapper.TYPE1;
        if (mapper != null) {
            String mapperStr = mapper.toString();
            if (mapperStr.contains("TYPE2") || mapperStr.contains("type_2")) {
                rarityMapper = GpuNode.RarityMapper.TYPE2;
            }
        }

        return new GpuNode.WeirdScaledSampler(input, bufferOffset, rarityMapper);
    }

    private static GpuNode extractSpline(Object obj) {
        Object internalSpline = invokeSafe(obj, "spline");
        if (internalSpline == null) return new GpuNode.Constant(0.0);

        String className = internalSpline.getClass().getName();

        // 1. CubicSpline.Constant
        if (className.contains("$Constant")) {
            try {
                double val = ((Number) invokeSafe(internalSpline, "value")).doubleValue();
                return new GpuNode.Constant(val);
            } catch (Exception e) {
                try {
                    double val = ((Number) invokeSafe(internalSpline, "c")).doubleValue();
                    return new GpuNode.Constant(val);
                } catch (Exception e2) {
                    return new GpuNode.Constant(0.0);
                }
            }
        }

        // 2. CubicSpline.Multipoint
        if (className.contains("$Multipoint")) {
            try {
                // A. Coordonnée d'entrée
                GpuNode coordinateNode = extractSplineCoordinate(internalSpline);

                // B. Données d'interpolation
                float[] locations = toFloatArray(invokeSafe(internalSpline, "locations"));
                List<?> valuesList = (List<?>) invokeSafe(internalSpline, "values");
                float[] derivatives = toFloatArray(invokeSafe(internalSpline, "derivatives"));

                if (locations == null || valuesList == null || derivatives == null) {
                    return new GpuNode.Constant(0.0);
                }

                // C. Expansion récursive des valeurs (Poupées Russes)
                GpuNode[] recursiveValues = new GpuNode[valuesList.size()];
                for (int i = 0; i < valuesList.size(); i++) {
                    recursiveValues[i] = visit(valuesList.get(i));
                }

                // D. Création du nœud d'interpolation
                GpuNode.HermiteInterpolation node = new GpuNode.HermiteInterpolation(
                    coordinateNode,
                    locations,
                    recursiveValues,
                    derivatives,
                    -1 // ID sera assigné par le registre
                );

                // E. Enregistrement pour génération de fonction GLSL
                int splineId = SplineRegistry.registerSplineFunction(internalSpline, node);
                
                // On retourne un nœud avec l'ID final
                return new GpuNode.HermiteInterpolation(
                    coordinateNode,
                    locations,
                    recursiveValues,
                    derivatives,
                    splineId
                );

            } catch (Exception e) {
                System.err.println("[AstOptimizer] Erreur extraction Multipoint: " + e.getMessage());
                return new GpuNode.Unknown("Multipoint_Error");
            }
        }

        return new GpuNode.Unknown("UnknownSpline_" + className);
    }

    private static GpuNode extractSplineCoordinate(Object spline) {
        Object coordWrapper = invokeSafe(spline, "coordinate");
        if (coordWrapper == null) {
            coordWrapper = invokeSafe(spline, "coordinateFunction");
        }
        
        if (coordWrapper == null) return new GpuNode.Constant(0.0);

        if (coordWrapper instanceof DensityFunction df) {
            return visit(df);
        } else {
            Object holder = invokeSafe(coordWrapper, "function");
            if (holder == null) {
                holder = findFieldByType(coordWrapper, DensityFunction.class);
            }

            if (holder instanceof Holder<?> h && h.isBound()) {
                return visit(h.value());
            } else if (holder != null) {
                return visit(holder);
            } else {
                return visit(coordWrapper);
            }
        }
    }

    private static float[] toFloatArray(Object obj) {
        if (obj == null) return null;
        if (obj instanceof float[] f) return f;
        if (obj instanceof List<?> l) {
            float[] res = new float[l.size()];
            for (int i = 0; i < l.size(); i++) res[i] = ((Number) l.get(i)).floatValue();
            return res;
        }
        return null;
    }

    // ================================================================
    // Constant Folding
    // ================================================================

    private static GpuNode foldBinary(String op, GpuNode left, GpuNode right) {
        if (left instanceof GpuNode.Constant c1 && right instanceof GpuNode.Constant c2) {
            double v1 = c1.value();
            double v2 = c2.value();
            return switch (op) {
                case "ADD" -> new GpuNode.Constant(v1 + v2);
                case "MUL" -> new GpuNode.Constant(v1 * v2);
                case "MIN" -> new GpuNode.Constant(Math.min(v1, v2));
                case "MAX" -> new GpuNode.Constant(Math.max(v1, v2));
                default -> new GpuNode.BinaryOp(op, left, right);
            };
        }
        return new GpuNode.BinaryOp(op, left, right);
    }

    private static GpuNode foldUnary(String op, GpuNode input) {
        if (input instanceof GpuNode.Constant c) {
            double v = c.value();
            return switch (op) {
                case "ABS" -> new GpuNode.Constant(Math.abs(v));
                case "SQUARE" -> new GpuNode.Constant(v * v);
                case "CUBE" -> new GpuNode.Constant(v * v * v);
                case "HALF_NEGATIVE" -> new GpuNode.Constant(v > 0 ? v : v * 0.5);
                case "QUARTER_NEGATIVE" -> new GpuNode.Constant(v > 0 ? v : v * 0.25);
                case "SQUEEZE" -> {
                    double f = Math.max(-1.0, Math.min(1.0, v));
                    yield new GpuNode.Constant((f / 2.0) - (f * f * f) / 24.0);
                }
                default -> new GpuNode.UnaryOp(op, input);
            };
        }
        return new GpuNode.UnaryOp(op, input);
    }

    // ================================================================
    // Utilitaires de réflexion
    // ================================================================

    private static NormalNoise extractSamplerFromHolder(Object noiseHolder) {
        if (noiseHolder == null) return null;
        try {
            // Essayer getValue() (NeoForge NoiseHolder)
            Method m = noiseHolder.getClass().getDeclaredMethod("getValue", double.class, double.class, double.class);
            // C'est un NoiseHolder — extraire le sampler interne
            Object internalNoise = findFieldByType(noiseHolder, NormalNoise.class);
            if (internalNoise instanceof NormalNoise nn) return nn;
        } catch (NoSuchMethodException ignored) {}

        try {
            // Essayer value() simple (Holder pattern)
            Method m = noiseHolder.getClass().getDeclaredMethod("value");
            m.setAccessible(true);
            Object val = m.invoke(noiseHolder);
            if (val instanceof NormalNoise nn) return nn;
        } catch (Exception ignored) {}

        // Fallback : chercher un champ NormalNoise par type
        Object found = findFieldByType(noiseHolder, NormalNoise.class);
        return found instanceof NormalNoise nn ? nn : null;
    }

    private static Object findFieldByType(Object obj, Class<?> type) {
        if (obj == null) return null;
        Class<?> current = obj.getClass();
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                if (type.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        return f.get(obj);
                    } catch (Exception ignored) {}
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Object invokeSafe(Object obj, String methodName) {
        if (obj == null) return null;
        try {
            Method m = obj.getClass().getDeclaredMethod(methodName);
            m.setAccessible(true);
            return m.invoke(obj);
        } catch (Exception e) {
            try {
                Method m = obj.getClass().getMethod(methodName);
                m.setAccessible(true);
                return m.invoke(obj);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static String getNoiseName(Object noiseHolder) {
        if (noiseHolder == null) return "unknown";
        try {
            // NeoForge: DensityFunction.NoiseHolder has noiseData() -> Holder<NormalNoise.NoiseData>
            Method m = noiseHolder.getClass().getDeclaredMethod("noiseData");
            m.setAccessible(true);
            Object holder = m.invoke(noiseHolder);
            Method unwrap = holder.getClass().getMethod("unwrapKey");
            Optional<?> key = (Optional<?>) unwrap.invoke(holder);
            return key.map(Object::toString).orElse("internal");
        } catch (Exception e) {
            return "unknown";
        }
    }
    /**
     * Extrait un Multipoint CubicSpline qui n'est pas enveloppé dans une DensityFunction.Spline.
     * Utilisé pour la récursion profonde des splines.
     */
    private static GpuNode extractSplineInternal(Object internalSpline) {
        try {
            GpuNode coordinateNode = extractSplineCoordinate(internalSpline);
            float[] locations = toFloatArray(invokeSafe(internalSpline, "locations"));
            List<?> valuesList = (List<?>) invokeSafe(internalSpline, "values");
            float[] derivatives = toFloatArray(invokeSafe(internalSpline, "derivatives"));

            if (locations == null || valuesList == null || derivatives == null) {
                return new GpuNode.Constant(0.0);
            }

            GpuNode[] recursiveValues = new GpuNode[valuesList.size()];
            for (int i = 0; i < valuesList.size(); i++) {
                recursiveValues[i] = visit(valuesList.get(i));
            }

            GpuNode.HermiteInterpolation node = new GpuNode.HermiteInterpolation(
                coordinateNode, locations, recursiveValues, derivatives, -1
            );

            int splineId = SplineRegistry.registerSplineFunction(internalSpline, node);
            return new GpuNode.HermiteInterpolation(
                coordinateNode, locations, recursiveValues, derivatives, splineId
            );
        } catch (Exception e) {
            return new GpuNode.Unknown("SplineInternal_Error");
        }
    }
}
