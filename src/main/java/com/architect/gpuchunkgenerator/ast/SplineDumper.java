package com.architect.gpuchunkgenerator.ast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Utilitaire pour extraire et dumper l'arborescence des CubicSplines de Minecraft.
 */
public class SplineDumper {

    private static final StringBuilder log = new StringBuilder();

    public static void clear() {
        log.setLength(0);
    }

    public static String getResult() {
        return log.toString();
    }

    public static void dumpSpline(Object cubicSpline, int depth) {
        if (cubicSpline == null) return;

        String indent = "  ".repeat(depth);
        String className = cubicSpline.getClass().getSimpleName();
        
        log.append(indent).append("[Spline] ").append(className).append("\n");

        if (className.contains("Constant")) {
            try {
                log.append(indent).append("  Value (Constant): ").append(invokeSafe(cubicSpline, "value")).append("\n");
            } catch (Exception e) {
                log.append(indent).append("  Value (Constant): Error extracting\n");
            }
            return;
        }

        if (className.contains("Multipoint")) {
            try {
                // Extraction de la coordonnée (Erosion, Continentalness, etc.)
                Object coordinate = invokeSafe(cubicSpline, "coordinate");
                log.append(indent).append("  Coordinate: ").append(coordinate != null ? coordinate.getClass().getSimpleName() : "null").append("\n");

                float[] locations = (float[]) getFieldSafe(cubicSpline, "locations");
                List<?> values = (List<?>) getFieldSafe(cubicSpline, "values");
                float[] derivatives = (float[]) getFieldSafe(cubicSpline, "derivatives");

                if (locations != null && values != null) {
                    for (int i = 0; i < locations.length; i++) {
                        float loc = locations[i];
                        Object val = values.get(i);
                        float der = (derivatives != null && i < derivatives.length) ? derivatives[i] : 0.0f;

                        log.append(indent).append("  Pt[").append(i).append("] Location: ").append(loc).append(" (Deriv: ").append(der).append(")\n");
                        
                        // Si la valeur est elle-même une spline, on récurse
                        if (val != null && !isPrimitiveValue(val)) {
                            dumpSpline(val, depth + 2);
                        } else {
                            log.append(indent).append("    Value: ").append(val).append("\n");
                        }
                    }
                }
            } catch (Exception e) {
                log.append(indent).append("  [Error] Failed to dump Multipoint: ").append(e.getMessage()).append("\n");
            }
        }
    }

    private static boolean isPrimitiveValue(Object val) {
        return val instanceof Float || val instanceof Double || val instanceof Integer || val instanceof Long;
    }

    private static Object invokeSafe(Object obj, String methodName) {
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

    private static Object getFieldSafe(Object obj, String fieldName) {
        try {
            Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Exception e) {
            try {
                // Record component access
                Method m = obj.getClass().getMethod(fieldName);
                m.setAccessible(true);
                return m.invoke(obj);
            } catch (Exception e2) {
                return null;
            }
        }
    }
}
