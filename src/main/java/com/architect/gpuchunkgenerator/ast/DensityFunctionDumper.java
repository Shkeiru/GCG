package com.architect.gpuchunkgenerator.ast;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.core.Holder;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Optional;

/**
 * Utilitaire Universel pour extraire l'AST des DensityFunctions de Minecraft (1.21.1 Mojmap).
 * Utilise la réflexion sur les Records pour un parsing dynamique et exhaustif.
 */
public class DensityFunctionDumper {

    public static void dumpToFile(DensityFunction function, String fileName) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== DUMP DENSITY FUNCTION AST (GCG Phase 3 - Universal Parser v2) ===\n");
        dumpRecursive(function, 0, sb);
        
        try {
            Path path = Paths.get("run", fileName);
            Files.writeString(path, sb.toString());
            System.out.println("[GCG] AST dumpé avec succès dans : " + path.toAbsolutePath());
        } catch (IOException e) {
            try {
                Files.writeString(Paths.get(fileName), sb.toString());
            } catch (IOException e2) {
                System.err.println("[GCG] Échec de l'écriture de l'AST : " + e2.getMessage());
            }
        }
    }

    private static void dumpRecursive(Object obj, int depth, StringBuilder sb) {
        String indent = "  ".repeat(depth);
        if (obj == null) {
            sb.append(indent).append("null\n");
            return;
        }

        // Unwrapping automatique des Holders
        if (obj instanceof Holder<?> holder) {
            if (holder.isBound()) {
                dumpRecursive(holder.value(), depth, sb);
            } else {
                sb.append(indent).append("- [Unbound Holder: ").append(holder).append("]\n");
            }
            return;
        }

        String className = obj.getClass().getSimpleName();

        try {
            // --- Cas Spécialisés (Labels rapides) ---
            if (obj instanceof DensityFunction) {
                if (className.equals("Constant")) {
                    sb.append(indent).append("- Constant: ").append(invoke(obj, "value")).append("\n");
                    return;
                } 
                else if (className.equals("Noise")) {
                    Object noiseHolder = invoke(obj, "noise");
                    sb.append(indent).append("- Noise: ").append(getNoiseName(noiseHolder))
                      .append(" (scale: ").append(invoke(obj, "xzScale")).append(", ").append(invoke(obj, "yScale")).append(")\n");
                    return;
                }
            }

            // --- Le Parceur Universel (Record Reflection) ---
            sb.append(indent).append("- ").append(className).append("\n");

            if (obj.getClass().isRecord()) {
                for (RecordComponent rc : obj.getClass().getRecordComponents()) {
                    try {
                        Method accessor = rc.getAccessor();
                        accessor.setAccessible(true); // INDISPENSABLE pour les classes non-publiques comme Ap2
                        Object val = accessor.invoke(obj);
                        if (val == null) continue;

                        if (val instanceof DensityFunction childFunc) {
                            sb.append(indent).append("  -> ").append(rc.getName()).append(":\n");
                            dumpRecursive(childFunc, depth + 1, sb);
                        } 
                        else if (val instanceof net.minecraft.util.CubicSpline<?, ?> spline) {
                            sb.append(indent).append("  -> SplineData (").append(rc.getName()).append("):\n");
                            dumpRecursive(spline, depth + 1, sb);
                        }
                        else if (val instanceof Collection<?> list) {
                            if (!list.isEmpty()) {
                                sb.append(indent).append("  -> List (").append(rc.getName()).append(", size=").append(list.size()).append("):\n");
                                int i = 0;
                                for (Object item : list) {
                                    if (item instanceof DensityFunction || item instanceof net.minecraft.util.CubicSpline<?, ?> || isRecord(item)) {
                                        sb.append(indent).append("    #").append(i).append(":\n");
                                        dumpRecursive(item, depth + 2, sb);
                                    }
                                    i++;
                                }
                            }
                        }
                        else if (val instanceof Number || val instanceof Boolean || val instanceof Enum<?>) {
                             sb.append(indent).append("  -> ").append(rc.getName()).append(": ").append(val).append("\n");
                        }
                        else if (isRecord(val) && !val.getClass().getName().startsWith("java.")) {
                            sb.append(indent).append("  -> Record (").append(rc.getName()).append("):\n");
                            dumpRecursive(val, depth + 1, sb);
                        }
                    } catch (Exception e) {
                        sb.append(indent).append("  -> ERROR reading component ").append(rc.getName()).append(": ").append(e.getClass().getSimpleName()).append("\n");
                    }
                }
            }
        } catch (Exception e) {
            sb.append(indent).append("- ERROR dumping ").append(className).append(": ").append(e.getClass().getSimpleName()).append("\n");
        }
    }

    private static boolean isRecord(Object obj) {
        return obj != null && obj.getClass().isRecord();
    }

    private static Object invoke(Object obj, String methodName) throws Exception {
        Method m = obj.getClass().getDeclaredMethod(methodName);
        m.setAccessible(true);
        return m.invoke(obj);
    }

    private static String getNoiseName(Object noiseHolder) {
        try {
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
