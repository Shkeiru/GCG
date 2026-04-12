package com.architect.gpuchunkgenerator.ast;

import com.architect.gpuchunkgenerator.ast.ir.GpuNode;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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

    private static int depth = 0;
    private static int unknownCount = 0;

    public static GpuNode optimize(DensityFunction function) {
        depth = 0;
        unknownCount = 0;
        GpuNode result = visit(function);
        if (unknownCount > 0) {
            System.err.println("[GCG-AstOptimizer] ⚠ " + unknownCount + " nœud(s) non reconnus émis comme GpuNode.Unknown");
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
        // --- Holder unwrapping ---
        if (obj instanceof Holder<?> holder) {
            return holder.isBound() ? visit(holder.value()) : new GpuNode.Unknown("UnboundHolder");
        }

        String className = obj.getClass().getSimpleName();

        // ============================================================
        // PHASE 1 : Wrappers transparents (traversal pur, aucun nœud émis)
        // ============================================================

        // HolderHolder → function().value()
        if (className.equals("HolderHolder")) {
            Object holder = invokeSafe(obj, "function");
            if (holder instanceof Holder<?> h && h.isBound()) {
                return visit(h.value());
            }
            return visit(holder);
        }

        // Marker → wrapped()
        if (className.equals("Marker")) {
            return visit(invokeSafe(obj, "wrapped"));
        }

        // BlendDensity → input()
        if (className.equals("BlendDensity")) {
            return visit(invokeSafe(obj, "input"));
        }

        // Caches persistants (runtime NoiseChunk) — tous traversent vers le contenu
        if (className.equals("FlatCache") || className.equals("CacheAllInCell") ||
            className.equals("Cache2D") || className.equals("CacheOnce") ||
            className.equals("NoiseInterpolator")) {
            // Essayer 'wrapped' d'abord, puis 'input', puis chercher un champ DensityFunction
            Object inner = invokeSafe(obj, "wrapped");
            if (inner == null) inner = invokeSafe(obj, "input");
            if (inner == null) inner = findFieldByType(obj, DensityFunction.class);
            if (inner != null) return visit(inner);
            return new GpuNode.Unknown(className + "_NoInner");
        }

        // Slide → input()
        if (className.equals("Slide")) {
            return visit(invokeSafe(obj, "input"));
        }

        // BlendAlpha et BlendOffset — constantes
        if (className.equals("BlendAlpha")) return new GpuNode.Constant(1.0);
        if (className.equals("BlendOffset")) return new GpuNode.Constant(0.0);

        try {
            // ============================================================
            // PHASE 2 : Nœuds terminaux (feuilles)
            // ============================================================

            // Constant
            if (className.equals("Constant")) {
                return new GpuNode.Constant((double) invokeSafe(obj, "value"));
            }

            // YClampedGradient
            if (className.equals("YClampedGradient")) {
                return new GpuNode.YClampedGradient(
                    (int) invokeSafe(obj, "fromY"),
                    (int) invokeSafe(obj, "toY"),
                    (double) invokeSafe(obj, "fromValue"),
                    (double) invokeSafe(obj, "toValue")
                );
            }

            // ============================================================
            // PHASE 3 : Nœuds de bruit (avec extraction NoiseExtractor)
            // ============================================================

            // Noise → GpuNode.Noise
            if (className.equals("Noise")) {
                return extractNoise(obj);
            }

            // ShiftedNoise → GpuNode.ShiftedNoise
            if (className.equals("ShiftedNoise")) {
                return extractShiftedNoise(obj);
            }

            // Shift / ShiftA / ShiftB → GpuNode.ShiftNoise
            if (className.equals("Shift")) {
                return extractShiftNoise(obj, GpuNode.ShiftType.SHIFT_XYZ);
            }
            if (className.equals("ShiftA")) {
                return extractShiftNoise(obj, GpuNode.ShiftType.SHIFT_A);
            }
            if (className.equals("ShiftB")) {
                return extractShiftNoise(obj, GpuNode.ShiftType.SHIFT_B);
            }

            // ============================================================
            // PHASE 4 : Opérations mathématiques
            // ============================================================

            // Ap2 (Binary: ADD, MUL, MIN, MAX)
            if (className.equals("Ap2")) {
                String type = invokeSafe(obj, "type").toString();
                GpuNode left = visit(invokeSafe(obj, "argument1"));
                GpuNode right = visit(invokeSafe(obj, "argument2"));
                return foldBinary(type, left, right);
            }

            // MulOrAdd (input OP argument)
            if (className.equals("MulOrAdd")) {
                String type = invokeSafe(obj, "specificType").toString();
                GpuNode input = visit(invokeSafe(obj, "input"));
                double argValue = (double) invokeSafe(obj, "argument");
                return foldBinary(type, input, new GpuNode.Constant(argValue));
            }

            // Mapped (Unary: ABS, SQUARE, CUBE, SQUEEZE, HALF_NEGATIVE, QUARTER_NEGATIVE)
            if (className.equals("Mapped")) {
                String type = invokeSafe(obj, "type").toString();
                GpuNode input = visit(invokeSafe(obj, "input"));
                return foldUnary(type, input);
            }

            // Clamp
            if (className.equals("Clamp")) {
                GpuNode input = visit(invokeSafe(obj, "input"));
                double min = (double) invokeSafe(obj, "minValue");
                double max = (double) invokeSafe(obj, "maxValue");
                return new GpuNode.BinaryOp("CLAMP", input,
                    new GpuNode.BinaryOp("RANGE", new GpuNode.Constant(min), new GpuNode.Constant(max)));
            }

            // ============================================================
            // PHASE 5 : Nœuds composites
            // ============================================================

            // Spline → GpuNode.Spline (via SplineRegistry)
            if (className.equals("Spline")) {
                return extractSpline(obj);
            }

            // RangeChoice
            if (className.equals("RangeChoice")) {
                GpuNode input = visit(invokeSafe(obj, "input"));
                double minInc = (double) invokeSafe(obj, "minInclusive");
                double maxExc = (double) invokeSafe(obj, "maxExclusive");
                GpuNode whenInRange = visit(invokeSafe(obj, "whenInRange"));
                GpuNode whenOutOfRange = visit(invokeSafe(obj, "whenOutOfRange"));
                return new GpuNode.RangeChoice(input, minInc, maxExc, whenInRange, whenOutOfRange);
            }

            // WeirdScaledSampler
            if (className.equals("WeirdScaledSampler")) {
                return extractWeirdScaledSampler(obj);
            }

            // BlendedNoise
            if (className.equals("BlendedNoise") || className.endsWith(".BlendedNoise")) {
                return new GpuNode.BlendedNoise();
            }

        } catch (Exception e) {
            System.err.println("[GCG-AstOptimizer] Erreur lors du traitement de " + className + ": " + e.getMessage());
            return new GpuNode.Unknown(className + "_Error");
        }

        // Nœud non reconnu
        unknownCount++;
        System.err.println("[AstOptimizer] TYPE NON GÉRÉ: " + className + " → 0.0f");
        System.err.println("[GCG-CRITICAL] Nœud inconnu rencontré : " + className + " | Dump : " + obj.toString());
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

        GpuNode coordinateNode = new GpuNode.Constant(0.0); // Fallback
        
        // Tenter d'extraire le coordinate depuis l'objet Spline principal s'il existe
        Object coordWrapper = invokeSafe(obj, "coordinate");

        if (coordWrapper == null && internalSpline != null) {
            // Sinon tenter d'extraire le coordinate depuis la spline interne
            coordWrapper = invokeSafe(internalSpline, "coordinate");
            if (coordWrapper == null) {
                // Essayer via la méthode coordinateFunction() ou locationFunction()
                coordWrapper = invokeSafe(internalSpline, "coordinateFunction");
            }
        }

        if (coordWrapper != null) {
            // Si coordWrapper est directement un DensityFunction
            if (coordWrapper instanceof DensityFunction df) {
                coordinateNode = visit(df);
            } else {
                // Le coordinate wrapper contient un Holder<DensityFunction>
                Object holder = invokeSafe(coordWrapper, "function");
                if (holder == null) {
                    holder = findFieldByType(coordWrapper, DensityFunction.class);
                }

                if (holder instanceof Holder<?> h && h.isBound()) {
                    coordinateNode = visit(h.value());
                } else if (holder != null) {
                    coordinateNode = visit(holder);
                } else {
                    coordinateNode = visit(coordWrapper);
                }
            }
        }

        if (internalSpline != null) {
            return SplineRegistry.register(internalSpline, coordinateNode);
        }
        return new GpuNode.Spline(coordinateNode, -1, -2.0f, 2.0f);
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
}
